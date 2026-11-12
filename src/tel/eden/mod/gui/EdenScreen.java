package tel.eden.mod.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Base of every EdenMod screen: gives them all the same backdrop, see {@link EdenBackdrop}. */
public abstract class EdenScreen extends Screen implements EdenBackdrop {
	protected EdenScreen(Component title) {
		super(title);
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.fill(0, 0, this.width, this.height, DIM);
	}

	// Vanilla's own darkening is left out; the dim above replaces it, and the blur is capped by OptionsMixin.
	@Override
	protected void renderMenuBackground(GuiGraphics guiGraphics) {
	}

	@Override
	protected void renderMenuBackground(GuiGraphics guiGraphics, int x, int y, int width, int height) {
	}
}
