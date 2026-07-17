package tel.eden.mod.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import tel.eden.mod.config.BridgeConfig;

/**
 * Draws a HUD panel at its configured position and scale. The panel's elements are
 * built at the origin {@code (0, 0)}; this applies the saved translate + scale so a
 * feature never has to know its own screen placement. Position is clamped so the scaled
 * panel stays on screen.
 */
public final class HudRender {
	private HudRender() {
	}

	public static void draw(BridgeConfig config, String name, float defaultX, float defaultY, int width, int height, HudPanel panel, GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		float scale = HudLayout.scale(config, name);
		int screenW = mc.getWindow().getGuiScaledWidth();
		int screenH = mc.getWindow().getGuiScaledHeight();
		int scaledW = Math.round(width * scale);
		int scaledH = Math.round(height * scale);
		int x = Math.max(0, Math.min(HudLayout.x(config, name, defaultX, defaultY), screenW - scaledW));
		int y = Math.max(0, Math.min(HudLayout.y(config, name, defaultX, defaultY), screenH - scaledH));

		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		panel.draw(graphics);
		graphics.pose().popMatrix();
	}
}
