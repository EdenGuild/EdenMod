package tel.eden.mod.gui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.PendingEntry;
import tel.eden.mod.reward.GuildRewards;

/**
 * Table of guild members owed a reward, with per-row selection and a batch pay-out.
 * A top-left toggle switches which reward the whole screen is about — aspects or
 * emeralds — mirroring the same table, quick actions, and pay-out flow for both, just
 * pointed at a different backend request and {@link GuildRewards} method.
 *
 * <p>Rows are supplied by the backend (already sorted by amount owed, descending) and
 * annotated locally with each member's guild rank. Members who haven't been in the
 * guild a week can't receive rewards, so their rows are greyed out and unselectable.
 * Columns: selection checkbox, player name, guild rank, amount owed. A rank-range
 * filter (mirroring {@link AspectGiveawayScreen}'s own) narrows which rows are shown,
 * scrolled, and affected by the quick-select actions — useful on either table, but
 * especially the emerald one, which tends to list far more members at once.
 *
 * <p>The "Giveaways →" button only appears in Aspects mode: aspects are player-bound
 * (each member's own pending balance), which is why they get the separate
 * filter-driven {@link AspectGiveawayScreen} — a bulk distribution that doesn't map to
 * any one member's specific pending amount. Emeralds have no equivalent: surplus
 * emeralds are simply dumped to one player (see {@link GuildRewards#dumpEmeralds}) who
 * redistributes them manually.
 */
public final class PayoutScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 420;
	// Taller than the bare minimum on purpose — EdenPanelLayout.centered() already
	// scales this down (to a 0.55x floor) on a window too small to fit it, so a
	// larger base height just means more rows are visible on anyone with the room,
	// rather than the panel being capped at a fixed small size for everyone.
	private static final int BASE_PANEL_HEIGHT = 456;
	// Pending-balance status row, between the quick actions and Pay Out.
	private static final int OPTION_ROW_Y = 400;
	private static final int ROW_HEIGHT = 28;
	private static final int VISIBLE_ROWS = 11;
	private static final int LIST_TOP = 58;
	private static final int LIST_BOTTOM = LIST_TOP + (ROW_HEIGHT * VISIBLE_ROWS);
	private static final long WEEK_MS = 604_800_000L;
	// Liquid Emeralds: the denomination Chiefs think and gift in bulk in (1 LE =
	// 4096 real emeralds — see eden.display_unit's backend docstring). Display-only:
	// PendingEntry/PayoutTarget stay in real emeralds throughout, matching what
	// GuildRewards' gift flow and the backend's per-member pending balance both use.
	private static final int EMERALDS_PER_LE = 4096;
	// Mirrors AspectGiveawayScreen's own rank range filter — especially useful here
	// since the payout table (particularly emeralds) can list far more members than
	// fit on screen at once.
	private static final List<String> RANK_ORDER = List.of("Recruit", "Recruiter", "Captain", "Strategist", "Chief", "Owner");

	private final Screen parent;
	private final EdenModClient mod;

	private final List<PendingEntry> rows = new ArrayList<>();
	private final Set<String> selected = new LinkedHashSet<>();

	private EdenPanelLayout layout;
	private GuildRewards.RewardType mode = GuildRewards.RewardType.ASPECT;
	private Button payOutButton;
	private CycleButton<String> fromRankButton;
	private CycleButton<String> toRankButton;
	// Inclusive rank range shown/selectable — full range by default so the filter
	// never hides anything until a Chief deliberately narrows it.
	private String fromRank = RANK_ORDER.getFirst();
	private String toRank = RANK_ORDER.getLast();
	private int scrollOffset;
	private boolean draggingScrollbar;
	// Generation of the reply currently on screen; -1 means we haven't taken a
	// snapshot yet, which is how "still loading" is told from "replied, but empty".
	// Reset (along with rows/selected below) on every mode switch, since aspects and
	// emeralds are tracked as two entirely independent backend replies.
	private int seenGeneration = -1;
	// The "everything ticked" default applies to the first list that actually has rows,
	// not to the empty placeholder we snapshot before the reply lands.
	private boolean defaultsApplied;

	public PayoutScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Payout"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		super.init();
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);
		applyWidgetsForMode();
	}

	/** Rebuilds the widget set for the current {@link #mode} — called on init and on toggle. */
	private void applyWidgetsForMode() {
		this.clearWidgets();
		// A mode switch starts this screen over as if freshly opened for that reward —
		// aspects and emeralds are unrelated backend replies with their own generation
		// counters, so there is nothing meaningful to carry over between them.
		rows.clear();
		selected.clear();
		seenGeneration = -1;
		defaultsApplied = false;

		this.addRenderableWidget(CycleButton.<GuildRewards.RewardType>builder(type -> Component.literal(type == GuildRewards.RewardType.ASPECT ? "Aspects" : "Emeralds"), mode).withValues(List.of(GuildRewards.RewardType.ASPECT, GuildRewards.RewardType.EMERALD)).create(layout.x(15), layout.y(8), layout.w(90), layout.h(16), Component.literal("Reward"), (b, value) -> {
			mode = value;
			applyWidgetsForMode();
		}));

		fromRankButton = this.addRenderableWidget(CycleButton.<String>builder(Component::literal, fromRank).withValues(RANK_ORDER).create(layout.x(15), layout.y(30), layout.w(185), layout.h(20), Component.literal("From rank"), (b, value) -> {
			fromRank = value;
			clampScroll();
		}));
		toRankButton = this.addRenderableWidget(CycleButton.<String>builder(Component::literal, toRank).withValues(RANK_ORDER).create(layout.x(220), layout.y(30), layout.w(185), layout.h(20), Component.literal("To rank"), (b, value) -> {
			toRank = value;
			clampScroll();
		}));

		int quickY = 374;
		this.addRenderableWidget(Button.builder(Component.literal("Select All"), b -> selectAll()).bounds(layout.x(15), layout.y(quickY), layout.w(124), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("Select Non-Chief"), b -> selectNonChief()).bounds(layout.x(148), layout.y(quickY), layout.w(124), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("Deselect All"), b -> selected.clear()).bounds(layout.x(281), layout.y(quickY), layout.w(124), layout.h(20)).build());
		payOutButton = this.addRenderableWidget(Button.builder(Component.literal("Pay Out"), b -> payOut()).bounds(layout.x(15), layout.y(422), layout.w(190), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.minecraft.setScreen(parent)).bounds(layout.x(215), layout.y(422), layout.w(190), layout.h(20)).build());
		if (mode == GuildRewards.RewardType.ASPECT) {
			this.addRenderableWidget(Button.builder(Component.literal("Giveaways →"), b -> this.minecraft.setScreen(new AspectGiveawayScreen(parent, mod))).bounds(layout.x(315), layout.y(8), layout.w(90), layout.h(16)).build());
		}

		requestPending();
		refreshSnapshot();
	}

	private void requestPending() {
		if (mod.socket() != null) {
			if (mode == GuildRewards.RewardType.ASPECT) {
				mod.socket().sendAspectsPendingRequest();
			} else {
				mod.socket().sendEmeraldsPendingRequest();
			}
		}
		mod.guildRewards().ensureFresh(EdenModClient.instance().playerName());
	}

	private int currentGeneration() {
		return mode == GuildRewards.RewardType.ASPECT ? mod.pendingAspectsGeneration() : mod.pendingEmeraldsGeneration();
	}

	private List<PendingEntry> currentKnownPending() {
		return mode == GuildRewards.RewardType.ASPECT ? mod.knownPendingAspects() : mod.knownPendingEmeralds();
	}

	private String currentError() {
		return mode == GuildRewards.RewardType.ASPECT ? mod.pendingAspectsError() : mod.pendingEmeraldsError();
	}

	/** Adopt the latest backend reply, keeping selections for names that survived it. */
	private void refreshSnapshot() {
		// Generation first, list second — the opposite of the write order. Sampling
		// the generation after the list could pair the previous reply's rows with the
		// new reply's generation, and tick() would then see nothing left to pick up.
		// This way the worst case is snapshotting the same reply twice.
		seenGeneration = currentGeneration();
		List<PendingEntry> latest = currentKnownPending();

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
		scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, visibleRows().size() - VISIBLE_ROWS)));
	}

	@Override
	public void tick() {
		super.tick();
		if (seenGeneration != currentGeneration()) {
			refreshSnapshot();
		}
		if (payOutButton != null) {
			payOutButton.active = !selected.isEmpty() && mod.guildRewards().isChief() && !mod.guildRewards().isGiftInProgress();
		}
	}

	/**
	 * Whether a row can be selected. The guild member list loads asynchronously, so an
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

	/**
	 * Whether {@code name}'s rank falls within [{@link #fromRank}, {@link #toRank}].
	 * An unknown or unrecognized rank fails open (shown, not hidden) — same
	 * philosophy as {@link #isEligible}: don't let incomplete data silently hide a
	 * row rather than just leaving it unfiltered.
	 */
	private boolean rankInRange(String name) {
		String rank = mod.guildRewards().memberRank(name);
		int index = rank == null ? -1 : RANK_ORDER.indexOf(rank);
		if (index < 0) {
			return true;
		}
		return index >= RANK_ORDER.indexOf(fromRank) && index <= RANK_ORDER.indexOf(toRank);
	}

	/** Rows within the current rank filter — everything the table actually shows/scrolls. */
	private List<PendingEntry> visibleRows() {
		List<PendingEntry> visible = new ArrayList<>();
		for (PendingEntry entry : rows) {
			if (rankInRange(entry.name())) {
				visible.add(entry);
			}
		}
		return visible;
	}

	private int selectedAmount() {
		int total = 0;
		for (PendingEntry entry : rows) {
			if (selected.contains(entry.name())) {
				total += entry.amount();
			}
		}
		return total;
	}

	/**
	 * Human-readable amount for the current mode: a bare aspect count, or emeralds
	 * shown as Liquid Emeralds (with up to 2 decimals when not a whole LE) — real
	 * emerald counts get unwieldy fast (a single raid already credits over 1000).
	 */
	private String formatAmount(int realAmount) {
		if (mode == GuildRewards.RewardType.ASPECT) {
			return realAmount + " aspects";
		}
		double le = realAmount / (double) EMERALDS_PER_LE;
		String formatted = le == Math.rint(le) ? String.valueOf((long) le) : String.format(Locale.ROOT, "%.2f", le);
		return formatted + " LE";
	}

	// -- quick actions ----------------------------------------------------------

	private void selectAll() {
		selected.clear();
		for (PendingEntry entry : visibleRows()) {
			if (isEligible(entry.name())) {
				selected.add(entry.name());
			}
		}
	}

	private void selectNonChief() {
		selected.clear();
		for (PendingEntry entry : visibleRows()) {
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
			if (selected.contains(entry.name()) && entry.amount() > 0) {
				targets.add(new GuildRewards.PayoutTarget(entry.name(), entry.amount()));
			}
		}
		if (targets.isEmpty()) {
			return;
		}
		String title = mode == GuildRewards.RewardType.ASPECT ? "Aspect Payout" : "Emerald Payout";
		GiftProgressScreen progress = new GiftProgressScreen(title, mod.guildRewards());
		mod.guildRewards().setProgressListener(progress);
		this.minecraft.setScreen(progress);
		if (mode == GuildRewards.RewardType.ASPECT) {
			mod.guildRewards().payoutAspects(targets);
		} else {
			mod.guildRewards().payoutEmeralds(targets);
		}
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

		String rewardWord = mode == GuildRewards.RewardType.ASPECT ? "Aspects" : "Emeralds";
		g.drawCenteredString(this.font, "Pending " + rewardWord, layout.centerX(), layout.y(12), 0xFFFFFFFF);

		int listLeft = layout.x(15);
		int listTop = layout.y(LIST_TOP);
		int listWidth = layout.w(390);
		int listHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		g.fill(listLeft, listTop, listLeft + listWidth, listTop + listHeight, 0x22000000);

		List<PendingEntry> visible = visibleRows();
		if (visible.isEmpty()) {
			String message = rows.isEmpty() ? emptyMessage() : "No members in this rank range.";
			int color = rows.isEmpty() ? emptyColor() : 0xFFAAAAAA;
			g.drawCenteredString(this.font, message, layout.centerX(), layout.y(212), color);
		} else {
			renderRows(g, scaledMouseX, scaledMouseY, visible);
		}

		layout.drawScrollbar(g, layout.x(393), listTop, layout.w(8), listHeight, VISIBLE_ROWS, visible.size(), scrollOffset);
		g.drawString(this.font, footerText(), layout.x(15), layout.y(LIST_BOTTOM - 4), 0xFFCCCCCC);
		g.drawString(this.font, "Pending totals update from confirmed gifts", layout.x(15), layout.y(OPTION_ROW_Y + 2), 0xFFAAAAAA);
		popReferencePose(g);
	}

	private void renderRows(GuiGraphics g, double mouseX, double mouseY, List<PendingEntry> visible) {
		for (int row = 0; row < VISIBLE_ROWS; row++) {
			int index = scrollOffset + row;
			if (index >= visible.size()) {
				break;
			}
			PendingEntry entry = visible.get(index);
			// One member-list lookup each per row per frame; both were previously repeated
			// two or three times on the way through this loop.
			boolean tooNew = isTooNew(entry.name());
			String rank = rankOf(entry.name());
			boolean eligible = !tooNew;
			boolean checked = selected.contains(entry.name());

			int rowTop = layout.y(LIST_TOP + 2 + row * ROW_HEIGHT);
			int rowBottom = rowTop + layout.h(24);
			boolean hovered = eligible && mouseX >= layout.x(17) && mouseX <= layout.x(391) && mouseY >= rowTop && mouseY <= rowBottom;
			g.fill(layout.x(17), rowTop, layout.x(391), rowBottom, hovered ? 0x66383838 : 0x44282828);

			drawCheckbox(g, layout.x(23), rowTop + layout.h(6), layout.w(12), checked, eligible);

			int textY = rowTop + layout.h(8);
			int nameColor = eligible ? 0xFFFFFFFF : 0xFF777777;
			int rankColor = eligible ? 0xFFAAAAAA : 0xFF666666;
			g.drawString(this.font, trimToWidth(entry.name(), layout.w(140)), layout.x(45), textY, nameColor);

			String rankLabel = tooNew ? rank + " (<1 week)" : rank;
			g.drawString(this.font, trimToWidth(rankLabel, layout.w(120)), layout.x(196), textY, rankColor);

			String owed = formatAmount(entry.amount());
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
		String error = currentError();
		if (error != null) {
			return error;
		}
		if (mod.socket() == null) {
			return "Not connected to the bridge";
		}
		if (currentGeneration() == 0) {
			return "Loading pending " + (mode == GuildRewards.RewardType.ASPECT ? "aspects" : "emeralds") + "...";
		}
		return "No members have pending " + (mode == GuildRewards.RewardType.ASPECT ? "aspects" : "emeralds") + ".";
	}

	private int emptyColor() {
		return currentError() != null || mod.socket() == null ? 0xFFFF5555 : 0xFFAAAAAA;
	}

	private String footerText() {
		if (mod.guildRewards().isGiftInProgress()) {
			return "Payout in progress — see chat";
		}
		return "Selected: " + selected.size() + " players — " + formatAmount(selectedAmount());
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
				String name = visibleRows().get(row).name();
				if (isEligible(name)) {
					if (!selected.remove(name)) {
						selected.add(name);
					}
				}
				return true;
			}
		}

		// Right-click steps a rank picker backwards, mirroring AspectGiveawayScreen —
		// otherwise reaching an earlier rank means cycling almost all the way around.
		if (scaled.button() == 1) {
			if (isOverWidget(fromRankButton, mouseX, mouseY)) {
				cycleRankBackward(fromRankButton, value -> fromRank = value);
				return true;
			}
			if (isOverWidget(toRankButton, mouseX, mouseY)) {
				cycleRankBackward(toRankButton, value -> toRank = value);
				return true;
			}
		}

		return super.mouseClicked(scaled, bl);
	}

	private static boolean isOverWidget(net.minecraft.client.gui.components.AbstractWidget widget, double mouseX, double mouseY) {
		return mouseX >= widget.getX() && mouseX < widget.getX() + widget.getWidth() && mouseY >= widget.getY() && mouseY < widget.getY() + widget.getHeight();
	}

	private void cycleRankBackward(CycleButton<String> button, java.util.function.Consumer<String> assign) {
		int idx = RANK_ORDER.indexOf(button.getValue());
		String value = RANK_ORDER.get(idx <= 0 ? RANK_ORDER.size() - 1 : idx - 1);
		assign.accept(value);
		button.setValue(value);
		button.playDownSound(this.minecraft.getSoundManager());
		clampScroll();
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
		int maxOffset = Math.max(0, visibleRows().size() - VISIBLE_ROWS);
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
		return index >= 0 && index < visibleRows().size() ? index : -1;
	}

	private void updateScrollFromMouse(double mouseY) {
		int visibleCount = visibleRows().size();
		int maxOffset = Math.max(0, visibleCount - VISIBLE_ROWS);
		if (maxOffset == 0) {
			scrollOffset = 0;
			return;
		}
		int trackTop = layout.y(LIST_TOP);
		int trackHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		int thumbHeight = Math.max(layout.h(18), Math.round(trackHeight * (VISIBLE_ROWS / (float) visibleCount)));
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
