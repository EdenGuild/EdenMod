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
 * <p>A keybind (comma by default) toggles it on. Only the territory the player is in —
 * or the last one they were in — is drawn: its border becomes a translucent wall with a
 * bright top rail. The wall uses a flat baseline anchored to the player's current height
 * (no terrain sampling), so it floats level with the player and rises/falls as they do.
 * Green while the player is inside that territory, red once they step outside it. Other
 * territories are never shown.
 */
public final class TerritoryOutlineRenderer {
	private TerritoryOutlineRenderer() {
	}

	private static final int RAIL_GREEN = 0xFF44FF44;
	private static final int RAIL_RED = 0xFFFF5050;
	private static final int FILL_GREEN = 0x5533CC33;
	private static final int FILL_RED = 0x55FF4040;
	private static final float RAIL_WIDTH = 2.5f;
	// The wall spans from BASE_BELOW blocks under the player's feet to WALL_HEIGHT above.
	private static final int BASE_BELOW = 2;
	private static final int WALL_HEIGHT = 6;
	// Only draw border columns within this many blocks of the player.
	private static final int DRAW_RADIUS = 48;

	private static KeyMapping key;
	private static boolean enabled;
	private static String lockedTerritory;

	// Cached border columns near the player, split into contiguous in-range runs so
	// quads never bridge a gap. Each column is an {x, z} pair; the Y is a flat baseline
	// applied fresh each frame from the player's height (so no rebuild on vertical move).
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
		BlockPos pos = player.blockPosition();
		String inside = TerritoryData.territoryAt(pos.getX(), pos.getZ());
		// Lock onto whichever territory the player is currently standing in; if they've
		// stepped out, keep the last one but draw it red.
		if (inside != null) {
			lockedTerritory = inside;
		}
		if (lockedTerritory == null) {
			return;
		}
		int[] rect = TerritoryData.rect(lockedTerritory);
		if (rect == null) {
			return;
		}
		long posKey = (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
		if (!lockedTerritory.equals(cachedTerritory) || posKey != cachedPos) {
			rebuildWall(rect, pos);
			cachedTerritory = lockedTerritory;
			cachedPos = posKey;
		}
		// Flat baseline anchored to the player's current feet height.
		double base = player.getY() - BASE_BELOW;
		double top = base + WALL_HEIGHT;
		boolean here = lockedTerritory.equals(inside);
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
