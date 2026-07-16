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
 * or the last one they were in — is drawn: its border is a run of short vertical bars
 * that sit on the ground surface and rise/fall with the terrain, forming a wall.
 * Green while the player is inside that territory, red once they step outside it. Other
 * territories are never shown.
 */
public final class TerritoryOutlineRenderer {
	private TerritoryOutlineRenderer() {
	}

	private static final int GREEN = 0xFF33CC33;
	private static final int RED = 0xFFFF4040;
	private static final float LINE_WIDTH = 3.0f;
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
	// territory changes. Each entry is a bar base position [x, groundY, z].
	private static final List<float[]> cachedBars = new ArrayList<>();
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
		int color = lockedTerritory.equals(inside) ? GREEN : RED;
		for (float[] bar : cachedBars) {
			Gizmos.line(new Vec3(bar[0], bar[1], bar[2]), new Vec3(bar[0], bar[1] + WALL_HEIGHT, bar[2]), color, LINE_WIDTH).setAlwaysOnTop();
		}
	}

	/** Recompute the wall's bar positions for the border columns near the player. */
	private static void rebuildWall(Level level, int[] rect, BlockPos player) {
		cachedBars.clear();
		int minX = rect[0];
		int minZ = rect[1];
		int maxX = rect[2] + 1;
		int maxZ = rect[3] + 1;
		int px = player.getX();
		int pz = player.getZ();
		int py = player.getY();
		// Two Z-aligned edges (west/east) and two X-aligned edges (north/south).
		for (int z = minZ; z <= maxZ; z++) {
			addBar(level, minX, z, px, pz, py);
			addBar(level, maxX, z, px, pz, py);
		}
		for (int x = minX; x <= maxX; x++) {
			addBar(level, x, minZ, px, pz, py);
			addBar(level, x, maxZ, px, pz, py);
		}
	}

	private static void addBar(Level level, int x, int z, int px, int pz, int py) {
		if (Math.abs(x - px) > DRAW_RADIUS || Math.abs(z - pz) > DRAW_RADIUS) {
			return;
		}
		cachedBars.add(new float[]{x, groundY(level, x, z, py), z});
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
