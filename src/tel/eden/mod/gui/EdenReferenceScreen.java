package tel.eden.mod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

abstract class EdenReferenceScreen extends Screen {
	protected float uiScale = 1.0f;
	protected int virtualWidth;
	protected int virtualHeight;

	/** The screen to return to when this one closes, or null to return to the game. */
	protected final Screen parent;

	/**
	 * Every screen says where it returns to. Pass {@code null} on purpose for a screen that is not opened
	 * from another one, such as the modal progress screens.
	 */
	protected EdenReferenceScreen(Component title, Screen parent) {
		super(title);
		this.parent = parent;
	}

	/**
	 * Escape and the like go back to the screen this one was opened from, as the on-screen Back buttons
	 * do. This is final on purpose: a screen that has something to save first overrides
	 * {@link #beforeClose()}, so none can end up going somewhere else by mistake.
	 *
	 * <p>Modal screens that must not be dismissed (the gifting and ally progress screens) do not reach
	 * this from Escape: they return false from {@code shouldCloseOnEsc()}.
	 */
	@Override
	public final void onClose() {
		beforeClose();
		this.minecraft.setScreen(parent);
	}

	/** Runs just before the screen closes, for a screen that has something to save or commit first. */
	protected void beforeClose() {
	}

	/** Picks the scale this screen is drawn at, then lays the panel out inside the resulting space. */
	protected final EdenPanelLayout centeredLayout(int basePanelWidth, int basePanelHeight) {
		uiScale = EdenPanelLayout.fittingScaleFactor(this.width, this.height, basePanelWidth, basePanelHeight, (int) Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale())));
		virtualWidth = Math.round(this.width / uiScale);
		virtualHeight = Math.round(this.height / uiScale);
		return EdenPanelLayout.centered(virtualWidth, virtualHeight, basePanelWidth, basePanelHeight);
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
