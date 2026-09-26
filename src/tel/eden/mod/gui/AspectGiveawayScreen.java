package tel.eden.mod.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.GiveawayCandidate;
import tel.eden.mod.reward.GuildRewards;

/**
 * Filter-driven bulk aspect giveaway: flat-gifts a fixed amount of aspects to every
 * current member matching a recency / XP / rank filter, as a way to drain aspect
 * surplus from members who don't claim their own (aspects-blocked members are always
 * excluded). Unlike {@link PayoutScreen}, targets are computed from filters
 * rather than picked row-by-row, so a "Review Gift" step snapshots exactly who will
 * be gifted, the total, and the guild's remaining stock before the Chief commits.
 */
public final class AspectGiveawayScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 420;
	private static final int BASE_PANEL_HEIGHT = 360;
	private static final int ROW_HEIGHT = 24;
	private static final int VISIBLE_ROWS = 7;
	private static final int LIST_TOP = 110;
	private static final int LIST_BOTTOM = LIST_TOP + (ROW_HEIGHT * VISIBLE_ROWS);

	private static final List<String> RANK_ORDER = List.of("Recruit", "Recruiter", "Captain", "Strategist", "Chief", "Owner");
	private static final String DEFAULT_HOURS = "6";
	private static final String DEFAULT_BILLION_XP = "10";
	private static final String DEFAULT_AMOUNT = "1";
	private static final String DEFAULT_FROM_RANK = "Recruiter";
	private static final String DEFAULT_TO_RANK = "Owner";

	private final Screen parent;
	private final EdenModClient mod;

	private List<GiveawayCandidate> allCandidates = List.of();
	private int seenGeneration = -1;
	private int scrollOffset;
	private boolean draggingScrollbar;

	private String fromRank = DEFAULT_FROM_RANK;
	private String toRank = DEFAULT_TO_RANK;

	private boolean confirming;
	private List<GiveawayCandidate> reviewTargets = List.of();
	private int reviewAmountEach;
	private int reviewTotal;
	private int reviewStorage;

	private EdenPanelLayout layout;
	private EditBox hoursField;
	private EditBox xpField;
	private EditBox amountField;
	private CycleButton<String> fromRankButton;
	private CycleButton<String> toRankButton;
	private Button reviewButton;
	private Button toggleButton;
	private Button confirmButton;
	private Button backButton;

	public AspectGiveawayScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Aspect Giveaways"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		super.init();
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		toggleButton = Button.builder(Component.literal("← Payouts"), b -> this.minecraft.setScreen(new PayoutScreen(parent, mod))).bounds(layout.x(315), layout.y(8), layout.w(90), layout.h(16)).build();

		hoursField = new EditBox(this.font, layout.x(15), layout.y(40), layout.w(120), layout.h(20), Component.literal("Max hours since seen"));
		hoursField.setValue(DEFAULT_HOURS);
		hoursField.setMaxLength(10);

		xpField = new EditBox(this.font, layout.x(150), layout.y(40), layout.w(120), layout.h(20), Component.literal("Billion XP contributed"));
		xpField.setValue(DEFAULT_BILLION_XP);
		xpField.setMaxLength(10);

		amountField = new EditBox(this.font, layout.x(285), layout.y(40), layout.w(120), layout.h(20), Component.literal("Aspects each"));
		amountField.setValue(DEFAULT_AMOUNT);
		amountField.setMaxLength(6);

		fromRankButton = CycleButton.<String>builder(rank -> Component.literal(rank), fromRank).withValues(RANK_ORDER).create(layout.x(15), layout.y(78), layout.w(185), layout.h(20), Component.literal("From rank"), (b, value) -> fromRank = value);
		toRankButton = CycleButton.<String>builder(rank -> Component.literal(rank), toRank).withValues(RANK_ORDER).create(layout.x(220), layout.y(78), layout.w(185), layout.h(20), Component.literal("To rank"), (b, value) -> toRank = value);

		reviewButton = Button.builder(Component.literal("Review Gift"), b -> onReview()).bounds(layout.x(15), layout.y(322), layout.w(390), layout.h(20)).build();
		confirmButton = Button.builder(Component.literal("Confirm & Gift"), b -> onConfirm()).bounds(layout.x(15), layout.y(322), layout.w(190), layout.h(20)).build();
		backButton = Button.builder(Component.literal("Back"), b -> {
			confirming = false;
			scrollOffset = 0;
			applyWidgetsForState();
		}).bounds(layout.x(215), layout.y(322), layout.w(190), layout.h(20)).build();

		applyWidgetsForState();
		requestMembers();
	}

	private void applyWidgetsForState() {
		this.clearWidgets();
		this.addRenderableWidget(toggleButton);
		if (confirming) {
			this.addRenderableWidget(confirmButton);
			this.addRenderableWidget(backButton);
		} else {
			this.addRenderableWidget(hoursField);
			this.addRenderableWidget(xpField);
			this.addRenderableWidget(amountField);
			this.addRenderableWidget(fromRankButton);
			this.addRenderableWidget(toRankButton);
			this.addRenderableWidget(reviewButton);
		}
	}

	private void requestMembers() {
		if (mod.socket() != null) {
			mod.socket().sendAspectGiveawayRequest();
		}
		// Join dates for the too-new check below come from GuildRewards' own
		// Wynncraft-API member list (memberJoined), not the bridge reply.
		mod.guildRewards().ensureFresh(EdenModClient.playerName());
	}

	@Override
	public void tick() {
		super.tick();
		if (seenGeneration != mod.giveawayGeneration()) {
			seenGeneration = mod.giveawayGeneration();
			allCandidates = mod.knownGiveawayCandidates();
		}
		if (!confirming) {
			clampScroll(currentMatches().size());
			if (reviewButton != null) {
				reviewButton.active = !currentMatches().isEmpty();
			}
		} else if (confirmButton != null) {
			confirmButton.active = !reviewTargets.isEmpty() && reviewTotal <= reviewStorage && mod.guildRewards().isChief() && !mod.guildRewards().isGiftInProgress();
		}
	}

	private void clampScroll(int rowCount) {
		scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rowCount - VISIBLE_ROWS)));
	}

	// -- filtering ----------------------------------------------------------------

	/**
	 * Hours from the field: negative means "no recency filter" (an empty field parses
	 * to this), 0 means "currently online" ({@link #ONLINE_WINDOW_MS}), and a garbled
	 * (non-empty, unparsable) value falls back to the 6-hour default rather than
	 * silently turning the filter off.
	 */
	private double hoursSinceSeenValue() {
		String raw = hoursField.getValue().trim();
		if (raw.isEmpty()) {
			return -1.0;
		}
		try {
			return Double.parseDouble(raw);
		} catch (NumberFormatException e) {
			return 6.0;
		}
	}

	/** Blank means "no XP filter" (threshold 0, which already excludes nobody). */
	private long minContributedXp() {
		String raw = xpField.getValue().trim();
		if (raw.isEmpty()) {
			return 0L;
		}
		try {
			double billions = Double.parseDouble(raw);
			return Math.round(Math.max(0, billions) * 1_000_000_000.0);
		} catch (NumberFormatException e) {
			return 10_000_000_000L;
		}
	}

	private int amountEach() {
		try {
			return Math.max(1, Integer.parseInt(amountField.getValue().trim()));
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static int rankIndex(String rank) {
		if (rank == null) {
			return -1;
		}
		for (int i = 0; i < RANK_ORDER.size(); i++) {
			if (RANK_ORDER.get(i).equalsIgnoreCase(rank)) {
				return i;
			}
		}
		return -1;
	}

	// "Currently online" isn't a stored flag — it's inferred from a last-seen window
	// wide enough to survive one missed poll tick (player_last_seen refreshes every
	// ~60s; see poll_online.py), so an online member is never falsely filtered out.
	private static final long ONLINE_WINDOW_MS = 120_000L;
	// Matches GuildRewards/PayoutScreen's own cooldown: the game refuses to
	// gift a member who hasn't been in the guild a week, and GuildRewards.batchRun
	// rejects a batch outright if even one target is inside it — so a too-new member
	// must never reach the match list, not just be flagged there.
	private static final long WEEK_MS = 604_800_000L;

	/**
	 * Whether {@code name}'s join date is positively known and inside the cooldown.
	 * An unknown join date (member list still loading) is treated as eligible rather than
	 * excluded, matching {@code PayoutScreen} — the batch's own validation
	 * catches a genuine too-new member either way.
	 */
	private boolean isTooNew(String name) {
		Long joined = mod.guildRewards().memberJoined(name);
		return joined != null && System.currentTimeMillis() - joined < WEEK_MS;
	}

	/** Every current, non-blocked, week-plus-tenured member matching the filters. */
	private List<GiveawayCandidate> currentMatches() {
		double hours = hoursSinceSeenValue();
		boolean recencyFilterOn = hours >= 0;
		long maxAge = hours == 0 ? ONLINE_WINDOW_MS : (long) (hours * 3_600_000.0);
		long minXp = minContributedXp();
		int fromIdx = rankIndex(fromRank);
		int toIdx = rankIndex(toRank);
		int lo = Math.min(fromIdx < 0 ? 0 : fromIdx, toIdx < 0 ? RANK_ORDER.size() - 1 : toIdx);
		int hi = Math.max(fromIdx < 0 ? 0 : fromIdx, toIdx < 0 ? RANK_ORDER.size() - 1 : toIdx);
		long now = System.currentTimeMillis();
		List<GiveawayCandidate> out = new ArrayList<>();
		for (GiveawayCandidate c : allCandidates) {
			if (c.aspectsBlocked() || isTooNew(c.name())) {
				continue;
			}
			int idx = rankIndex(c.rank());
			if (idx < lo || idx > hi) {
				continue;
			}
			if (c.contributedXp() < minXp) {
				continue;
			}
			if (recencyFilterOn && (c.lastSeenEpochMs() <= 0 || now - c.lastSeenEpochMs() > maxAge)) {
				continue;
			}
			out.add(c);
		}
		return out;
	}

	private List<GiveawayCandidate> displayedRows() {
		return confirming ? reviewTargets : currentMatches();
	}

	// -- actions --------------------------------------------------------------------

	private void onReview() {
		List<GiveawayCandidate> matches = currentMatches();
		if (matches.isEmpty()) {
			return;
		}
		reviewTargets = matches;
		reviewAmountEach = amountEach();
		reviewTotal = reviewTargets.size() * reviewAmountEach;
		reviewStorage = mod.giveawayStorageAspects();
		confirming = true;
		scrollOffset = 0;
		applyWidgetsForState();
	}

	private void onConfirm() {
		if (reviewTargets.isEmpty()) {
			return;
		}
		if (!mod.guildRewards().isChief()) {
			sendChat("Only guild Chiefs can gift rewards.");
			return;
		}
		List<GuildRewards.PayoutTarget> targets = new ArrayList<>();
		for (GiveawayCandidate c : reviewTargets) {
			targets.add(new GuildRewards.PayoutTarget(c.name(), reviewAmountEach));
		}
		GiftProgressScreen progress = new GiftProgressScreen("Aspect Giveaway", mod.guildRewards());
		mod.guildRewards().setProgressListener(progress);
		this.minecraft.setScreen(progress);
		mod.guildRewards().giveaway(targets);
	}

	private void sendChat(String message) {
		if (this.minecraft.player != null) {
			this.minecraft.player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), false);
		}
	}

	// -- formatting -------------------------------------------------------------

	private static String formatXp(long xp) {
		if (xp >= 1_000_000_000L) {
			return String.format(Locale.ROOT, "%.1fB", xp / 1_000_000_000.0);
		}
		if (xp >= 1_000_000L) {
			return String.format(Locale.ROOT, "%.1fM", xp / 1_000_000.0);
		}
		if (xp >= 1_000L) {
			return String.format(Locale.ROOT, "%.1fK", xp / 1_000.0);
		}
		return String.valueOf(xp);
	}

	private static String formatAge(long epochMs) {
		if (epochMs <= 0) {
			return "never";
		}
		long minutes = Math.max(0, System.currentTimeMillis() - epochMs) / 60_000L;
		if (minutes < 60) {
			return minutes + "m ago";
		}
		long hours = minutes / 60;
		if (hours < 48) {
			return hours + "h ago";
		}
		return (hours / 24) + "d ago";
	}

	// -- rendering ----------------------------------------------------------------

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		this.renderMenuBackground(g);
		pushReferencePose(g);
		layout.drawBackground(g);
		layout.drawPanel(g);
		super.render(g, scaledMouseX, scaledMouseY, delta);

		g.drawCenteredString(this.font, confirming ? "Confirm Aspect Giveaway" : "Aspect Giveaways", layout.centerX(), layout.y(12), 0xFFFFFFFF);

		if (!confirming) {
			g.drawString(this.font, "Last seen (h) (0=online)", layout.x(15), layout.y(30), 0xFFA0A0A0);
			g.drawString(this.font, "Bill. XP contr. (blank=any)", layout.x(150), layout.y(30), 0xFFA0A0A0);
			g.drawString(this.font, "Aspects each", layout.x(285), layout.y(30), 0xFFA0A0A0);
			g.drawString(this.font, "From rank", layout.x(15), layout.y(68), 0xFFA0A0A0);
			g.drawString(this.font, "To rank", layout.x(220), layout.y(68), 0xFFA0A0A0);
		}

		int listLeft = layout.x(15);
		int listTop = layout.y(LIST_TOP);
		int listWidth = layout.w(390);
		int listHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		g.fill(listLeft, listTop, listLeft + listWidth, listTop + listHeight, 0x22000000);

		List<GiveawayCandidate> rows = displayedRows();
		if (rows.isEmpty()) {
			g.drawCenteredString(this.font, emptyMessage(), layout.centerX(), layout.y(190), emptyColor());
		} else {
			renderRows(g, rows);
		}

		layout.drawScrollbar(g, layout.x(393), listTop, layout.w(8), listHeight, VISIBLE_ROWS, rows.size(), scrollOffset);

		if (confirming) {
			g.drawString(this.font, "Gifting " + reviewTargets.size() + " members × " + reviewAmountEach + " aspects = " + reviewTotal + " total", layout.x(15), layout.y(286), 0xFFCCCCCC);
			int remaining = reviewStorage - reviewTotal;
			int color = remaining < 0 ? 0xFFFF5555 : 0xFF55FF55;
			g.drawString(this.font, "Guild storage: " + reviewStorage + " → " + remaining + " remaining", layout.x(15), layout.y(302), color);
			if (remaining < 0) {
				g.drawString(this.font, "Not enough aspects in storage — nothing will be gifted.", layout.x(15), layout.y(302 + this.font.lineHeight + 2), 0xFFFF5555);
			}
		} else {
			int total = currentMatches().size() * amountEach();
			g.drawString(this.font, "Matching: " + currentMatches().size() + " members — " + total + " aspects", layout.x(15), layout.y(294), 0xFFCCCCCC);
		}

		popReferencePose(g);
	}

	private void renderRows(GuiGraphics g, List<GiveawayCandidate> rows) {
		for (int visible = 0; visible < VISIBLE_ROWS; visible++) {
			int index = scrollOffset + visible;
			if (index >= rows.size()) {
				break;
			}
			GiveawayCandidate entry = rows.get(index);
			int rowTop = layout.y(LIST_TOP + 2 + visible * ROW_HEIGHT);
			int rowBottom = rowTop + layout.h(ROW_HEIGHT - 4);
			g.fill(layout.x(17), rowTop, layout.x(391), rowBottom, 0x44282828);

			int textY = rowTop + layout.h(6);
			g.drawString(this.font, trimToWidth(entry.name(), layout.w(120)), layout.x(23), textY, 0xFFFFFFFF);
			g.drawString(this.font, trimToWidth(entry.rank(), layout.w(90)), layout.x(160), textY, 0xFFAAAAAA);
			g.drawString(this.font, formatXp(entry.contributedXp()) + " XP", layout.x(260), textY, 0xFFAAAAAA);
			String seen = formatAge(entry.lastSeenEpochMs());
			g.drawString(this.font, seen, layout.x(385) - this.font.width(seen), textY, 0xFF88CC88);
		}
	}

	private String emptyMessage() {
		if (confirming) {
			return "Nothing to gift.";
		}
		if (mod.giveawayError() != null) {
			return mod.giveawayError();
		}
		if (mod.socket() == null) {
			return "Not connected to the bridge";
		}
		if (mod.giveawayGeneration() == 0) {
			return "Loading guild members...";
		}
		return "No members match these filters.";
	}

	private int emptyColor() {
		return mod.giveawayError() != null || mod.socket() == null ? 0xFFFF5555 : 0xFFAAAAAA;
	}

	private String trimToWidth(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("..."))) + "...";
	}

	// -- input --------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		MouseButtonEvent scaled = rescale(event);
		if (scaled.button() == 0 && isOverScrollbar(scaled.x(), scaled.y())) {
			draggingScrollbar = true;
			updateScrollFromMouse(scaled.y());
			return true;
		}
		// Right-click steps a rank picker backwards, mirroring the left-click's forward
		// cycle — otherwise reaching an earlier rank means cycling almost all the way
		// around.
		if (!confirming && scaled.button() == 1) {
			if (isOverWidget(fromRankButton, scaled.x(), scaled.y())) {
				cycleRankBackward(fromRankButton, value -> fromRank = value);
				return true;
			}
			if (isOverWidget(toRankButton, scaled.x(), scaled.y())) {
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
		int maxOffset = Math.max(0, displayedRows().size() - VISIBLE_ROWS);
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

	private void updateScrollFromMouse(double mouseY) {
		List<GiveawayCandidate> rows = displayedRows();
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
}
