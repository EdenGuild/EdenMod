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

	private static final int LEFT_COL_X = 14;
	private static final int RIGHT_COL_X = 240;
	private static final int COL_WIDTH = 208;
	private static final int SCROLLBAR_OFFSET = 210;
	private static final int SCROLLBAR_WIDTH = 6;
	private static final int VISIBLE_ROWS = 8;
	private static final int ROW_PITCH = 18;
	private static final int ROW_HEIGHT = 16;
	private static final int ZERO_ROW_OVERLAY = 0x48CC2222;
	private static final int ROW_FILL = 0x520F281A;

	private static final Identifier[] MEDAL_TEXTURES = {Identifier.fromNamespaceAndPath("edenmod", "textures/gui/cycle/medal_1.png"), Identifier.fromNamespaceAndPath("edenmod", "textures/gui/cycle/medal_2.png"), Identifier.fromNamespaceAndPath("edenmod", "textures/gui/cycle/medal_3.png"), Identifier.fromNamespaceAndPath("edenmod", "icon.png"),};

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

		int tabStartX = Math.max(198, (this.width - 196) / 2);
		int tabY = 18;
		cycleTabButton = Button.builder(Component.literal("Promotion Cycle"), b -> switchTab(Tab.CYCLE)).bounds(tabStartX, tabY, 112, 20).build();
		lifetimeTabButton = Button.builder(Component.literal("Lifetime"), b -> switchTab(Tab.LIFETIME)).bounds(tabStartX + 116, tabY, 80, 20).build();
		this.addRenderableWidget(cycleTabButton);
		this.addRenderableWidget(lifetimeTabButton);

		Button closeButton = Button.builder(Component.literal("Close"), b -> onClose()).bounds(this.width - 66, 18, 50, 20).build();
		this.addRenderableWidget(closeButton);

		// Cycle tab archive controls in full-width bottom-right footer
		int archiveY = this.height - 30;
		prevCycleButton = Button.builder(Component.literal("< Previous"), b -> navigateArchive(-1)).bounds(this.width - 246, archiveY, 70, 18).build();
		nextCycleButton = Button.builder(Component.literal("Next >"), b -> navigateArchive(1)).bounds(this.width - 172, archiveY, 58, 18).build();
		archiveButton = Button.builder(Component.literal("Cycle Archive"), b -> toggleArchive()).bounds(this.width - 110, archiveY, 94, 18).build();
		this.addRenderableWidget(prevCycleButton);
		this.addRenderableWidget(nextCycleButton);
		this.addRenderableWidget(archiveButton);

		// Lifetime tab controls inside emerald filter bar
		int filterY = 62;
		fromBox = new EditBox(this.font, 48, filterY, 80, 18, Component.literal("From days ago"));
		fromBox.setHint(Component.literal("-# days ago").withStyle(ChatFormatting.GRAY));
		fromBox.setMaxLength(16);
		fromBox.setValue(fromDraft);

		toBox = new EditBox(this.font, 154, filterY, 80, 18, Component.literal("To days ago"));
		toBox.setHint(Component.literal("-# days ago").withStyle(ChatFormatting.GRAY));
		toBox.setMaxLength(16);
		toBox.setValue(toDraft);

		applyIntervalButton = Button.builder(Component.literal("Apply"), b -> applyInterval()).bounds(240, filterY, 48, 18).build();
		resetIntervalButton = Button.builder(Component.literal("Reset"), b -> resetInterval()).bounds(292, filterY, 48, 18).build();
		finishesToggleButton = Button.builder(Component.literal("Cycle Finishes"), b -> toggleFinishes()).bounds(this.width - 126, filterY, 108, 18).build();

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
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(g);

		// Full-screen layered emerald backdrop
		g.fillGradient(0, 0, this.width, this.height, 0xF006150C, 0xF6030906);
		g.fillGradient(0, 0, this.width, Math.min(this.height, 150), 0x5C1D7843, 0x0006150C);
		g.fillGradient(0, Math.max(0, this.height - 96), this.width, this.height, 0x00030906, 0x8C020604);
		g.fill(0, 0, this.width, 1, 0xFF287D4B);
		g.fill(0, this.height - 1, this.width, this.height, 0xFF1A5734);
		g.fill(0, 0, 1, this.height, 0xFF287D4B);
		g.fill(this.width - 1, 0, this.width, this.height, 0xFF1A5734);

		// Shaded emerald top banner with framed Eden Logo crest
		int headerRight = this.width - 8;
		g.fillGradient(8, 6, headerRight, 52, 0xEB113821, 0xEB092113);
		g.fill(8, 6, headerRight, 7, 0x6658F59F);
		g.fill(8, 50, headerRight, 52, 0xFF2EA866);
		g.fill(8, 52, headerRight, 55, 0x55000000);
		g.fill(8, 6, 9, 52, 0xFF256D43);
		g.fill(headerRight - 1, 6, headerRight, 52, 0xFF256D43);

		// Eden Logo emblem
		g.fill(15, 9, 53, 47, 0xAA0C2818);
		g.fill(15, 9, 53, 10, 0xFF47DB86);
		g.fill(15, 46, 53, 47, 0xFF257A48);
		g.fill(15, 9, 16, 47, 0xFF47DB86);
		g.fill(52, 9, 53, 47, 0xFF257A48);
		drawTexture(g, MEDAL_TEXTURES[3], 17, 11, 34, 34);

		String heading = activeTab == Tab.CYCLE ? "Eden Promotion Cycle" : "Eden Lifetime Stats";
		g.drawString(this.font, heading, 60, 15, 0xFF7BFFAE);

		// Active-tab glowing mint indicator underneath the selected tab button
		int tabStartX = Math.max(198, (this.width - 196) / 2);
		int activeIndX = activeTab == Tab.CYCLE ? tabStartX : (tabStartX + 116);
		int activeIndW = activeTab == Tab.CYCLE ? 112 : 80;
		g.fill(activeIndX + 4, 40, activeIndX + activeIndW - 4, 42, 0xFF56FF9F);

		if (activeTab == Tab.CYCLE) {
			renderCycleTab(g);
		} else {
			renderLifetimeTab(g);
		}

		super.render(g, scaledMouseX, scaledMouseY, delta);
		popReferencePose(g);
	}

	private void renderCycleTab(GuiGraphics g) {
		CycleLeaderboardData.CycleSnapshot snap = mod != null ? mod.knownCycleLeaderboard() : null;
		String err = mod != null ? mod.recentCycleLeaderboardError() : "";

		String cycleBadge;
		if (snap != null && !snap.cycleCode().isEmpty()) {
			List<String> cycles = snap.cycles();
			boolean latest = cycles.isEmpty() || snap.cycleCode().equals(cycles.get(cycles.size() - 1));
			cycleBadge = "Cycle " + snap.cycleCode() + (latest ? "  •  Current" : "  •  Archive");
		} else {
			cycleBadge = "Fetching cycle...";
		}
		g.drawString(this.font, cycleBadge, 60, 29, 0xFFA8EBC2);

		// Bottom shaded footer card
		int footerTop = this.height - 36;
		int footerRight = this.width - 8;
		g.fillGradient(8, footerTop, footerRight, this.height - 6, 0xEB0E2E1C, 0xEB07190F);
		g.fill(8, footerTop, footerRight, footerTop + 1, 0xFF319A5D);

		int centerY = this.height / 2;
		if (!err.isEmpty()) {
			g.drawCenteredString(this.font, err, this.width / 2, centerY - 4, 0xFFFF6B6B);
			return;
		}
		if (snap == null) {
			g.drawCenteredString(this.font, "Loading cycle leaderboard...", this.width / 2, centerY - 4, 0xFFA8EBC2);
			return;
		}

		int listTop = listTopOffset();
		int colW = Math.max(180, (this.width - 42) / 2);
		int leftX = 14;
		int rightX = 14 + colW + 14;
		int hdrTop = listTop - 20;

		// Column Headers (shaded emerald & gold-emerald)
		g.fillGradient(leftX, hdrTop, leftX + colW, listTop, 0xEE14472D, 0xEE0C2C1B);
		g.fill(leftX, hdrTop, leftX + colW, hdrTop + 2, 0xFF42E695);
		g.drawString(this.font, "Raids", leftX + 8, hdrTop + 6, 0xFF7CFFD1);
		String rCount = snap.raids().size() + " players";
		g.drawString(this.font, rCount, leftX + colW - 8 - this.font.width(rCount), hdrTop + 6, 0xFF93D1AE);

		g.fillGradient(rightX, hdrTop, rightX + colW, listTop, 0xEE1A4223, 0xEE0F2916);
		g.fill(rightX, hdrTop, rightX + colW, hdrTop + 2, 0xFFFFCA62);
		g.drawString(this.font, "Wars", rightX + 8, hdrTop + 6, 0xFFFFD479);
		String wCount = snap.wars().size() + " players";
		g.drawString(this.font, wCount, rightX + colW - 8 - this.font.width(wCount), hdrTop + 6, 0xFFD9C798);

		renderEntryList(g, LEFT_COL_X, listTop, snap.raids(), raidsScroll, true, true);
		renderEntryList(g, RIGHT_COL_X, listTop, snap.wars(), warsScroll, true, true);

		int controlsLeft = archiveOpen ? (this.width - 256) : (this.width - 120);
		int maxTextW = Math.max(80, controlsLeft - 24);
		String totals = "Totals: " + snap.totalRaids() + " raids  •  " + snap.totalWars() + " wars  •  " + snap.totalTickets() + " tickets";
		g.drawString(this.font, trimToWidth(totals, maxTextW), 16, footerTop + 5, 0xFFFFE082);
		if (!snap.raffleWinners().isEmpty()) {
			StringBuilder raffle = new StringBuilder("Raffle Top 3: ");
			List<CycleLeaderboardData.RaffleWinner> top = snap.raffleWinners().subList(0, Math.min(3, snap.raffleWinners().size()));
			for (int i = 0; i < top.size(); i++) {
				if (i > 0) {
					raffle.append(", ");
				}
				raffle.append("#").append(i + 1).append(" ").append(top.get(i).name()).append(" (").append(top.get(i).tickets()).append(")");
			}
			g.drawString(this.font, trimToWidth(raffle.toString(), maxTextW), 16, footerTop + 17, 0xFFE2C6FF);
		}
	}

	private void renderLifetimeTab(GuiGraphics g) {
		CycleLeaderboardData.LifetimeSnapshot snap = mod != null ? mod.knownLifetimeLeaderboard() : null;
		String err = mod != null ? mod.recentLifetimeLeaderboardError() : "";

		String sub = "All-Time Records";
		if (snap != null && snap.hasInterval()) {
			String toLabel = (snap.toDays() == null || snap.toDays() <= 0.0) ? "now" : ("-" + snap.toDays() + "d");
			sub = "Filtered Window: -" + snap.fromDays() + "d to " + toLabel;
		}
		g.drawString(this.font, sub, 60, 29, 0xFFA8EBC2);

		// Shaded emerald filter bar
		int barRight = this.width - 14;
		g.fillGradient(14, 58, barRight, 84, 0xD80D2B1B, 0xD8081C11);
		g.fill(14, 58, barRight, 59, 0xFF287549);
		g.fill(14, 83, barRight, 84, 0xFF1D5937);
		g.fill(14, 58, 15, 84, 0xFF236841);
		g.fill(barRight - 1, 58, barRight, 84, 0xFF236841);

		g.drawString(this.font, "From:", 20, 67, 0xFFA8EBC2);
		g.drawString(this.font, "To:", 134, 67, 0xFFA8EBC2);
		if (!validationError.isEmpty()) {
			g.drawString(this.font, trimToWidth(validationError, Math.max(80, this.width - 490)), 346, 67, 0xFFFF6B6B);
		}

		// Footer strip
		int footerTop = this.height - 36;
		g.fillGradient(8, footerTop, this.width - 8, this.height - 6, 0xEB0E2E1C, 0xEB07190F);
		g.fill(8, footerTop, this.width - 8, footerTop + 1, 0xFF319A5D);
		g.drawString(this.font, showFinishes ? "Cycle Finishes: cumulative #1 / #2 / #3 placements across concluded cycles" : (snap != null && snap.hasInterval() ? "Custom day interval active (medals disabled)" : "All-time cumulative completions"), 16, footerTop + 11, 0xFF8CD1A8);

		int centerY = this.height / 2 + 12;
		if (!err.isEmpty()) {
			g.drawCenteredString(this.font, err, this.width / 2, centerY, 0xFFFF6B6B);
			return;
		}
		if (snap == null) {
			g.drawCenteredString(this.font, "Loading leaderboard stats...", this.width / 2, centerY, 0xFFA8EBC2);
			return;
		}

		int listTop = listTopOffset();
		int colW = Math.max(180, (this.width - 42) / 2);
		int leftX = 14;
		int rightX = 14 + colW + 14;
		int hdrTop = listTop - 20;

		if (showFinishes) {
			g.fillGradient(leftX, hdrTop, leftX + colW, listTop, 0xEE14472D, 0xEE0C2C1B);
			g.fill(leftX, hdrTop, leftX + colW, hdrTop + 2, 0xFF42E695);
			g.drawString(this.font, "Raids Cycle Finishes", leftX + 8, hdrTop + 6, 0xFF7CFFD1);

			g.fillGradient(rightX, hdrTop, rightX + colW, listTop, 0xEE1A4223, 0xEE0F2916);
			g.fill(rightX, hdrTop, rightX + colW, hdrTop + 2, 0xFFFFCA62);
			g.drawString(this.font, "Wars Cycle Finishes", rightX + 8, hdrTop + 6, 0xFFFFD479);

			renderPodiumList(g, LEFT_COL_X, listTop, snap.raidFinishes(), raidsScroll);
			renderPodiumList(g, RIGHT_COL_X, listTop, snap.warFinishes(), warsScroll);
			return;
		}

		String subtitle = snap.hasInterval() ? " (Filtered: no medals)" : "";
		g.fillGradient(leftX, hdrTop, leftX + colW, listTop, 0xEE14472D, 0xEE0C2C1B);
		g.fill(leftX, hdrTop, leftX + colW, hdrTop + 2, 0xFF42E695);
		g.drawString(this.font, "Raids" + subtitle, leftX + 8, hdrTop + 6, 0xFF7CFFD1);

		g.fillGradient(rightX, hdrTop, rightX + colW, listTop, 0xEE1A4223, 0xEE0F2916);
		g.fill(rightX, hdrTop, rightX + colW, hdrTop + 2, 0xFFFFCA62);
		g.drawString(this.font, "Wars" + subtitle, rightX + 8, hdrTop + 6, 0xFFFFD479);

		boolean showMedals = !snap.hasInterval();
		renderEntryList(g, LEFT_COL_X, listTop, snap.raids(), raidsScroll, showMedals, false);
		renderEntryList(g, RIGHT_COL_X, listTop, snap.wars(), warsScroll, showMedals, false);
	}

	private void renderEntryList(GuiGraphics g, int baseColX, int baseTopY, List<CycleLeaderboardData.LeaderboardEntry> entries, int scrollOffset, boolean showMedals, boolean showDelta) {
		int colW = Math.max(180, (this.width - 42) / 2);
		int x = baseColX == LEFT_COL_X ? 14 : (14 + colW + 14);
		int listW = colW - 8;
		int bottomLimit = Math.max(baseTopY + ROW_PITCH * 4 + 4, this.height - 42);
		int visRows = Math.max(4, (bottomLimit - baseTopY - 4) / ROW_PITCH);
		int height = visRows * ROW_PITCH + 4;

		// Shaded card body + border
		g.fill(x, baseTopY, x + colW, baseTopY + height, 0xB806140C);
		g.fill(x, baseTopY, x + colW, baseTopY + 1, 0xFF246A43);
		g.fill(x, baseTopY + height - 1, x + colW, baseTopY + height, 0xFF1A4F32);
		g.fill(x, baseTopY, x + 1, baseTopY + height, 0xFF246A43);
		g.fill(x + colW - 1, baseTopY, x + colW, baseTopY + height, 0xFF1A4F32);

		int total = entries.size();
		int clamped = clampOffset(scrollOffset, total);
		g.enableScissor(x + 1, baseTopY + 1, x + listW, baseTopY + height - 1);
		for (int vis = 0; vis < visRows; vis++) {
			int idx = clamped + vis;
			if (idx >= total) {
				break;
			}
			CycleLeaderboardData.LeaderboardEntry entry = entries.get(idx);
			int rowY = baseTopY + 2 + vis * ROW_PITCH;
			int rowBg;
			int accentBar = 0;
			if (entry.count() <= 0) {
				rowBg = ZERO_ROW_OVERLAY;
				accentBar = 0xFFFF5555;
			} else if (entry.rank() == 1) {
				rowBg = 0x623F330F;
				accentBar = 0xFFFFD700;
			} else if (entry.rank() == 2) {
				rowBg = 0x52253633;
				accentBar = 0xFFD4E0EA;
			} else if (entry.rank() == 3) {
				rowBg = 0x523B2616;
				accentBar = 0xFFDE9151;
			} else {
				rowBg = (idx & 1) == 0 ? ROW_FILL : 0x3A0A1C12;
			}
			g.fill(x + 3, rowY, x + listW - 2, rowY + ROW_HEIGHT, rowBg);
			if (accentBar != 0) {
				g.fill(x + 3, rowY, x + 5, rowY + ROW_HEIGHT, accentBar);
			}

			int cursorX = x + 8;
			if (showDelta) {
				Integer deltaVal = entry.delta();
				String deltaText = deltaVal == null || deltaVal == 0 ? "-" : (deltaVal > 0 ? "+" + deltaVal : String.valueOf(deltaVal));
				int deltaColor = deltaVal == null || deltaVal == 0 ? 0xFF729E84 : (deltaVal > 0 ? 0xFF55FF88 : 0xFFFF6B6B);
				g.drawString(this.font, deltaText, cursorX, rowY + 4, deltaColor);
				cursorX += 24;
			}

			if (this.minecraft != null) {
				PlayerSkin skin = PlayerSkinResolver.skinFor(this.minecraft, parseUuid(entry.uuid()), entry.name());
				if (skin != null) {
					PlayerFaceRenderer.draw(g, skin, cursorX, rowY + 3, 10);
				}
			}
			cursorX += 14;

			int rankColor = switch (entry.rank()) {
				case 1 -> 0xFFFFD700;
				case 2 -> 0xFFD4E0EA;
				case 3 -> 0xFFDE9151;
				default -> 0xFF83B89A;
			};
			String rankStr = "#" + entry.rank();
			g.drawString(this.font, rankStr, cursorX, rowY + 4, entry.count() <= 0 ? 0xFFCCCCCC : rankColor);
			cursorX += 22;

			int medal = CycleLeaderboardData.medalForEntry(entry, showMedals);
			if (medal >= 1 && medal <= 3) {
				drawTexture(g, MEDAL_TEXTURES[medal - 1], cursorX, rowY + 2, 12, 12);
				cursorX += 15;
			}

			String countText = String.valueOf(entry.count());
			int countWidth = this.font.width(countText);
			int countX = x + listW - 8 - countWidth;
			int maxNameW = Math.max(24, countX - cursorX - 6);
			int textColor = entry.count() <= 0 ? 0xFFE0DDDD : 0xFFF2FFF7;
			int countColor = entry.count() <= 0 ? 0xFFE0A0A0 : 0xFF98FFBF;
			g.drawString(this.font, trimToWidth(entry.name(), maxNameW), cursorX, rowY + 4, textColor);
			g.drawString(this.font, countText, countX, rowY + 4, countColor);
		}
		g.disableScissor();

		// Emerald custom scrollbar
		int sbX = x + colW - SCROLLBAR_WIDTH - 2;
		g.fill(sbX, baseTopY + 2, sbX + SCROLLBAR_WIDTH, baseTopY + height - 2, 0x44030D07);
		if (total > visRows && total > 0) {
			int sbH = height - 4;
			int thumbH = Math.max(18, Math.round(sbH * (visRows / (float) total)));
			int travel = Math.max(1, sbH - thumbH);
			int thumbY = baseTopY + 2 + Math.round((clamped / (float) (total - visRows)) * travel);
			g.fillGradient(sbX + 1, thumbY, sbX + SCROLLBAR_WIDTH - 1, thumbY + thumbH, 0xFF36BF72, 0xFF207A46);
			g.fill(sbX + 1, thumbY, sbX + SCROLLBAR_WIDTH - 1, thumbY + 1, 0xFF8CFFBE);
		}
	}

	private void renderPodiumList(GuiGraphics g, int baseColX, int baseTopY, List<CycleLeaderboardData.PodiumEntry> entries, int scrollOffset) {
		int colW = Math.max(180, (this.width - 42) / 2);
		int x = baseColX == LEFT_COL_X ? 14 : (14 + colW + 14);
		int listW = colW - 8;
		int bottomLimit = Math.max(baseTopY + ROW_PITCH * 4 + 4, this.height - 42);
		int visRows = Math.max(4, (bottomLimit - baseTopY - 4) / ROW_PITCH);
		int height = visRows * ROW_PITCH + 4;

		g.fill(x, baseTopY, x + colW, baseTopY + height, 0xB806140C);
		g.fill(x, baseTopY, x + colW, baseTopY + 1, 0xFF246A43);
		g.fill(x, baseTopY + height - 1, x + colW, baseTopY + height, 0xFF1A4F32);
		g.fill(x, baseTopY, x + 1, baseTopY + height, 0xFF246A43);
		g.fill(x + colW - 1, baseTopY, x + colW, baseTopY + height, 0xFF1A4F32);

		int total = entries.size();
		int clamped = clampOffset(scrollOffset, total);
		g.enableScissor(x + 1, baseTopY + 1, x + listW, baseTopY + height - 1);
		for (int vis = 0; vis < visRows; vis++) {
			int idx = clamped + vis;
			if (idx >= total) {
				break;
			}
			CycleLeaderboardData.PodiumEntry entry = entries.get(idx);
			int rowY = baseTopY + 2 + vis * ROW_PITCH;
			g.fill(x + 3, rowY, x + listW - 2, rowY + ROW_HEIGHT, (idx & 1) == 0 ? ROW_FILL : 0x3A0A1C12);

			int cursorX = x + 8;
			if (this.minecraft != null) {
				PlayerSkin skin = PlayerSkinResolver.skinFor(this.minecraft, parseUuid(entry.uuid()), entry.name());
				if (skin != null) {
					PlayerFaceRenderer.draw(g, skin, cursorX, rowY + 3, 10);
				}
			}
			cursorX += 14;

			int statsStart = x + listW - 96;
			int maxNameW = Math.max(24, statsStart - cursorX - 6);
			g.drawString(this.font, trimToWidth(entry.name(), maxNameW), cursorX, rowY + 4, 0xFFF2FFF7);

			int[] counts = {entry.first(), entry.second(), entry.third()};
			for (int m = 0; m < 3; m++) {
				int slotX = statsStart + m * 30;
				drawTexture(g, MEDAL_TEXTURES[m], slotX, rowY + 3, 10, 10);
				g.drawString(this.font, String.valueOf(counts[m]), slotX + 12, rowY + 4, 0xFF98FFBF);
			}
		}
		g.disableScissor();

		int sbX = x + colW - SCROLLBAR_WIDTH - 2;
		g.fill(sbX, baseTopY + 2, sbX + SCROLLBAR_WIDTH, baseTopY + height - 2, 0x44030D07);
		if (total > visRows && total > 0) {
			int sbH = height - 4;
			int thumbH = Math.max(18, Math.round(sbH * (visRows / (float) total)));
			int travel = Math.max(1, sbH - thumbH);
			int thumbY = baseTopY + 2 + Math.round((clamped / (float) (total - visRows)) * travel);
			g.fillGradient(sbX + 1, thumbY, sbX + SCROLLBAR_WIDTH - 1, thumbY + thumbH, 0xFF36BF72, 0xFF207A46);
			g.fill(sbX + 1, thumbY, sbX + SCROLLBAR_WIDTH - 1, thumbY + 1, 0xFF8CFFBE);
		}
	}

	private int listTopOffset() {
		return activeTab == Tab.CYCLE ? 80 : 110;
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
		int top = listTopOffset();
		int bottomLimit = Math.max(top + ROW_PITCH * 4 + 4, this.height - 42);
		int visRows = Math.max(4, (bottomLimit - top - 4) / ROW_PITCH);
		int max = Math.max(0, total - visRows);
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
		int srcSize = id.getPath().endsWith("icon.png") ? 256 : 64;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / (float) srcSize, h / (float) srcSize);
		g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0.0f, 0.0f, srcSize, srcSize, srcSize, srcSize);
		g.pose().popMatrix();
	}

	private boolean isInPane(double scaledX, double scaledY, int baseColX) {
		int colW = Math.max(180, (this.width - 42) / 2);
		int left = baseColX == LEFT_COL_X ? 14 : (14 + colW + 14);
		int top = listTopOffset();
		int bottomLimit = Math.max(top + ROW_PITCH * 4 + 4, this.height - 42);
		int visRows = Math.max(4, (bottomLimit - top - 4) / ROW_PITCH);
		int bottom = top + visRows * ROW_PITCH + 4;
		return scaledX >= left && scaledX <= left + colW && scaledY >= top && scaledY <= bottom;
	}

	private boolean isOverScrollbar(double scaledX, double scaledY, int baseColX) {
		int colW = Math.max(180, (this.width - 42) / 2);
		int colLeft = baseColX == LEFT_COL_X ? 14 : (14 + colW + 14);
		int sbX = colLeft + colW - SCROLLBAR_WIDTH - 2;
		int top = listTopOffset();
		int bottomLimit = Math.max(top + ROW_PITCH * 4 + 4, this.height - 42);
		int visRows = Math.max(4, (bottomLimit - top - 4) / ROW_PITCH);
		int bottom = top + visRows * ROW_PITCH + 4;
		return scaledX >= sbX - 2 && scaledX <= sbX + SCROLLBAR_WIDTH + 2 && scaledY >= top && scaledY <= bottom;
	}

	private void updateScrollFromMouse(double scaledY, int pane) {
		int top = listTopOffset();
		int bottomLimit = Math.max(top + ROW_PITCH * 4 + 4, this.height - 42);
		int visRows = Math.max(4, (bottomLimit - top - 4) / ROW_PITCH);
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
