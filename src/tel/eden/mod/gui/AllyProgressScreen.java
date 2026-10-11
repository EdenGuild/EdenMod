package tel.eden.mod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Blocking modal shown while an ally add or remove operation runs via
 * BackgroundContainerSession — blocks gameplay input and keeps progress visible
 * (matching /eden dump and /eden gift).
 */
public final class AllyProgressScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 320;
	private static final int BASE_PANEL_HEIGHT = 110;

	public interface ProgressListener {
		void onProgress(String status);
		void onDone();
		void requestCancel();
	}

	private final ProgressListener listener;
	private EdenPanelLayout layout;
	private Button cancelButton;

	private volatile String status = "Starting...";
	private volatile boolean cancelling;
	private volatile boolean done;

	public AllyProgressScreen(String title, ProgressListener listener) {
		super(Component.literal(title));
		this.listener = listener;
	}

	@Override
	protected void init() {
		super.init();
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);
		cancelButton = this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> requestCancel()).bounds(layout.x(110), layout.y(80), layout.w(100), layout.h(20)).build());
	}

	private void requestCancel() {
		if (done || cancelling) {
			return;
		}
		cancelling = true;
		cancelButton.active = false;
		if (listener != null) {
			listener.requestCancel();
		}
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public void markDone() {
		done = true;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.screen == this) {
				mc.setScreen(null);
			}
		});
	}

	public boolean isDone() {
		return done;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		this.renderMenuBackground(g);
		pushReferencePose(g);
		layout.drawBackground(g);
		layout.drawPanel(g);
		super.render(g, scaledMouseX, scaledMouseY, delta);

		g.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(14), 0xFFFFFFFF);
		g.drawCenteredString(this.font, cancelling ? "Cancelling..." : status, layout.centerX(), layout.y(36), 0xFFCCCCCC);

		int barLeft = layout.x(20);
		int barTop = layout.y(54);
		int barWidth = layout.w(BASE_PANEL_WIDTH - 40);
		int barHeight = layout.h(14);
		g.fill(barLeft, barTop, barLeft + barWidth, barTop + barHeight, 0xFF1E1E1E);
		g.fill(barLeft, barTop, barLeft + barWidth, barTop + barHeight, 0xFF3A6A3A);

		popReferencePose(g);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
