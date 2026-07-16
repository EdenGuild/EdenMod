package tel.eden.mod.war;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
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
	// of sight and their chunks may be unloaded, so the heightmap would be wrong).
	private static final int DRAW_RADIUS = 48;

	private static KeyMapping key;
	private static boolean enabled;
	private static String lockedTerritory;

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
		int color = lockedTerritory.equals(inside) ? GREEN : RED;
		drawWall(level, rect, pos.getX(), pos.getZ(), color);
	}

	private static void drawWall(Level level, int[] rect, int px, int pz, int color) {
		int minX = rect[0];
		int minZ = rect[1];
		int maxX = rect[2] + 1;
		int maxZ = rect[3] + 1;
		// Two Z-aligned edges (west/east) and two X-aligned edges (north/south).
		for (int z = minZ; z <= maxZ; z++) {
			bar(level, minX, z, px, pz, color);
			bar(level, maxX, z, px, pz, color);
		}
		for (int x = minX; x <= maxX; x++) {
			bar(level, x, minZ, px, pz, color);
			bar(level, x, maxZ, px, pz, color);
		}
	}

	private static void bar(Level level, int x, int z, int px, int pz, int color) {
		if (Math.abs(x - px) > DRAW_RADIUS || Math.abs(z - pz) > DRAW_RADIUS) {
			return;
		}
		double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		Gizmos.line(new Vec3(x, ground, z), new Vec3(x, ground + WALL_HEIGHT, z), color, LINE_WIDTH).setAlwaysOnTop();
	}
}
