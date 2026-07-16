package tel.eden.mod.war;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.config.BridgeConfig;

/**
 * Renders territory boundary boxes in the world with debug gizmos. A keybind (comma
 * by default) toggles outlining <em>all</em> nearby territories — green for the one
 * you're standing in, red otherwise; when that's off, the soonest upcoming attack is
 * still outlined if {@code warOutlineSoonest} is on. Drawn during
 * {@code WorldRenderEvents.AFTER_ENTITIES} so gizmos land in the per-frame collector.
 */
public final class TerritoryOutlineRenderer {
	private TerritoryOutlineRenderer() {
	}

	private static final int GREEN = 0xFF33CC33;
	private static final int RED = 0xFFFF4040;
	private static final float LINE_WIDTH = 2.0f;
	// Vertical half-extent of the drawn box around the player's Y.
	private static final int Y_SPAN = 45;
	// Skip territories whose nearest edge is beyond this (blocks) when showing all.
	private static final int MAX_DISTANCE = 500;

	private static KeyMapping key;
	private static boolean showAll;

	public static void register(KeyMapping.Category category) {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.edenmod.toggle_territory_outlines", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, category));
	}

	public static void onTick(Minecraft mc) {
		if (key == null) {
			return;
		}
		while (key.consumeClick()) {
			showAll = !showAll;
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal("Territory outlines " + (showAll ? "enabled" : "disabled")).withStyle(showAll ? ChatFormatting.GREEN : ChatFormatting.RED), true);
			}
		}
	}

	public static void render(BridgeConfig config) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		BlockPos pos = mc.player.blockPosition();
		int y0 = pos.getY() - Y_SPAN;
		int y1 = pos.getY() + Y_SPAN;
		String current = TerritoryData.territoryAt(pos.getX(), pos.getZ());

		if (showAll) {
			for (String name : TerritoryData.territoryNames()) {
				int[] rect = TerritoryData.rect(name);
				if (rect == null) {
					continue;
				}
				boolean inside = name.equals(current);
				if (!inside && distance(rect, pos.getX(), pos.getZ()) > MAX_DISTANCE) {
					continue;
				}
				drawBox(rect, y0, y1, inside ? GREEN : RED);
			}
			return;
		}
		if (config.warOutlineSoonest) {
			String soonest = AttackTimerMenu.soonestTerritory();
			if (soonest != null) {
				int[] rect = TerritoryData.rect(soonest);
				if (rect != null) {
					drawBox(rect, y0, y1, GREEN);
				}
			}
		}
	}

	private static void drawBox(int[] rect, int y0, int y1, int argb) {
		Gizmos.cuboid(new AABB(rect[0], y0, rect[1], rect[2] + 1.0, y1, rect[3] + 1.0), GizmoStyle.stroke(argb, LINE_WIDTH)).setAlwaysOnTop();
	}

	private static int distance(int[] rect, int x, int z) {
		int dx = Math.max(0, Math.max(rect[0] - x, x - rect[2]));
		int dz = Math.max(0, Math.max(rect[1] - z, z - rect[3]));
		return dx + dz;
	}
}
