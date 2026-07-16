package tel.eden.mod.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A single line of drop-shadowed text. Accepts legacy section-sign colour codes
 * (e.g. {@code §b}) via {@link Component} so ported war-suite strings render with
 * the same colours they had in the reference implementation.
 */
public record TextElement(String text, int x, int y, int argb) implements HudElement {

	@Override
	public void draw(GuiGraphics graphics) {
		graphics.drawString(Minecraft.getInstance().font, Component.literal(text), x, y, argb);
	}
}
