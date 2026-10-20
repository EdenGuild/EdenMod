package tel.eden.mod.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import tel.eden.mod.config.BridgeConfig;

/**
 * Draws a HUD panel at its configured position and scale. The panel's elements are
 * built at the origin {@code (0, 0)}; this applies the saved translate + scale so a
 * feature never has to know its own screen placement. Panels can move beyond screen bounds.
 */
public final class HudRender {
	private HudRender() {
	}

	public static void draw(BridgeConfig config, String name, float defaultX, float defaultY, int width, int height, HudPanel panel, GuiGraphics graphics) {
		float scale = HudLayout.scale(config, name);
		int x = HudLayout.x(config, name, defaultX, defaultY);
		int y = HudLayout.y(config, name, defaultX, defaultY);

		graphics.pose().pushMatrix();
		graphics.pose().translate(x, y);
		graphics.pose().scale(scale, scale);
		panel.draw(graphics);
		graphics.pose().popMatrix();
	}
}
