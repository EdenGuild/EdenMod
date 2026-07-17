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
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Draws the boundary of a single territory as a terrain-following wall, using the
 * per-frame debug gizmos (added during {@code WorldRenderEvents.AFTER_ENTITIES}).
 *
 * <p>A keybind (comma by default) toggles it on. Only the territory the player is in —
 * or the last one they were in — is drawn: its border is a translucent wall face with a
 * bright top rail, its base following the ground surface so it rises/falls with terrain.
 * Green while the player is inside that territory, red once they step outside it. Other
 * territories are never shown.
 */
public final class TerritoryOutlineRenderer {
	private TerritoryOutlineRenderer() {
	}

	// Bright top-rail colours and translucent fill colours (ARGB).
	private static final int RAIL_GREEN = 0xFF44FF44;
	private static final int RAIL_RED = 0xFFFF5050;
	private static final int FILL_GREEN = 0x5533CC33;
	private static final int FILL_RED = 0x55FF4040;
	private static final float RAIL_WIDTH = 2.5f;
	// Wall height above the ground surface, in blocks.
	private static final int WALL_HEIGHT = 4;
	// Only draw border columns within this many blocks of the player (the rest are out
	// of sight and their chunks may not be loaded, so the surface can't be found).
	private static final int DRAW_RADIUS = 48;
	// How far up/down from the player's feet to search for the ground surface.
	private static final int SCAN_UP = 24;
	private static final int SCAN_DOWN = 48;

	private static KeyMapping key;
	private static boolean enabled;
	private static String lockedTerritory;

	// The wall geometry is expensive to compute (a block scan per border column), so it
	// is cached and only rebuilt when the player moves to a new block or the tracked
	// territory changes. Each run is a contiguous stretch of in-range border columns,
	// each column a base position [x, groundY, z]; runs break across out-of-range gaps
	// so quads never bridge a gap.
	private static final List<List<float[]>> cachedRuns = new ArrayList<>();
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
		Level level = mc.level;
		if (player == null || level == null) {
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
			rebuildWall(level, rect, pos);
			cachedTerritory = lockedTerritory;
			cachedPos = posKey;
		}
		boolean here = lockedTerritory.equals(inside);
		int fill = here ? FILL_GREEN : FILL_RED;
		int rail = here ? RAIL_GREEN : RAIL_RED;
		for (List<float[]> run : cachedRuns) {
			for (int i = 0; i + 1 < run.size(); i++) {
				float[] a = run.get(i);
				float[] b = run.get(i + 1);
				Vec3 bottomA = new Vec3(a[0], a[1], a[2]);
				Vec3 topA = new Vec3(a[0], a[1] + WALL_HEIGHT, a[2]);
				Vec3 bottomB = new Vec3(b[0], b[1], b[2]);
				Vec3 topB = new Vec3(b[0], b[1] + WALL_HEIGHT, b[2]);
				// Translucent wall face between the two columns, plus a crisp top rail.
				// Filled quads are backface-culled, so draw both windings to make the
				// wall visible from either side of the border.
				Gizmos.rect(topA, topB, bottomB, bottomA, GizmoStyle.fill(fill)).setAlwaysOnTop();
				Gizmos.rect(topB, topA, bottomA, bottomB, GizmoStyle.fill(fill)).setAlwaysOnTop();
				Gizmos.line(topA, topB, rail, RAIL_WIDTH).setAlwaysOnTop();
			}
		}
	}

	/** Recompute the wall's columns, split into contiguous in-range runs. */
	private static void rebuildWall(Level level, int[] rect, BlockPos player) {
		cachedRuns.clear();
		int minX = rect[0];
		int minZ = rect[1];
		int maxX = rect[2] + 1;
		int maxZ = rect[3] + 1;
		int px = player.getX();
		int pz = player.getZ();
		int py = player.getY();
		// West/east edges vary z; north/south edges vary x.
		edgeAlongZ(level, minX, minZ, maxZ, px, pz, py);
		edgeAlongZ(level, maxX, minZ, maxZ, px, pz, py);
		edgeAlongX(level, minZ, minX, maxX, px, pz, py);
		edgeAlongX(level, maxZ, minX, maxX, px, pz, py);
	}

	private static void edgeAlongZ(Level level, int x, int z0, int z1, int px, int pz, int py) {
		List<float[]> run = new ArrayList<>();
		for (int z = z0; z <= z1; z++) {
			if (Math.abs(x - px) > DRAW_RADIUS || Math.abs(z - pz) > DRAW_RADIUS) {
				run = closeRun(run);
				continue;
			}
			run.add(new float[]{x, groundY(level, x, z, py), z});
		}
		closeRun(run);
	}

	private static void edgeAlongX(Level level, int z, int x0, int x1, int px, int pz, int py) {
		List<float[]> run = new ArrayList<>();
		for (int x = x0; x <= x1; x++) {
			if (Math.abs(x - px) > DRAW_RADIUS || Math.abs(z - pz) > DRAW_RADIUS) {
				run = closeRun(run);
				continue;
			}
			run.add(new float[]{x, groundY(level, x, z, py), z});
		}
		closeRun(run);
	}

	private static List<float[]> closeRun(List<float[]> run) {
		if (run.size() >= 2) {
			cachedRuns.add(run);
		}
		return new ArrayList<>();
	}

	/**
	 * Surface Y at a column, found by scanning down for the first solid block near the
	 * player's feet. The client heightmap is unreliable on a server world (it can report
	 * the world bottom), so blocks are read directly. Falls back to the player's Y.
	 */
	private static float groundY(Level level, int x, int z, int py) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = py + SCAN_UP; y >= py - SCAN_DOWN; y--) {
			pos.set(x, y, z);
			if (!level.getBlockState(pos).isAir()) {
				return y + 1.0f;
			}
		}
		return py;
	}
}
