package tel.eden.mod.gui;

import java.util.List;
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
	private static final int MARGIN_X = 12;
	private static final int COL_GAP = 16;
	private static final int SCROLLBAR_GAP = 2;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int ROW_PITCH = 17;
	private static final int ROW_HEIGHT = 15;
	private static final int PODIUM_CAP_WIDTH = 30;
	private static final int PODIUM_HEAD_SIZE = 32;
	private static final int ZERO_ROW_OVERLAY = 0x44CC2222;
	private static final int ROW_FILL = 0x44282828;

	private static final Identifier EDEN_ICON = Identifier.fromNamespaceAndPath("edenmod", "icon.png");
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
		layout = centeredLayout(200, 120);
		uiScale = 1.0f;
		virtualWidth = this.width;
		virtualHeight = this.height;

		if (fromBox != null) {
			fromDraft = fromBox.getValue();
		}
		if (toBox != null) {
			toDraft = toBox.getValue();
		}

		this.clearWidgets();

		cycleTabButton = Button.builder(Component.literal("Promotion Cycle"), b -> switchTab(Tab.CYCLE)).bounds(12, 24, 110, 18).build();
		lifetimeTabButton = Button.builder(Component.literal("Lifetime"), b -> switchTab(Tab.LIFETIME)).bounds(126, 24, 80, 18).build();
		this.addRenderableWidget(cycleTabButton);
		this.addRenderableWidget(lifetimeTabButton);

		int closeX = bottomCloseX();
		Button closeButton = Button.builder(Component.literal("Close"), b -> onClose()).bounds(closeX, this.height - 24, 100, 18).build();
		this.addRenderableWidget(closeButton);

		// Cycle tab archive controls
		prevCycleButton = Button.builder(Component.literal("< Previous"), b -> navigateArchive(-1)).bounds(this.width - 236, this.height - 24, 68, 18).build();
		nextCycleButton = Button.builder(Component.literal("Next >"), b -> navigateArchive(1)).bounds(this.width - 164, this.height - 24, 56, 18).build();
		archiveButton = Button.builder(Component.literal("Cycle Archive"), b -> toggleArchive()).bounds(this.width - 104, this.height - 24, 92, 18).build();
		this.addRenderableWidget(prevCycleButton);
		this.addRenderableWidget(nextCycleButton);
		this.addRenderableWidget(archiveButton);

		// Lifetime tab controls
		fromBox = new EditBox(this.font, 42, 48, 78, 18, Component.literal("From days ago"));
		fromBox.setHint(Component.literal("-# days ago").withStyle(ChatFormatting.GRAY));
		fromBox.setMaxLength(16);
		fromBox.setValue(fromDraft);

		toBox = new EditBox(this.font, 146, 48, 78, 18, Component.literal("To days ago"));
		toBox.setHint(Component.literal("-# days ago").withStyle(ChatFormatting.GRAY));
		toBox.setMaxLength(16);
		toBox.setValue(toDraft);

		applyIntervalButton = Button.builder(Component.literal("Apply"), b -> applyInterval()).bounds(230, 48, 46, 18).build();
		resetIntervalButton = Button.builder(Component.literal("Reset"), b -> resetInterval()).bounds(280, 48, 46, 18).build();
		finishesToggleButton = Button.builder(Component.literal("Cycle Finishes"), b -> toggleFinishes()).bounds(this.width - 118, 48, 106, 18).build();

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
			if (!mod.recentCycleLeaderboardError().isEmpty()) {
				selectedCycleCode = (snap != null && !snap.cycleCode().isEmpty()) ? snap.cycleCode() : null;
			} else if (snap != null && !snap.cycleCode().isEmpty() && snap.error().isEmpty()) {
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
		String currentCode = (selectedCycleCode != null && !selectedCycleCode.isEmpty()) ? selectedCycleCode : snap.cycleCode();
		int idx = currentCycleIndex(cycles, currentCode);
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
			String activeCode = (selectedCycleCode != null && !selectedCycleCode.isEmpty()) ? selectedCycleCode : (snap != null ? snap.cycleCode() : "");
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
		if (uiScale != 1.0f || virtualWidth != this.width || virtualHeight != this.height) {
			init();
		}
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(g);
		EdenPanelLayout.drawPanel(g, 0, 0, this.width, this.height);

		if (activeTab == Tab.CYCLE) {
			renderCycleTab(g);
		} else {
			renderLifetimeTab(g);
		}

		super.render(g, scaledMouseX, scaledMouseY, delta);
		String heading = activeTab == Tab.CYCLE ? "Promotion Cycle" : "Lifetime Stats";
		g.drawCenteredString(this.font, heading, this.width / 2, 10, 0xFFFFFFFF);
		popReferencePose(g);
	}

	private void renderCycleTab(GuiGraphics g) {
		CycleLeaderboardData.CycleSnapshot snap = mod != null ? mod.knownCycleLeaderboard() : null;
		String err = mod != null ? mod.recentCycleLeaderboardError() : "";

		String cycleHeader = (snap != null && !snap.cycleCode().isEmpty()) ? "Cycle " + snap.cycleCode() : "Loading...";
		g.drawString(this.font, cycleHeader, 220, 29, 0xFFFFCC55);

		if (!err.isEmpty()) {
			g.drawCenteredString(this.font, err, this.width / 2, this.height / 2, 0xFFFF5555);
			return;
		}
		if (snap == null) {
			g.drawCenteredString(this.font, "Loading cycle leaderboard...", this.width / 2, this.height / 2, 0xFFAAAAAA);
			return;
		}

		int colW = colWidth();
		int titleY = titleY();
		g.drawCenteredString(this.font, "Raids", colX(true) + colW / 2, titleY, 0xFF55FFFF);
		g.drawCenteredString(this.font, "Wars", colX(false) + colW / 2, titleY, 0xFFFF5555);

		renderEntryList(g, true, snap.raids(), raidsScroll, true, true);
		renderEntryList(g, false, snap.wars(), warsScroll, true, true);

		int maxFooterW = Math.max(60, bottomCloseX() - 20);
		String totals = "Totals: " + snap.totalRaids() + " raids | " + snap.totalWars() + " wars | " + snap.totalTickets() + " tickets";
		g.drawString(this.font, trimToWidth(totals, maxFooterW), 12, this.height - 36, 0xFFFFD966);
		if (!snap.raffleWinners().isEmpty()) {
			StringBuilder raffle = new StringBuilder("Raffle Top 3: ");
			List<CycleLeaderboardData.RaffleWinner> top = snap.raffleWinners().subList(0, Math.min(3, snap.raffleWinners().size()));
			for (int i = 0; i < top.size(); i++) {
				if (i > 0) {
					raffle.append(", ");
				}
				raffle.append("#").append(i + 1).append(" ").append(top.get(i).name()).append(" (").append(top.get(i).tickets()).append(")");
			}
			g.drawString(this.font, trimToWidth(raffle.toString(), maxFooterW), 12, this.height - 24, 0xFFD5A6FF);
		}
	}

	private void renderLifetimeTab(GuiGraphics g) {
		g.drawString(this.font, "From:", 12, 53, 0xFFCCCCCC);
		g.drawString(this.font, "To:", 126, 53, 0xFFCCCCCC);
		if (!validationError.isEmpty()) {
			int errMaxW = Math.max(40, (this.width - 126) - 332);
			g.drawString(this.font, trimToWidth(validationError, errMaxW), 332, 53, 0xFFFF5555);
		}

		CycleLeaderboardData.LifetimeSnapshot snap = mod != null ? mod.knownLifetimeLeaderboard() : null;
		String err = mod != null ? mod.recentLifetimeLeaderboardError() : "";
		if (!err.isEmpty()) {
			g.drawCenteredString(this.font, err, this.width / 2, this.height / 2 + 12, 0xFFFF5555);
			return;
		}
		if (snap == null) {
			g.drawCenteredString(this.font, "Loading leaderboard stats...", this.width / 2, this.height / 2 + 12, 0xFFAAAAAA);
			return;
		}

		int colW = colWidth();
		int titleY = titleY();
		int leftCenter = colX(true) + colW / 2;
		int rightCenter = colX(false) + colW / 2;

		if (showFinishes) {
			g.drawCenteredString(this.font, "Raids Finishes", leftCenter, titleY, 0xFF55FFFF);
			g.drawCenteredString(this.font, "Wars Finishes", rightCenter, titleY, 0xFFFF5555);
			renderPodiumList(g, true, snap.raidFinishes(), raidsScroll);
			renderPodiumList(g, false, snap.warFinishes(), warsScroll);
			return;
		}

		String subtitle = snap.hasInterval() ? " (Filtered: no medals)" : "";
		g.drawCenteredString(this.font, "Raids" + subtitle, leftCenter, titleY, 0xFF55FFFF);
		g.drawCenteredString(this.font, "Wars" + subtitle, rightCenter, titleY, 0xFFFF5555);

		boolean showMedals = !snap.hasInterval();
		renderEntryList(g, true, snap.raids(), raidsScroll, showMedals, false);
		renderEntryList(g, false, snap.wars(), warsScroll, showMedals, false);
	}

	private void renderPodium(GuiGraphics g, int x, int colW, List<?> rawEntries) {
		int podiumTop = podiumTop();
		int podiumH = podiumHeight();
		g.fill(x, podiumTop, x + colW, podiumTop + podiumH, 0x22000000);

		int slotW = Math.clamp(colW / 3 - 4, 48, 96);
		int cx1 = x + colW / 2;
		int cx2 = cx1 - slotW;
		int cx3 = cx1 + slotW;
		int maxStandH = Math.max(70, podiumH - 54);
		int h2 = Math.max(52, maxStandH * 76 / 100);
		int h3 = Math.max(38, maxStandH * 56 / 100);
		int baseLineY = podiumTop + podiumH - 4;
		int lineLeft = Math.max(x + 4, cx2 - PODIUM_CAP_WIDTH / 2 - 8);
		int lineRight = Math.min(x + colW - 4, cx3 + PODIUM_CAP_WIDTH / 2 + 8);
		g.fill(lineLeft, baseLineY, lineRight, baseLineY + 2, 0xFF8E8E8E);
		g.fill(lineLeft, baseLineY + 2, lineRight, baseLineY + 3, 0xFF1E1E1E);

		for (int slot = 0; slot < 3; slot++) {
			int rank = slot == 0 ? 2 : (slot == 1 ? 1 : 3);
			int cx = slot == 0 ? cx2 : (slot == 1 ? cx1 : cx3);
			int standH = slot == 0 ? h2 : (slot == 1 ? maxStandH : h3);
			int standTopY = baseLineY - standH;
			drawPillar(g, cx, baseLineY, PODIUM_CAP_WIDTH, standH);

			int entryIdx = rank - 1;
			if (entryIdx >= rawEntries.size()) {
				continue;
			}
			String uuid = null;
			String name = null;
			String suffix = null;
			Object item = rawEntries.get(entryIdx);
			if (item instanceof CycleLeaderboardData.LeaderboardEntry e) {
				if (e.count() <= 0) {
					continue;
				}
				uuid = e.uuid();
				name = e.name();
				suffix = " (" + e.count() + ")";
			} else if (item instanceof CycleLeaderboardData.PodiumEntry p) {
				uuid = p.uuid();
				name = p.name();
				suffix = " (" + p.first() + "/" + p.second() + "/" + p.third() + ")";
			}
			if (name == null) {
				continue;
			}

			int headStartX = cx - PODIUM_HEAD_SIZE / 2;
			int headY = standTopY - PODIUM_HEAD_SIZE - 2;
			drawPlayerFace(g, uuid, name, headStartX, headY, PODIUM_HEAD_SIZE);

			int halfBound = switch (rank) {
				case 2 -> Math.min(cx - x - 3, slotW - PODIUM_HEAD_SIZE / 2 - 3);
				case 3 -> Math.min((x + colW) - cx - 3, slotW - PODIUM_HEAD_SIZE / 2 - 3);
				default -> Math.min(colW / 2 - 4, (slotW + 28) / 2);
			};
			int maxLabelW = Math.max(24, halfBound * 2);
			int maxNameW = Math.max(12, maxLabelW - this.font.width(suffix));
			g.drawCenteredString(this.font, trimToWidth(name, maxNameW) + suffix, cx, standTopY - PODIUM_HEAD_SIZE - 12, 0xFFFFFFFF);
		}
	}

	private void renderEntryList(GuiGraphics g, boolean left, List<CycleLeaderboardData.LeaderboardEntry> entries, int scrollOffset, boolean showMedals, boolean showDelta) {
		int x = colX(left);
		int colW = colWidth();
		renderPodium(g, x, colW, entries);

		int baseTopY = listTopOffset();
		int visRows = visibleRows();
		int height = visRows * ROW_PITCH + 4;
		g.fill(x, baseTopY, x + colW, baseTopY + height, 0x22000000);

		int total = entries.size();
		int clamped = clampOffset(scrollOffset, total);
		for (int vis = 0; vis < visRows; vis++) {
			int idx = clamped + vis;
			if (idx >= total) {
				break;
			}
			CycleLeaderboardData.LeaderboardEntry entry = entries.get(idx);
			int rowY = baseTopY + 2 + vis * ROW_PITCH;
			g.fill(x + 2, rowY, x + colW - 2, rowY + ROW_HEIGHT, entry.count() <= 0 ? ZERO_ROW_OVERLAY : ROW_FILL);

			int cursorX = x + 4;
			if (showDelta) {
				Integer deltaVal = entry.delta();
				String deltaText = deltaVal == null || deltaVal == 0 ? "-" : (deltaVal > 0 ? "+" + deltaVal : String.valueOf(deltaVal));
				int deltaColor = deltaVal == null || deltaVal == 0 ? 0xFF888888 : (deltaVal > 0 ? 0xFF55FF55 : 0xFFFF5555);
				g.drawString(this.font, deltaText, cursorX, rowY + 4, deltaColor);
				cursorX += Math.max(22, this.font.width(deltaText) + 4);
			}

			drawPlayerFace(g, entry.uuid(), entry.name(), cursorX, rowY + 2, 10);
			cursorX += 13;

			int rankColor = switch (entry.rank()) {
				case 1 -> 0xFFFFD700;
				case 2 -> 0xFFC0C0C0;
				case 3 -> 0xFFCD7F32;
				default -> 0xFFAAAAAA;
			};
			String rankStr = "#" + entry.rank();
			g.drawString(this.font, rankStr, cursorX, rowY + 4, entry.count() <= 0 ? 0xFFCCCCCC : rankColor);
			cursorX += Math.max(28, this.font.width(rankStr) + 4);

			int medal = CycleLeaderboardData.medalForEntry(entry, showMedals);
			if (medal >= 1 && medal <= 3) {
				drawTexture(g, MEDAL_TEXTURES[medal - 1], cursorX, rowY + 2, 11, 11);
				cursorX += 13;
			}

			String countText = String.valueOf(entry.count());
			int countWidth = this.font.width(countText);
			int countX = x + colW - 6 - countWidth;
			int maxNameW = Math.max(24, countX - cursorX - 4);
			int textColor = entry.count() <= 0 ? 0xFFDDDDDD : 0xFFFFFFFF;
			g.drawString(this.font, trimToWidth(entry.name(), maxNameW), cursorX, rowY + 4, textColor);
			g.drawString(this.font, countText, countX, rowY + 4, textColor);
		}

		layout.drawScrollbar(g, x + colW + SCROLLBAR_GAP, baseTopY, SCROLLBAR_WIDTH, height, visRows, total, clamped);
	}

	private void renderPodiumList(GuiGraphics g, boolean left, List<CycleLeaderboardData.PodiumEntry> entries, int scrollOffset) {
		int x = colX(left);
		int colW = colWidth();
		renderPodium(g, x, colW, entries);

		int baseTopY = listTopOffset();
		int visRows = visibleRows();
		int height = visRows * ROW_PITCH + 4;
		g.fill(x, baseTopY, x + colW, baseTopY + height, 0x22000000);

		int total = entries.size();
		int clamped = clampOffset(scrollOffset, total);
		for (int vis = 0; vis < visRows; vis++) {
			int idx = clamped + vis;
			if (idx >= total) {
				break;
			}
			CycleLeaderboardData.PodiumEntry entry = entries.get(idx);
			int rowY = baseTopY + 2 + vis * ROW_PITCH;
			g.fill(x + 2, rowY, x + colW - 2, rowY + ROW_HEIGHT, ROW_FILL);

			int cursorX = x + 4;
			drawPlayerFace(g, entry.uuid(), entry.name(), cursorX, rowY + 2, 10);
			cursorX += 13;

			int statsStart = x + colW - 88;
			int maxNameW = Math.max(24, statsStart - cursorX - 4);
			g.drawString(this.font, trimToWidth(entry.name(), maxNameW), cursorX, rowY + 4, 0xFFFFFFFF);

			int[] counts = {entry.first(), entry.second(), entry.third()};
			for (int m = 0; m < 3; m++) {
				int slotX = statsStart + m * 28;
				drawTexture(g, MEDAL_TEXTURES[m], slotX, rowY + 3, 9, 9);
				g.drawString(this.font, String.valueOf(counts[m]), slotX + 11, rowY + 4, 0xFFEFEFEF);
			}
		}

		layout.drawScrollbar(g, x + colW + SCROLLBAR_GAP, baseTopY, SCROLLBAR_WIDTH, height, visRows, total, clamped);
	}

	private void drawPlayerFace(GuiGraphics g, String uuid, String name, int x, int y, int size) {
		if (this.minecraft == null) {
			return;
		}
		PlayerSkin skin = PlayerSkinResolver.skinForId(this.minecraft, uuid, name);
		if (skin != null) {
			PlayerFaceRenderer.draw(g, skin, x, y, size);
		}
	}

	private int bottomCloseX() {
		return Math.min(this.width / 2 - 50, Math.max(12, this.width - 344));
	}

	private int totalColWidth() {
		return Math.max(200, (this.width - MARGIN_X * 2 - COL_GAP) / 2);
	}

	private int colWidth() {
		return totalColWidth() - SCROLLBAR_GAP - SCROLLBAR_WIDTH;
	}

	private int colX(boolean left) {
		return left ? MARGIN_X : (MARGIN_X + totalColWidth() + COL_GAP);
	}

	private int titleY() {
		return activeTab == Tab.CYCLE ? 46 : 70;
	}

	private int podiumTop() {
		return titleY() + 11;
	}

	private int footerReserve() {
		return activeTab == Tab.CYCLE ? 42 : 30;
	}

	private int podiumHeight() {
		int bottomLimit = Math.max(podiumTop() + 190, this.height - footerReserve());
		return Math.clamp((bottomLimit - podiumTop()) * 58 / 100, 126, 184);
	}

	private int listTopOffset() {
		return podiumTop() + podiumHeight() + 4;
	}

	private int visibleRows() {
		int top = listTopOffset();
		int listBottom = Math.max(top + ROW_PITCH * 4 + 4, this.height - footerReserve());
		return Math.max(4, (listBottom - top - 4) / ROW_PITCH);
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
		int max = Math.max(0, total - visibleRows());
		return Math.clamp(offset, 0, max);
	}

	private String trimToWidth(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("..."))) + "...";
	}

	private static void drawPillar(GuiGraphics g, int centerX, int baseLineY, int capW, int standH) {
		int evenCapW = capW & ~1;
		int clampedH = Math.max(24, standH);
		int capH = Math.min(clampedH - 6, Math.max(14, Math.round(evenCapW * 41f / 45f)));
		int shaftW = Math.max(16, Math.round(evenCapW * 31f / 45f)) & ~1;
		int shaftH = Math.max(4, clampedH - capH);
		int standTopY = baseLineY - clampedH;

		g.blit(RenderPipelines.GUI_TEXTURED, EDEN_ICON, centerX - evenCapW / 2, standTopY, 18.0f, 13.0f, evenCapW, capH, 45, 41, 256, 256);
		int segMaxH = Math.max(12, Math.round(60f * evenCapW / 45f));
		int drawn = 0;
		while (drawn < shaftH) {
			int segH = Math.min(shaftH - drawn, segMaxH);
			int srcH = Math.clamp(Math.round(segH * 45f / evenCapW), 1, 60);
			g.blit(RenderPipelines.GUI_TEXTURED, EDEN_ICON, centerX - shaftW / 2, standTopY + capH + drawn, 25.0f, 54.0f, shaftW, segH, 31, srcH, 256, 256);
			drawn += segH;
		}
		g.fill(centerX - shaftW / 2 - 2, baseLineY - 2, centerX - shaftW / 2 + shaftW + 2, baseLineY - 1, 0xFF9E8FB2);
		g.fill(centerX - shaftW / 2 - 2, baseLineY - 1, centerX - shaftW / 2 + shaftW + 2, baseLineY, 0xFF231F20);
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

	private boolean isInPane(double scaledX, double scaledY, boolean leftPane) {
		int left = colX(leftPane);
		int bottom = listTopOffset() + visibleRows() * ROW_PITCH + 4;
		return scaledX >= left && scaledX <= left + totalColWidth() + 4 && scaledY >= podiumTop() && scaledY <= bottom;
	}

	private boolean isOverScrollbar(double scaledX, double scaledY, boolean leftPane) {
		int sbX = colX(leftPane) + colWidth() + SCROLLBAR_GAP;
		int top = listTopOffset();
		int bottom = top + visibleRows() * ROW_PITCH + 4;
		return scaledX >= sbX - 2 && scaledX <= sbX + SCROLLBAR_WIDTH + 2 && scaledY >= top && scaledY <= bottom;
	}

	private void updateScrollFromMouse(double scaledY, int pane) {
		int top = listTopOffset();
		int visRows = visibleRows();
		int height = visRows * ROW_PITCH + 4;
		int total = pane == 1 ? leftEntries().size() : rightEntries().size();
		int maxOffset = Math.max(0, total - visRows);
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
			if (isOverScrollbar(scaled.x(), scaled.y(), true)) {
				draggingPane = 1;
				updateScrollFromMouse(scaled.y(), 1);
				return true;
			}
			if (isOverScrollbar(scaled.x(), scaled.y(), false)) {
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
		if (isInPane(sx, sy, true)) {
			raidsScroll = clampOffset(raidsScroll + step, leftEntries().size());
			return true;
		}
		if (isInPane(sx, sy, false)) {
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
