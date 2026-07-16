package tel.eden.mod.hud;

import net.minecraft.client.gui.GuiGraphics;

/** A solid ARGB rectangle, used as a translucent panel background. */
public record RectangleElement(int x, int y, int width, int height, int argb) implements HudElement {

	@Override
	public void draw(GuiGraphics graphics) {
		graphics.fill(x, y, x + width, y + height, argb);
	}
}
