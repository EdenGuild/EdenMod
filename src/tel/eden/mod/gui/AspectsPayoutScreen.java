package tel.eden.mod.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.PendingEntry;
import tel.eden.mod.reward.GuildRewards;

/**
 * Table of guild members owed aspects, with per-row selection and a batch pay-out.
 *
 * <p>Rows are supplied by the backend (already sorted by amount owed, descending) and
 * annotated locally with each member's guild rank. Members who haven't been in the
 * guild a week can't receive rewards, so their rows are greyed out and unselectable.
 *
 * <p>Columns: selection checkbox, player name, guild rank, aspects owed.
 */
public final class AspectsPayoutScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 420;
	private static final int BASE_PANEL_HEIGHT = 300;
	private static final int ROW_HEIGHT = 28;
	private static final int VISIBLE_ROWS = 7;
	private static final int LIST_TOP = 36;
	private static final int LIST_BOTTOM = LIST_TOP + (ROW_HEIGHT * VISIBLE_ROWS);
	private static final long WEEK_MS = 604_800_000L;

	private final Screen parent;
	private final EdenModClient mod;

	private final List<PendingEntry> rows = new ArrayList<>();
	private final Set<String> selected = new LinkedHashSet<>();

	private EdenPanelLayout layout;
	private Button payOutButton;
	private int scrollOffset;
	private boolean draggingScrollbar;
	// Generation of the reply currently on screen; -1 means we haven't taken a
	// snapshot yet, which is how "still loading" is told from "replied, but empty".
	private int seenGeneration = -1;
	// The "everything ticked" default applies to the first list that actually has rows,
	// not to the empty placeholder we snapshot before the reply lands.
	private boolean defaultsApplied;

	public AspectsPayoutScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Aspect Payouts"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		super.init();
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		int quickY = 240;
		this.addRenderableWidget(Button.builder(Component.literal("Select All"), b -> selectAll()).bounds(layout.x(15), layout.y(quickY), layout.w(124), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("Select Non-Chief"), b -> selectNonChief()).bounds(layout.x(148), layout.y(quickY), layout.w(124), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("Deselect All"), b -> selected.clear()).bounds(layout.x(281), layout.y(quickY), layout.w(124), layout.h(20)).build());

		payOutButton = this.addRenderableWidget(Button.builder(Component.literal("Pay Out"), b -> payOut()).bounds(layout.x(15), layout.y(266), layout.w(190), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreen(parent)).bounds(layout.x(215), layout.y(266), layout.w(190), layout.h(20)).build());

		requestPending();
		refreshSnapshot();
	}

	private void requestPending() {
		if (mod.socket() != null) {
			mod.socket().sendAspectsPendingRequest();
		}
		mod.guildRewards().ensureFresh(EdenModClient.instance().playerName());
	}

	/** Adopt the latest backend reply, keeping selections for names that survived it. */
	private void refreshSnapshot() {
		List<PendingEntry> latest = mod.knownPendingAspects();
		seenGeneration = mod.pendingAspectsGeneration();

		Set<String> stillListed = new LinkedHashSet<>();
		for (PendingEntry entry : latest) {
			stillListed.add(entry.name());
		}
		if (!defaultsApplied && !latest.isEmpty()) {
			// Paying everyone is the common case, so start with everything eligible ticked.
			selected.clear();
			for (PendingEntry entry : latest) {
				if (isEligible(entry.name())) {
					selected.add(entry.name());
				}
			}
			defaultsApplied = true;
		} else {
			selected.retainAll(stillListed);
		}

		rows.clear();
		rows.addAll(latest);
		clampScroll();
	}

	private void clampScroll() {
		scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - VISIBLE_ROWS)));
	}

	@Override
	public void tick() {
		super.tick();
		if (seenGeneration != mod.pendingAspectsGeneration()) {
			refreshSnapshot();
		}
		if (payOutButton != null) {
			payOutButton.active = !selected.isEmpty() && mod.guildRewards().isChief() && !mod.guildRewards().isGiftInProgress();
		}
	}

	/**
	 * Whether a row can be selected. The guild roster loads asynchronously, so an
	 * unknown member is treated as selectable rather than locking every row until the
	 * API answers; only a member we positively know joined too recently is blocked.
	 * A genuinely non-member selection is caught by the batch's own validation.
	 */
	private boolean isEligible(String name) {
		return !isTooNew(name);
	}

	private boolean isTooNew(String name) {
		Long joined = mod.guildRewards().memberJoined(name);
		return joined != null && System.currentTimeMillis() - joined < WEEK_MS;
	}

	private String rankOf(String name) {
		String rank = mod.guildRewards().memberRank(name);
		return rank == null ? "—" : rank;
	}

	private boolean isChiefRank(String name) {
		String rank = mod.guildRewards().memberRank(name);
		if (rank == null) {
			return false;
		}
		String lower = rank.toLowerCase(Locale.ROOT);
		return lower.equals("chief") || lower.equals("owner");
	}

	private int selectedAspects() {
		int total = 0;
		for (PendingEntry entry : rows) {
			if (selected.contains(entry.name())) {
				total += entry.aspects();
			}
		}
		return total;
	}

	// -- quick actions ----------------------------------------------------------

	private void selectAll() {
		selected.clear();
		for (PendingEntry entry : rows) {
			if (isEligible(entry.name())) {
				selected.add(entry.name());
			}
		}
	}

	private void selectNonChief() {
		selected.clear();
		for (PendingEntry entry : rows) {
			if (isEligible(entry.name()) && !isChiefRank(entry.name())) {
				selected.add(entry.name());
			}
		}
	}

	private void payOut() {
		if (selected.isEmpty()) {
			return;
		}
		if (!mod.guildRewards().isChief()) {
			sendChat("Only guild Chiefs can pay out rewards.");
			return;
		}
		List<GuildRewards.PayoutTarget> targets = new ArrayList<>();
		for (PendingEntry entry : rows) {
			if (selected.contains(entry.name()) && entry.aspects() > 0) {
				targets.add(new GuildRewards.PayoutTarget(entry.name(), entry.aspects()));
			}
		}
		if (targets.isEmpty()) {
			return;
		}
		// The guild-manage menu needs the screen, and progress is reported in chat.
		this.minecraft.setScreen(null);
		mod.guildRewards().payoutAspects(targets);
	}

	private void sendChat(String message) {
		if (this.minecraft.player != null) {
			this.minecraft.player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), false);
		}
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

		g.drawCenteredString(this.font, "Pending Aspects", layout.centerX(), layout.y(12), 0xFFFFFFFF);

		int listLeft = layout.x(15);
		int listTop = layout.y(LIST_TOP);
		int listWidth = layout.w(390);
		int listHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		g.fill(listLeft, listTop, listLeft + listWidth, listTop + listHeight, 0x22000000);

		if (rows.isEmpty()) {
			g.drawCenteredString(this.font, emptyMessage(), layout.centerX(), layout.y(122), emptyColor());
		} else {
			renderRows(g, scaledMouseX, scaledMouseY);
		}

		layout.drawScrollbar(g, layout.x(393), listTop, layout.w(8), listHeight, VISIBLE_ROWS, rows.size(), scrollOffset);
		g.drawString(this.font, footerText(), layout.x(15), layout.y(228), 0xFFCCCCCC);
		popReferencePose(g);
	}

	private void renderRows(GuiGraphics g, double mouseX, double mouseY) {
		for (int visible = 0; visible < VISIBLE_ROWS; visible++) {
			int index = scrollOffset + visible;
			if (index >= rows.size()) {
				break;
			}
			PendingEntry entry = rows.get(index);
			boolean eligible = isEligible(entry.name());
			boolean checked = selected.contains(entry.name());

			int rowTop = layout.y(38 + visible * ROW_HEIGHT);
			int rowBottom = rowTop + layout.h(24);
			boolean hovered = eligible && mouseX >= layout.x(17) && mouseX <= layout.x(391) && mouseY >= rowTop && mouseY <= rowBottom;
			g.fill(layout.x(17), rowTop, layout.x(391), rowBottom, hovered ? 0x66383838 : 0x44282828);

			drawCheckbox(g, layout.x(23), rowTop + layout.h(6), layout.w(12), checked, eligible);

			int textY = rowTop + layout.h(8);
			int nameColor = eligible ? 0xFFFFFFFF : 0xFF777777;
			int rankColor = eligible ? 0xFFAAAAAA : 0xFF666666;
			g.drawString(this.font, trimToWidth(entry.name(), layout.w(140)), layout.x(45), textY, nameColor);

			String rank = isTooNew(entry.name()) ? rankOf(entry.name()) + " (<1 week)" : rankOf(entry.name());
			g.drawString(this.font, trimToWidth(rank, layout.w(120)), layout.x(196), textY, rankColor);

			String owed = entry.aspects() + " aspects";
			g.drawString(this.font, owed, layout.x(385) - this.font.width(owed), textY, eligible ? 0xFFFFD24A : 0xFF7A6A32);
		}
	}

	private void drawCheckbox(GuiGraphics g, int x, int y, int size, boolean checked, boolean enabled) {
		int border = enabled ? 0xFFD8D8D8 : 0xFF666666;
		g.fill(x, y, x + size, y + size, 0xFF1E1E1E);
		g.fill(x, y, x + size, y + 1, border);
		g.fill(x, y + size - 1, x + size, y + size, border);
		g.fill(x, y, x + 1, y + size, border);
		g.fill(x + size - 1, y, x + size, y + size, border);
		if (checked) {
			g.fill(x + 3, y + 3, x + size - 3, y + size - 3, enabled ? 0xFF55DD55 : 0xFF3A6A3A);
		}
	}

	private String emptyMessage() {
		if (mod.pendingAspectsError() != null) {
			return mod.pendingAspectsError();
		}
		if (mod.socket() == null) {
			return "Not connected to the bridge";
		}
		if (mod.pendingAspectsGeneration() == 0) {
			return "Loading pending aspects...";
		}
		return "No members have pending aspects.";
	}

	private int emptyColor() {
		return mod.pendingAspectsError() != null || mod.socket() == null ? 0xFFFF5555 : 0xFFAAAAAA;
	}

	private String footerText() {
		if (mod.guildRewards().isGiftInProgress()) {
			return "Payout in progress — see chat";
		}
		return "Selected: " + selected.size() + " players — " + selectedAspects() + " aspects";
	}

	// -- input ------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		MouseButtonEvent scaled = rescale(event);
		double mouseX = scaled.x();
		double mouseY = scaled.y();

		if (scaled.button() == 0) {
			if (isOverScrollbar(mouseX, mouseY)) {
				draggingScrollbar = true;
				updateScrollFromMouse(mouseY);
				return true;
			}
			int row = rowAt(mouseX, mouseY);
			if (row >= 0) {
				String name = rows.get(row).name();
				if (isEligible(name)) {
					if (!selected.remove(name)) {
						selected.add(name);
					}
				}
				return true;
			}
		}

		return super.mouseClicked(scaled, bl);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingScrollbar = false;
		return super.mouseReleased(rescale(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double d, double e) {
		MouseButtonEvent scaled = rescale(event);
		if (draggingScrollbar) {
			updateScrollFromMouse(scaled.y());
			return true;
		}
		return super.mouseDragged(scaled, d / uiScale, e / uiScale);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double d, double e) {
		double scaledMouseX = mouseX / uiScale;
		double scaledMouseY = mouseY / uiScale;
		if (!isOverList(scaledMouseX, scaledMouseY) && !isOverScrollbar(scaledMouseX, scaledMouseY)) {
			return super.mouseScrolled(scaledMouseX, scaledMouseY, d, e);
		}
		int maxOffset = Math.max(0, rows.size() - VISIBLE_ROWS);
		if (maxOffset == 0) {
			return true;
		}
		scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(e)));
		return true;
	}

	private boolean isOverList(double mouseX, double mouseY) {
		return mouseX >= layout.x(15) && mouseX <= layout.x(401) && mouseY >= layout.y(LIST_TOP) && mouseY <= layout.y(LIST_BOTTOM);
	}

	private boolean isOverScrollbar(double mouseX, double mouseY) {
		return mouseX >= layout.x(393) && mouseX <= layout.x(401) && mouseY >= layout.y(LIST_TOP) && mouseY <= layout.y(LIST_BOTTOM);
	}

	private int rowAt(double mouseX, double mouseY) {
		if (!isOverList(mouseX, mouseY)) {
			return -1;
		}
		int row = ((int) mouseY - layout.y(LIST_TOP)) / layout.h(ROW_HEIGHT);
		int index = scrollOffset + row;
		return index >= 0 && index < rows.size() ? index : -1;
	}

	private void updateScrollFromMouse(double mouseY) {
		int maxOffset = Math.max(0, rows.size() - VISIBLE_ROWS);
		if (maxOffset == 0) {
			scrollOffset = 0;
			return;
		}
		int trackTop = layout.y(LIST_TOP);
		int trackHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		int thumbHeight = Math.max(layout.h(18), Math.round(trackHeight * (VISIBLE_ROWS / (float) rows.size())));
		double relative = mouseY - trackTop - (thumbHeight / 2.0);
		double range = Math.max(1, trackHeight - thumbHeight);
		double percent = Math.max(0.0, Math.min(1.0, relative / range));
		scrollOffset = (int) Math.round(percent * maxOffset);
	}

	private String trimToWidth(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("..."))) + "...";
	}
}
