package tel.eden.mod.war;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Draws the boundary of the territory the player is standing in as a flat, full-height
 * wall using the per-frame debug gizmos (added during
 * {@code WorldRenderEvents.AFTER_ENTITIES}).
 *
 * <p>A keybind (comma by default) toggles it on. While on, it always shows the border of
 * the player's <em>current</em> territory as four full-height translucent walls (world
 * bottom to top, no terrain sampling) with a bright top rail — <b>green</b> when that
 * territory is under attack, <b>red</b> otherwise. The walls are depth-tested (not
 * always-on-top), so terrain occludes them: they hide behind blocks. Walking into a
 * different territory
 * switches the wall to that territory's border; standing outside all territories shows
 * nothing. Edges sit at fixed territory borders and are drawn only when the player is near
 * them (distance-culled to spare fill-rate on large territories), so a wall fades in as you
 * approach a border rather than following you.
 */
public final class TerritoryOutlineRenderer {
	private TerritoryOutlineRenderer() {
	}

	private static final int RAIL_GREEN = 0xFF44FF44;
	private static final int RAIL_RED = 0xFFFF5050;
	private static final int FILL_GREEN = 0x5533CC33;
	private static final int FILL_RED = 0x55FF4040;
	private static final float RAIL_WIDTH = 2.5f;
	// The wall spans most of the world height; the quad is the same 4 vertices regardless of
	// height, but a shorter one is less translucent overdraw for low-end GPUs.
	private static final int WALL_BOTTOM = -64;
	private static final int WALL_TOP = 270;
	// Only draw edges whose nearest point is within this many blocks of the player. A big
	// territory otherwise paints distant full-height translucent walls, which is fill-rate
	// heavy; edges stay fixed, so a wall just fades in as you approach a border.
	private static final double CULL_DIST = 96.0;

	private static KeyMapping key;
	private static boolean enabled;

	public static void register(KeyMapping.Category category) {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.edenmod.toggle_territory_outlines", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, category));
	}

	public static void onTick(Minecraft mc) {
		if (key == null) {
			return;
		}
		while (key.consumeClick()) {
			enabled = !enabled;
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal("Territory wall " + (enabled ? "enabled" : "disabled")).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED), false);
			}
		}
	}

	public static void render() {
		if (!enabled) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		// Always show the border of the territory the player is standing in; nothing when
		// outside all territories.
		BlockPos pos = player.blockPosition();
		String inside = TerritoryData.territoryAt(pos.getX(), pos.getZ());
		if (inside == null) {
			return;
		}
		int[] rect = TerritoryData.rect(inside);
		if (rect == null) {
			return;
		}
		// Green while the current territory is under attack, red otherwise.
		boolean attacked = AttackTimerMenu.attackedTerritories().contains(inside);
		int fill = attacked ? FILL_GREEN : FILL_RED;
		int rail = attacked ? RAIL_GREEN : RAIL_RED;

		// Territory corners (rect is [minX, minZ, maxX, maxZ]; +1 to cover the full block).
		int minX = rect[0];
		int minZ = rect[1];
		int maxX = rect[2] + 1;
		int maxZ = rect[3] + 1;
		int px = pos.getX();
		int pz = pos.getZ();
		maybeWall(minX, minZ, maxX, minZ, px, pz, fill, rail);
		maybeWall(maxX, minZ, maxX, maxZ, px, pz, fill, rail);
		maybeWall(maxX, maxZ, minX, maxZ, px, pz, fill, rail);
		maybeWall(minX, maxZ, minX, minZ, px, pz, fill, rail);
	}

	/** Draw an edge only when the player is within {@link #CULL_DIST} of it. */
	private static void maybeWall(int x1, int z1, int x2, int z2, int px, int pz, int fill, int rail) {
		if (segmentDistance(px, pz, x1, z1, x2, z2) <= CULL_DIST) {
			wall(x1, z1, x2, z2, fill, rail);
		}
	}

	/** Nearest distance from (px, pz) to the segment (x1,z1)-(x2,z2) in the XZ plane. */
	private static double segmentDistance(int px, int pz, int x1, int z1, int x2, int z2) {
		double dx = x2 - x1;
		double dz = z2 - z1;
		double lenSq = dx * dx + dz * dz;
		double t = lenSq == 0 ? 0 : ((px - x1) * dx + (pz - z1) * dz) / lenSq;
		t = Math.max(0, Math.min(1, t));
		double cx = x1 + t * dx;
		double cz = z1 + t * dz;
		return Math.hypot(px - cx, pz - cz);
	}

	/** Draw one full-height wall segment from (x1,z1) to (x2,z2), visible from both sides. */
	private static void wall(int x1, int z1, int x2, int z2, int fill, int rail) {
		Vec3 bottom1 = new Vec3(x1, WALL_BOTTOM, z1);
		Vec3 top1 = new Vec3(x1, WALL_TOP, z1);
		Vec3 bottom2 = new Vec3(x2, WALL_BOTTOM, z2);
		Vec3 top2 = new Vec3(x2, WALL_TOP, z2);
		// Both windings so the filled face shows from either side; plus a crisp top rail.
		// No setAlwaysOnTop() — the gizmos are depth-tested by default, so the wall is
		// occluded by terrain (hidden behind blocks) without any block scanning.
		Gizmos.rect(top1, top2, bottom2, bottom1, GizmoStyle.fill(fill));
		Gizmos.rect(top2, top1, bottom1, bottom2, GizmoStyle.fill(fill));
		Gizmos.line(top1, top2, rail, RAIL_WIDTH);
	}
}
