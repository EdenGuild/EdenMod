package tel.eden.mod.war;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
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
 * Draws the boundary of a single territory as a flat wall, using the per-frame debug
 * gizmos (added during {@code WorldRenderEvents.AFTER_ENTITIES}).
 *
 * <p>A keybind (comma by default) toggles it on, but it only draws while a territory is
 * actually under attack (from the scoreboard timers) — the border of the attacked
 * territory becomes a full-height translucent wall (world bottom to top, no terrain
 * sampling) with a bright top rail. Green while the player is standing in the attacked
 * territory, red once they step outside it into an unattacked territory (or none). When
 * several are attacked at once it shows the one the player is in, else the soonest.
 */
public final class TerritoryOutlineRenderer {
	private TerritoryOutlineRenderer() {
	}

	private static final int RAIL_GREEN = 0xFF44FF44;
	private static final int RAIL_RED = 0xFFFF5050;
	private static final int FILL_GREEN = 0x5533CC33;
	private static final int FILL_RED = 0x55FF4040;
	private static final float RAIL_WIDTH = 2.5f;
	// The wall spans the full world height (a taller quad is the same 4 vertices, so this
	// costs no more than a short wall). Covers the vanilla build range with margin.
	private static final int WALL_BOTTOM = -64;
	private static final int WALL_TOP = 320;
	// Only draw border columns within this many blocks of the player.
	private static final int DRAW_RADIUS = 48;

	private static KeyMapping key;
	private static boolean enabled;

	// Cached border columns near the player, split into contiguous in-range runs so
	// quads never bridge a gap. Each column is an {x, z} pair; the Y is the full-height
	// baseline applied fresh each frame.
	private static final List<List<int[]>> cachedRuns = new ArrayList<>();
	private static String cachedTerritory;
	private static long cachedPos = Long.MIN_VALUE;

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
				mc.player.displayClientMessage(Component.literal("Territory wall " + (enabled ? "enabled" : "disabled")).withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED), true);
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
		// Only render while a territory is actually under attack; show the one being
		// attacked. When defending several, prefer the one the player is standing in.
		List<String> attacked = AttackTimerMenu.attackedTerritories();
		if (attacked.isEmpty()) {
			return;
		}
		BlockPos pos = player.blockPosition();
		String inside = TerritoryData.territoryAt(pos.getX(), pos.getZ());
		String target = (inside != null && attacked.contains(inside)) ? inside : attacked.get(0);
		int[] rect = TerritoryData.rect(target);
		if (rect == null) {
			return;
		}
		long posKey = (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
		if (!target.equals(cachedTerritory) || posKey != cachedPos) {
			rebuildWall(rect, pos);
			cachedTerritory = target;
			cachedPos = posKey;
		}
		// Full-height wall from world bottom to top (flat, terrain-independent).
		double base = WALL_BOTTOM;
		double top = WALL_TOP;
		// Green while standing in the attacked territory, red when outside it (in another
		// territory not under attack, or none).
		boolean here = target.equals(inside);
		int fill = here ? FILL_GREEN : FILL_RED;
		int rail = here ? RAIL_GREEN : RAIL_RED;
		for (List<int[]> run : cachedRuns) {
			for (int i = 0; i + 1 < run.size(); i++) {
				int[] a = run.get(i);
				int[] b = run.get(i + 1);
				Vec3 bottomA = new Vec3(a[0], base, a[1]);
				Vec3 topA = new Vec3(a[0], top, a[1]);
				Vec3 bottomB = new Vec3(b[0], base, b[1]);
				Vec3 topB = new Vec3(b[0], top, b[1]);
				// Translucent wall face (both windings so it shows from either side) plus
				// a crisp top rail.
				Gizmos.rect(topA, topB, bottomB, bottomA, GizmoStyle.fill(fill)).setAlwaysOnTop();
				Gizmos.rect(topB, topA, bottomA, bottomB, GizmoStyle.fill(fill)).setAlwaysOnTop();
				Gizmos.line(topA, topB, rail, RAIL_WIDTH).setAlwaysOnTop();
			}
		}
	}

	/** Recompute the in-range border columns, split into contiguous runs. */
	private static void rebuildWall(int[] rect, BlockPos player) {
		cachedRuns.clear();
		int minX = rect[0];
		int minZ = rect[1];
		int maxX = rect[2] + 1;
		int maxZ = rect[3] + 1;
		int px = player.getX();
		int pz = player.getZ();
		edgeAlongZ(minX, minZ, maxZ, px, pz);
		edgeAlongZ(maxX, minZ, maxZ, px, pz);
		edgeAlongX(minZ, minX, maxX, px, pz);
		edgeAlongX(maxZ, minX, maxX, px, pz);
	}

	private static void edgeAlongZ(int x, int z0, int z1, int px, int pz) {
		List<int[]> run = new ArrayList<>();
		for (int z = z0; z <= z1; z++) {
			if (Math.abs(x - px) > DRAW_RADIUS || Math.abs(z - pz) > DRAW_RADIUS) {
				run = closeRun(run);
				continue;
			}
			run.add(new int[]{x, z});
		}
		closeRun(run);
	}

	private static void edgeAlongX(int z, int x0, int x1, int px, int pz) {
		List<int[]> run = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			if (Math.abs(x - px) > DRAW_RADIUS || Math.abs(z - pz) > DRAW_RADIUS) {
				run = closeRun(run);
				continue;
			}
			run.add(new int[]{x, z});
		}
		closeRun(run);
	}

	private static List<int[]> closeRun(List<int[]> run) {
		if (run.size() >= 2) {
			cachedRuns.add(run);
		}
		return new ArrayList<>();
	}
}
