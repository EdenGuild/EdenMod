package tel.eden.mod.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

abstract class EdenReferenceScreen extends Screen {
	protected float uiScale = 1.0f;
	protected int virtualWidth;
	protected int virtualHeight;

	protected EdenReferenceScreen(Component title) {
		super(title);
	}

	protected final void updateReferenceSpace() {
		uiScale = EdenPanelLayout.referenceScaleFactor();
		virtualWidth = Math.round(this.width / uiScale);
		virtualHeight = Math.round(this.height / uiScale);
	}

	protected final int scaledMouseX(int mouseX) {
		return Math.round(mouseX / uiScale);
	}

	protected final int scaledMouseY(int mouseY) {
		return Math.round(mouseY / uiScale);
	}

	protected final void pushReferencePose(GuiGraphics guiGraphics) {
		guiGraphics.pose().pushMatrix();
		guiGraphics.pose().scale(uiScale, uiScale);
	}

	protected final void popReferencePose(GuiGraphics guiGraphics) {
		guiGraphics.pose().popMatrix();
	}

	protected final MouseButtonEvent rescale(MouseButtonEvent event) {
		return new MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo());
	}
}
