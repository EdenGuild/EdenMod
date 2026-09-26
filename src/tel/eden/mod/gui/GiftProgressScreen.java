package tel.eden.mod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import tel.eden.mod.reward.GuildRewards;

/**
 * Blocking modal shown for a Chief-initiated gift/payout run's entire duration —
 * keeps the automation visible and in the player's control instead of driving the
 * guild-manage menu invisibly in the background while they're free to keep playing.
 * Purely a status display: it never touches the container actually being driven,
 * which is still handled by {@code BackgroundContainerSession} exactly as before.
 *
 * <p>Only {@link #onDone} closes it — Cancel doesn't dismiss the screen itself, it
 * just asks the run to stop (via {@link GuildRewards#requestCancel}), so the screen
 * stays up (showing "Cancelling...") until the run has actually finished stopping.
 * That's deliberate: closing early would mean the automation keeps driving the menu
 * unseen, which defeats the point of showing this at all.
 */
public final class GiftProgressScreen extends EdenReferenceScreen implements GuildRewards.GiftProgressListener {
	private static final int BASE_PANEL_WIDTH = 320;
	private static final int BASE_PANEL_HEIGHT = 110;

	private final GuildRewards guildRewards;

	private EdenPanelLayout layout;
	private Button cancelButton;
	// Updated from the worker thread via the GiftProgressListener callbacks below;
	// read from the render thread in render(). Plain volatile fields are enough here
	// (no compound invariant across them needs to be atomic — a render seeing an
	// old status next to a new count for one frame is harmless).
	private volatile String status = "Starting...";
	private volatile int completed;
	private volatile int total;
	private volatile boolean cancelling;
	private volatile boolean done;

	public GiftProgressScreen(String title, GuildRewards guildRewards) {
		super(Component.literal(title));
		this.guildRewards = guildRewards;
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
		guildRewards.requestCancel();
	}

	// -- GuildRewards.GiftProgressListener — called from the worker thread ----------

	@Override
	public void onProgress(String status, int completed, int total) {
		this.status = status;
		this.completed = completed;
		this.total = total;
	}

	@Override
	public void onDone() {
		done = true;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.screen == this) {
				mc.setScreen(null);
			}
		});
	}

	/**
	 * Whether the run this screen was created for has already finished. The caller
	 * that shows this screen defers {@code setScreen} to the next tick (see
	 * {@code EdenModClient#showGiftProgress}); this lets that deferred call skip
	 * showing a screen for a run so fast it already finished, which would otherwise
	 * get stuck open forever — {@link #onDone} only closes it if it's already current.
	 */
	public boolean isDone() {
		return done;
	}

	// -- rendering --------------------------------------------------------------

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
		int currentTotal = total;
		if (currentTotal > 0) {
			int filled = Math.round(barWidth * Math.min(1.0f, completed / (float) currentTotal));
			g.fill(barLeft, barTop, barLeft + filled, barTop + barHeight, 0xFF55DD55);
			g.drawCenteredString(this.font, completed + " / " + currentTotal, layout.centerX(), barTop + layout.h(3), 0xFFFFFFFF);
		} else {
			// Indeterminate phase (opening the menu, confirming with the Guild Log, ...) —
			// no meaningful fraction yet, so just a flat fill rather than a fake 0% bar.
			g.fill(barLeft, barTop, barLeft + barWidth, barTop + barHeight, 0xFF3A6A3A);
		}

		popReferencePose(g);
	}

	// -- input --------------------------------------------------------------------

	/** The only way out is Cancel — closing on Esc would leave the run driving the menu unseen. */
	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
