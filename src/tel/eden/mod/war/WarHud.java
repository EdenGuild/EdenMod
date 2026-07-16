package tel.eden.mod.war;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import tel.eden.mod.config.BridgeConfig;

/**
 * Single entry point for the war-suite 2D HUD overlays, dispatched from one
 * {@code HudRenderCallback}. No-ops entirely unless the player is in a world; each
 * overlay further checks its own config toggle.
 */
public final class WarHud {
	private WarHud() {
	}

	public static void render(BridgeConfig config, GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.options.hideGui) {
			return;
		}
		AttackTimerMenu.render(config, graphics);
	}
}
