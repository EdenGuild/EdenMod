package tel.eden.mod.gui;

import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.CycleLeaderboardData;
import tel.eden.mod.util.PlayerSkinResolver;

/** Promotion Cycle (& Archive) and Lifetime leaderboard screen for full guild members. */
public class PromotionCycleScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 468;
	private static final int BASE_PANEL_HEIGHT = 272;

	private static final int LEFT_COL_X = 12;
	private static final int RIGHT_COL_X = 240;
	private static final int COL_WIDTH = 208;
	private static final int SCROLLBAR_OFFSET = 210;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int VISIBLE_ROWS = 8;
	private static final int ROW_PITCH = 17;
	private static final int ROW_HEIGHT = 15;
	private static final int ZERO_ROW_OVERLAY = 0x44CC2222;
	private static final int ROW_FILL = 0x44282828;

	private static final Identifier[] MEDAL_TEXTURES = {Identifier.fromNamespaceAndPath("edenmod", "textures/gui/cycle/medal_1.png"), Identifier.fromNamespaceAndPath("edenmod", "textures/gui/cycle/medal_2.png"), Identifier.fromNamespaceAndPath("edenmod", "textures/gui/cycle/medal_3.png"),};

	private enum Tab {
		CYCLE, LIFETIME
	}

	private final EdenModClient mod;
	private final String initialCycleCode;
	private EdenPanelLayout layout;

	private Tab activeTab = Tab.CYCLE;
	private boolean archiveOpen;
	private boolean showFinishes;
	private boolean initialRequestSent;
	private boolean lifetimeRequested;
	private String selectedCycleCode;

	private EditBox fromBox;
	private EditBox toBox;
	private String fromDraft = "";
	private String toDraft = "";
	private String validationError = "";

	private Button cycleTabButton;
	private Button lifetimeTabButton;
	private Button archiveButton;
	private Button prevCycleButton;
	private Button nextCycleButton;
	private Button applyIntervalButton;
	private Button resetIntervalButton;
	private Button finishesToggleButton;

	private int raidsScroll;
	private int warsScroll;
	private int draggingPane;
	private int seenCycleGen = -1;
	private int seenLifetimeGen = -1;

	public PromotionCycleScreen(Screen parent, EdenModClient mod) {
		this(parent, mod, null);
	}

	public PromotionCycleScreen(Screen parent, EdenModClient mod, String initialCycleCode) {
		super(Component.literal("Promotion Cycle"), parent);
		this.mod = mod;
		this.initialCycleCode = (initialCycleCode != null && !initialCycleCode.isBlank()) ? initialCycleCode.trim() : null;
		this.selectedCycleCode = this.initialCycleCode;
		this.archiveOpen = this.initialCycleCode != null;
	}

	@Override
	protected void init() {
		super.init();
		layout = centeredLayout(BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		if (fromBox != null) {
			fromDraft = fromBox.getValue();
		}
		if (toBox != null) {
			toDraft = toBox.getValue();
		}

		this.clearWidgets();

		cycleTabButton = Button.builder(Component.literal("Promotion Cycle"), b -> switchTab(Tab.CYCLE)).bounds(layout.x(12), layout.y(24), layout.w(110), layout.h(18)).build();
		lifetimeTabButton = Button.builder(Component.literal("Lifetime"), b -> switchTab(Tab.LIFETIME)).bounds(layout.x(126), layout.y(24), layout.w(80), layout.h(18)).build();
		this.addRenderableWidget(cycleTabButton);
		this.addRenderableWidget(lifetimeTabButton);

		Button closeButton = Button.builder(Component.literal("Close"), b -> onClose()).bounds(layout.x(BASE_PANEL_WIDTH - 62), layout.y(8), layout.w(50), layout.h(16)).build();
		this.addRenderableWidget(closeButton);

		// Cycle tab controls
		prevCycleButton = Button.builder(Component.literal("< Previous"), b -> navigateArchive(-1)).bounds(layout.x(226), layout.y(BASE_PANEL_HEIGHT - 26), layout.w(68), layout.h(18)).build();
		nextCycleButton = Button.builder(Component.literal("Next >"), b -> navigateArchive(1)).bounds(layout.x(298), layout.y(BASE_PANEL_HEIGHT - 26), layout.w(56), layout.h(18)).build();
		archiveButton = Button.builder(Component.literal("Cycle Archive"), b -> toggleArchive()).bounds(layout.x(BASE_PANEL_WIDTH - 108), layout.y(BASE_PANEL_HEIGHT - 26), layout.w(96), layout.h(18)).build();
		this.addRenderableWidget(prevCycleButton);
		this.addRenderableWidget(nextCycleButton);
		this.addRenderableWidget(archiveButton);

		// Lifetime tab controls
		fromBox = new EditBox(this.font, layout.x(42), layout.y(48), layout.w(78), layout.h(18), Component.literal("From days ago"));
		fromBox.setHint(Component.literal("-# days ago").withStyle(ChatFormatting.GRAY));
		fromBox.setMaxLength(16);
		fromBox.setValue(fromDraft);

		toBox = new EditBox(this.font, layout.x(146), layout.y(48), layout.w(78), layout.h(18), Component.literal("To days ago"));
		toBox.setHint(Component.literal("-# days ago").withStyle(ChatFormatting.GRAY));
		toBox.setMaxLength(16);
		toBox.setValue(toDraft);

		applyIntervalButton = Button.builder(Component.literal("Apply"), b -> applyInterval()).bounds(layout.x(230), layout.y(48), layout.w(46), layout.h(18)).build();
		resetIntervalButton = Button.builder(Component.literal("Reset"), b -> resetInterval()).bounds(layout.x(280), layout.y(48), layout.w(46), layout.h(18)).build();
		finishesToggleButton = Button.builder(Component.literal("Cycle Finishes"), b -> toggleFinishes()).bounds(layout.x(BASE_PANEL_WIDTH - 118), layout.y(48), layout.w(106), layout.h(18)).build();

		this.addRenderableWidget(fromBox);
		this.addRenderableWidget(toBox);
		this.addRenderableWidget(applyIntervalButton);
		this.addRenderableWidget(resetIntervalButton);
		this.addRenderableWidget(finishesToggleButton);

		if (!initialRequestSent && mod != null) {
			initialRequestSent = true;
			seenCycleGen = mod.cycleLeaderboardGeneration();
			seenLifetimeGen = mod.lifetimeLeaderboardGeneration();
			mod.requestCycleLeaderboard(selectedCycleCode);
		}
		syncControlState();
	}

	@Override
	public void tick() {
		super.tick();
		if (mod == null) {
			return;
		}
		int cycleGen = mod.cycleLeaderboardGeneration();
		if (seenCycleGen != cycleGen) {
			seenCycleGen = cycleGen;
			raidsScroll = 0;
			warsScroll = 0;
			CycleLeaderboardData.CycleSnapshot snap = mod.knownCycleLeaderboard();
			if (snap != null && !snap.cycleCode().isEmpty() && snap.error().isEmpty()) {
				selectedCycleCode = snap.cycleCode();
			}
			syncControlState();
		}
		int lifeGen = mod.lifetimeLeaderboardGeneration();
		if (seenLifetimeGen != lifeGen) {
			seenLifetimeGen = lifeGen;
			raidsScroll = 0;
			warsScroll = 0;
			syncControlState();
		}
	}

	private void switchTab(Tab tab) {
		if (activeTab == tab) {
			return;
		}
		activeTab = tab;
		raidsScroll = 0;
		warsScroll = 0;
		validationError = "";
		if (tab == Tab.LIFETIME && !lifetimeRequested && mod != null) {
			lifetimeRequested = true;
			mod.requestLifetimeLeaderboard(null, null);
		}
		syncControlState();
	}

	private void toggleArchive() {
		archiveOpen = !archiveOpen;
		syncControlState();
	}

	private void navigateArchive(int step) {
		if (mod == null) {
			return;
		}
		CycleLeaderboardData.CycleSnapshot snap = mod.knownCycleLeaderboard();
		if (snap == null || snap.cycles().isEmpty()) {
			return;
		}
		List<String> cycles = snap.cycles();
		int idx = currentCycleIndex(cycles, snap.cycleCode());
		if (idx < 0) {
			return;
		}
		int target = idx + step;
		if (target >= 0 && target < cycles.size()) {
			selectedCycleCode = cycles.get(target);
			mod.requestCycleLeaderboard(selectedCycleCode);
			syncControlState();
		}
	}

	private void applyInterval() {
		CycleLeaderboardData.IntervalDays parsed = CycleLeaderboardData.parseIntervalDays(fromBox.getValue(), toBox.getValue());
		if (parsed == null) {
			validationError = "Invalid interval: positive days required, From > To";
			return;
		}
		validationError = "";
		if (mod != null) {
			mod.requestLifetimeLeaderboard(parsed.fromDays(), parsed.toDays());
		}
	}

	private void resetInterval() {
		fromBox.setValue("");
		toBox.setValue("");
		validationError = "";
		if (mod != null) {
			mod.requestLifetimeLeaderboard(null, null);
		}
	}

	private void toggleFinishes() {
		showFinishes = !showFinishes;
		raidsScroll = 0;
		warsScroll = 0;
		syncControlState();
	}

	private void syncControlState() {
		boolean isCycle = activeTab == Tab.CYCLE;
		cycleTabButton.active = !isCycle;
		lifetimeTabButton.active = isCycle;

		archiveButton.visible = isCycle;
		prevCycleButton.visible = isCycle && archiveOpen;
		nextCycleButton.visible = isCycle && archiveOpen;

		if (isCycle && archiveOpen) {
			CycleLeaderboardData.CycleSnapshot snap = mod != null ? mod.knownCycleLeaderboard() : null;
			List<String> cycles = snap != null ? snap.cycles() : List.of();
			String activeCode = snap != null ? snap.cycleCode() : "";
			int idx = currentCycleIndex(cycles, activeCode);
			prevCycleButton.active = idx > 0;
			nextCycleButton.active = idx >= 0 && idx < cycles.size() - 1;
		} else {
			prevCycleButton.active = false;
			nextCycleButton.active = false;
		}

		fromBox.visible = !isCycle;
		toBox.visible = !isCycle;
		applyIntervalButton.visible = !isCycle;
		resetIntervalButton.visible = !isCycle;
		finishesToggleButton.visible = !isCycle;
		finishesToggleButton.setMessage(Component.literal(showFinishes ? "Counts View" : "Cycle Finishes"));
	}

	private int currentCycleIndex(List<String> cycles, String activeCode) {
		if (cycles == null || cycles.isEmpty()) {
			return -1;
		}
		String code = (activeCode != null && !activeCode.isEmpty()) ? activeCode : selectedCycleCode;
		if (code == null || code.isEmpty()) {
			return cycles.size() - 1;
		}
		return cycles.indexOf(code);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(g);
		layout.drawPanel(g);

		if (activeTab == Tab.CYCLE) {
			renderCycleTab(g);
		} else {
			renderLifetimeTab(g);
		}

		super.render(g, scaledMouseX, scaledMouseY, delta);
		String heading = activeTab == Tab.CYCLE ? "Promotion Cycle" : "Lifetime Stats";
		g.drawCenteredString(this.font, heading, layout.centerX(), layout.y(10), 0xFFFFFFFF);
		popReferencePose(g);
	}

	private void renderCycleTab(GuiGraphics g) {
		CycleLeaderboardData.CycleSnapshot snap = mod != null ? mod.knownCycleLeaderboard() : null;
		String err = mod != null ? mod.recentCycleLeaderboardError() : "";

		String cycleHeader = (snap != null && !snap.cycleCode().isEmpty()) ? "Cycle " + snap.cycleCode() : "Loading...";
		g.drawString(this.font, cycleHeader, layout.x(220), layout.y(29), 0xFFFFCC55);

		if (!err.isEmpty()) {
			g.drawCenteredString(this.font, err, layout.centerX(), layout.y(126), 0xFFFF5555);
			return;
		}
		if (snap == null) {
			g.drawCenteredString(this.font, "Loading cycle leaderboard...", layout.centerX(), layout.y(126), 0xFFAAAAAA);
			return;
		}

		int listTopOffset = listTopOffset();
		g.drawCenteredString(this.font, "Raids", layout.x(LEFT_COL_X + COL_WIDTH / 2), layout.y(listTopOffset - 12), 0xFF55FFFF);
		g.drawCenteredString(this.font, "Wars", layout.x(RIGHT_COL_X + COL_WIDTH / 2), layout.y(listTopOffset - 12), 0xFFFF5555);

		renderEntryList(g, LEFT_COL_X, listTopOffset, snap.raids(), raidsScroll, true, true);
		renderEntryList(g, RIGHT_COL_X, listTopOffset, snap.wars(), warsScroll, true, true);

		int footerY = layout.y(BASE_PANEL_HEIGHT - 44);
		String totals = "Totals: " + snap.totalRaids() + " raids | " + snap.totalWars() + " wars | " + snap.totalTickets() + " tickets";
		g.drawString(this.font, totals, layout.x(12), footerY, 0xFFFFD966);
		if (!snap.raffleWinners().isEmpty()) {
			StringBuilder raffle = new StringBuilder("Raffle Top 3: ");
			List<CycleLeaderboardData.RaffleWinner> top = snap.raffleWinners().subList(0, Math.min(3, snap.raffleWinners().size()));
			for (int i = 0; i < top.size(); i++) {
				if (i > 0) {
					raffle.append(", ");
				}
				raffle.append("#").append(i + 1).append(" ").append(top.get(i).name()).append(" (").append(top.get(i).tickets()).append(")");
			}
			g.drawString(this.font, trimToWidth(raffle.toString(), layout.w(320)), layout.x(12), footerY + layout.h(12), 0xFFD5A6FF);
		}
	}

	private void renderLifetimeTab(GuiGraphics g) {
		g.drawString(this.font, "From:", layout.x(12), layout.y(53), 0xFFCCCCCC);
		g.drawString(this.font, "To:", layout.x(126), layout.y(53), 0xFFCCCCCC);
		if (!validationError.isEmpty()) {
			g.drawString(this.font, validationError, layout.x(12), layout.y(70), 0xFFFF5555);
		}

		CycleLeaderboardData.LifetimeSnapshot snap = mod != null ? mod.knownLifetimeLeaderboard() : null;
		String err = mod != null ? mod.recentLifetimeLeaderboardError() : "";
		if (!err.isEmpty()) {
			g.drawCenteredString(this.font, err, layout.centerX(), layout.y(140), 0xFFFF5555);
			return;
		}
		if (snap == null) {
			g.drawCenteredString(this.font, "Loading leaderboard stats...", layout.centerX(), layout.y(140), 0xFFAAAAAA);
			return;
		}

		int listTopOffset = listTopOffset();
		if (showFinishes) {
			g.drawCenteredString(this.font, "Raids Finishes", layout.x(LEFT_COL_X + COL_WIDTH / 2), layout.y(listTopOffset - 12), 0xFF55FFFF);
			g.drawCenteredString(this.font, "Wars Finishes", layout.x(RIGHT_COL_X + COL_WIDTH / 2), layout.y(listTopOffset - 12), 0xFFFF5555);
			renderPodiumList(g, LEFT_COL_X, listTopOffset, snap.raidFinishes(), raidsScroll);
			renderPodiumList(g, RIGHT_COL_X, listTopOffset, snap.warFinishes(), warsScroll);
			return;
		}

		String subtitle = snap.hasInterval() ? " (Filtered: no medals)" : "";
		g.drawCenteredString(this.font, "Raids" + subtitle, layout.x(LEFT_COL_X + COL_WIDTH / 2), layout.y(listTopOffset - 12), 0xFF55FFFF);
		g.drawCenteredString(this.font, "Wars" + subtitle, layout.x(RIGHT_COL_X + COL_WIDTH / 2), layout.y(listTopOffset - 12), 0xFFFF5555);

		boolean showMedals = !snap.hasInterval();
		renderEntryList(g, LEFT_COL_X, listTopOffset, snap.raids(), raidsScroll, showMedals, false);
		renderEntryList(g, RIGHT_COL_X, listTopOffset, snap.wars(), warsScroll, showMedals, false);
	}

	private void renderEntryList(GuiGraphics g, int baseColX, int baseTopY, List<CycleLeaderboardData.LeaderboardEntry> entries, int scrollOffset, boolean showMedals, boolean showDelta) {
		int x = layout.x(baseColX);
		int top = layout.y(baseTopY);
		int width = layout.w(COL_WIDTH);
		int height = layout.h(VISIBLE_ROWS * ROW_PITCH + 4);
		g.fill(x, top, x + width, top + height, 0x22000000);

		int total = entries.size();
		int clamped = clampOffset(scrollOffset, total);
		for (int vis = 0; vis < VISIBLE_ROWS; vis++) {
			int idx = clamped + vis;
			if (idx >= total) {
				break;
			}
			CycleLeaderboardData.LeaderboardEntry entry = entries.get(idx);
			int rowY = layout.y(baseTopY + 2 + vis * ROW_PITCH);
			int rowH = layout.h(ROW_HEIGHT);
			g.fill(x + layout.w(2), rowY, x + width - layout.w(2), rowY + rowH, entry.count() <= 0 ? ZERO_ROW_OVERLAY : ROW_FILL);

			int cursorX = x + layout.w(4);
			if (showDelta) {
				Integer deltaVal = entry.delta();
				String deltaText = deltaVal == null || deltaVal == 0 ? "-" : (deltaVal > 0 ? "+" + deltaVal : String.valueOf(deltaVal));
				int deltaColor = deltaVal == null || deltaVal == 0 ? 0xFF888888 : (deltaVal > 0 ? 0xFF55FF55 : 0xFFFF5555);
				g.drawString(this.font, deltaText, cursorX, rowY + layout.h(4), deltaColor);
				cursorX += layout.w(22);
			}

			if (this.minecraft != null) {
				PlayerSkin skin = PlayerSkinResolver.skinFor(this.minecraft, parseUuid(entry.uuid()), entry.name());
				if (skin != null) {
					PlayerFaceRenderer.draw(g, skin, cursorX, rowY + layout.h(2), layout.w(10));
				}
			}
			cursorX += layout.w(13);

			int rankColor = switch (entry.rank()) {
				case 1 -> 0xFFFFD700;
				case 2 -> 0xFFC0C0C0;
				case 3 -> 0xFFCD7F32;
				default -> 0xFFAAAAAA;
			};
			String rankStr = "#" + entry.rank();
			g.drawString(this.font, rankStr, cursorX, rowY + layout.h(4), entry.count() <= 0 ? 0xFFCCCCCC : rankColor);
			cursorX += layout.w(20);

			int medal = CycleLeaderboardData.medalForEntry(entry, showMedals);
			if (medal >= 1 && medal <= 3) {
				drawTexture(g, MEDAL_TEXTURES[medal - 1], cursorX, rowY + layout.h(2), layout.w(11), layout.h(11));
				cursorX += layout.w(13);
			}

			String countText = String.valueOf(entry.count());
			int countWidth = this.font.width(countText);
			int countX = x + width - layout.w(6) - countWidth;
			int maxNameW = Math.max(layout.w(24), countX - cursorX - layout.w(4));
			int textColor = entry.count() <= 0 ? 0xFFDDDDDD : 0xFFFFFFFF;
			g.drawString(this.font, trimToWidth(entry.name(), maxNameW), cursorX, rowY + layout.h(4), textColor);
			g.drawString(this.font, countText, countX, rowY + layout.h(4), textColor);
		}

		layout.drawScrollbar(g, layout.x(baseColX + SCROLLBAR_OFFSET), top, layout.w(SCROLLBAR_WIDTH), height, VISIBLE_ROWS, total, clamped);
	}

	private void renderPodiumList(GuiGraphics g, int baseColX, int baseTopY, List<CycleLeaderboardData.PodiumEntry> entries, int scrollOffset) {
		int x = layout.x(baseColX);
		int top = layout.y(baseTopY);
		int width = layout.w(COL_WIDTH);
		int height = layout.h(VISIBLE_ROWS * ROW_PITCH + 4);
		g.fill(x, top, x + width, top + height, 0x22000000);

		int total = entries.size();
		int clamped = clampOffset(scrollOffset, total);
		for (int vis = 0; vis < VISIBLE_ROWS; vis++) {
			int idx = clamped + vis;
			if (idx >= total) {
				break;
			}
			CycleLeaderboardData.PodiumEntry entry = entries.get(idx);
			int rowY = layout.y(baseTopY + 2 + vis * ROW_PITCH);
			int rowH = layout.h(ROW_HEIGHT);
			g.fill(x + layout.w(2), rowY, x + width - layout.w(2), rowY + rowH, ROW_FILL);

			int cursorX = x + layout.w(4);
			if (this.minecraft != null) {
				PlayerSkin skin = PlayerSkinResolver.skinFor(this.minecraft, parseUuid(entry.uuid()), entry.name());
				if (skin != null) {
					PlayerFaceRenderer.draw(g, skin, cursorX, rowY + layout.h(2), layout.w(10));
				}
			}
			cursorX += layout.w(13);

			int statsStart = x + width - layout.w(88);
			int maxNameW = Math.max(layout.w(24), statsStart - cursorX - layout.w(4));
			g.drawString(this.font, trimToWidth(entry.name(), maxNameW), cursorX, rowY + layout.h(4), 0xFFFFFFFF);

			int[] counts = {entry.first(), entry.second(), entry.third()};
			for (int m = 0; m < 3; m++) {
				int slotX = statsStart + m * layout.w(28);
				drawTexture(g, MEDAL_TEXTURES[m], slotX, rowY + layout.h(3), layout.w(9), layout.h(9));
				g.drawString(this.font, String.valueOf(counts[m]), slotX + layout.w(11), rowY + layout.h(4), 0xFFEFEFEF);
			}
		}

		layout.drawScrollbar(g, layout.x(baseColX + SCROLLBAR_OFFSET), top, layout.w(SCROLLBAR_WIDTH), height, VISIBLE_ROWS, total, clamped);
	}

	private int listTopOffset() {
		return activeTab == Tab.CYCLE ? 62 : 84;
	}

	private List<?> leftEntries() {
		if (mod == null) {
			return List.of();
		}
		if (activeTab == Tab.CYCLE) {
			CycleLeaderboardData.CycleSnapshot snap = mod.knownCycleLeaderboard();
			return snap != null ? snap.raids() : List.of();
		}
		CycleLeaderboardData.LifetimeSnapshot snap = mod.knownLifetimeLeaderboard();
		if (snap == null) {
			return List.of();
		}
		return showFinishes ? snap.raidFinishes() : snap.raids();
	}

	private List<?> rightEntries() {
		if (mod == null) {
			return List.of();
		}
		if (activeTab == Tab.CYCLE) {
			CycleLeaderboardData.CycleSnapshot snap = mod.knownCycleLeaderboard();
			return snap != null ? snap.wars() : List.of();
		}
		CycleLeaderboardData.LifetimeSnapshot snap = mod.knownLifetimeLeaderboard();
		if (snap == null) {
			return List.of();
		}
		return showFinishes ? snap.warFinishes() : snap.wars();
	}

	private int clampOffset(int offset, int total) {
		int max = Math.max(0, total - VISIBLE_ROWS);
		return Math.clamp(offset, 0, max);
	}

	private static UUID parseUuid(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw.trim());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private String trimToWidth(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("..."))) + "...";
	}

	private static void drawTexture(GuiGraphics g, Identifier id, int x, int y, int w, int h) {
		if (id == null || w <= 0 || h <= 0) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / 64.0f, h / 64.0f);
		g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0.0f, 0.0f, 64, 64, 64, 64);
		g.pose().popMatrix();
	}

	private boolean isInPane(double scaledX, double scaledY, int baseColX) {
		int left = layout.x(baseColX);
		int top = layout.y(listTopOffset());
		int right = left + layout.w(COL_WIDTH + SCROLLBAR_WIDTH + 4);
		int bottom = top + layout.h(VISIBLE_ROWS * ROW_PITCH + 4);
		return scaledX >= left && scaledX <= right && scaledY >= top && scaledY <= bottom;
	}

	private boolean isOverScrollbar(double scaledX, double scaledY, int baseColX) {
		int sbX = layout.x(baseColX + SCROLLBAR_OFFSET);
		int top = layout.y(listTopOffset());
		int bottom = top + layout.h(VISIBLE_ROWS * ROW_PITCH + 4);
		return scaledX >= sbX && scaledX <= sbX + layout.w(SCROLLBAR_WIDTH) && scaledY >= top && scaledY <= bottom;
	}

	private void updateScrollFromMouse(double scaledY, int pane) {
		int top = layout.y(listTopOffset());
		int height = layout.h(VISIBLE_ROWS * ROW_PITCH + 4);
		int total = pane == 1 ? leftEntries().size() : rightEntries().size();
		int maxOffset = Math.max(0, total - VISIBLE_ROWS);
		float ratio = Math.clamp((float) ((scaledY - top) / Math.max(1, height)), 0.0f, 1.0f);
		int target = Math.round(ratio * maxOffset);
		if (pane == 1) {
			raidsScroll = target;
		} else {
			warsScroll = target;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent scaled = rescale(event);
		if (scaled.button() == 0) {
			if (isOverScrollbar(scaled.x(), scaled.y(), LEFT_COL_X)) {
				draggingPane = 1;
				updateScrollFromMouse(scaled.y(), 1);
				return true;
			}
			if (isOverScrollbar(scaled.x(), scaled.y(), RIGHT_COL_X)) {
				draggingPane = 2;
				updateScrollFromMouse(scaled.y(), 2);
				return true;
			}
		}
		return super.mouseClicked(scaled, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingPane = 0;
		return super.mouseReleased(rescale(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double d, double e) {
		MouseButtonEvent scaled = rescale(event);
		if (draggingPane != 0) {
			updateScrollFromMouse(scaled.y(), draggingPane);
			return true;
		}
		return super.mouseDragged(scaled, d, e);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		double sx = mouseX / uiScale;
		double sy = mouseY / uiScale;
		int step = scrollY > 0 ? -1 : 1;
		if (isInPane(sx, sy, LEFT_COL_X)) {
			raidsScroll = clampOffset(raidsScroll + step, leftEntries().size());
			return true;
		}
		if (isInPane(sx, sy, RIGHT_COL_X)) {
			warsScroll = clampOffset(warsScroll + step, rightEntries().size());
			return true;
		}
		return super.mouseScrolled(sx, sy, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (activeTab == Tab.LIFETIME) {
			if (fromBox.keyPressed(event) || toBox.keyPressed(event)) {
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (activeTab == Tab.LIFETIME) {
			if (fromBox.charTyped(event) || toBox.charTyped(event)) {
				return true;
			}
		}
		return super.charTyped(event);
	}
}
