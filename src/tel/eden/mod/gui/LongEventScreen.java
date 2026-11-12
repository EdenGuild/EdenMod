package tel.eden.mod.gui;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.ImagePreviewManager;
import tel.eden.mod.net.LongEventState;
import tel.eden.mod.raid.RaidGambitSafety;

/** Screen displaying Standard Missions and Special Missions for long events. */
public final class LongEventScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 480;
	private static final int BASE_PANEL_HEIGHT = 350;

	private static final String[] STANDARD_SLOTS_3 = {"daily", "special", "repeatable"};
	private static final String[] STANDARD_SLOTS_4 = {"daily", "special", "repeatable", "repeatable2"};
	private static final int[] STANDARD_CARD_BASE_X_3 = {16, 168, 320};
	private static final int STANDARD_CARD_BASE_W_3 = 144;
	private static final int[] STANDARD_CARD_BASE_X_4 = {12, 128, 244, 360};
	private static final int STANDARD_CARD_BASE_W_4 = 108;
	private static final int CARD_BASE_Y = 48;
	private static final int CARD_BASE_H = 92;

	private static final int[] SPECIAL_CARD_BASE_X = {13, 78, 143, 208, 273, 338, 403};
	private static final int SPECIAL_CARD_BASE_W = 62;
	private static final int SPECIAL_CARD_BASE_Y = 26;
	private static final int SPECIAL_CARD_BASE_H = 58;
	private static final String[] SPECIAL_SLOT_DEFAULTS = {"Scavenger Hunt", "Riddle", "Mob Hunt", "Murder Mystery", "Build Battle", "Doodle Battle", "Raid Challenge"};

	private static final SoundEvent CLICK_SOUND = SoundEvent.createVariableRangeEvent(Identifier.parse("minecraft:ui.button.click"));

	private static final Identifier FRAME_DAILY = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/frame_daily.png");
	private static final Identifier FRAME_WEEKLY = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/frame_weekly.png");
	private static final Identifier FRAME_REPEATABLE = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/frame_repeatable.png");
	private static final Identifier FRAME_MASTER = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/frame_master.png");

	private static final Identifier ICON_NOTG = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/notg.png");
	private static final Identifier ICON_NOL = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/nol.png");
	private static final Identifier ICON_TCC = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/tcc.png");
	private static final Identifier ICON_TNA = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/tna.png");
	private static final Identifier ICON_WTP = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/icons/wtp.png");
	private static final Identifier ICON_ALL_RAIDS = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/all_raids.png");
	private static final Identifier ICON_GUILD_WARS = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/guild_wars.png");
	private static final Identifier ICON_TRIVIA = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/trivia.png");
	private static final Identifier ICON_DICEROLL = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/diceroll.png");
	private static final Identifier ICON_COINFLIP = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/coinflip.png");
	private static final Identifier ICON_OTHER = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/event/other.png");
	private static final Identifier ICON_EDEN = Identifier.fromNamespaceAndPath("edenmod", "icon.png");

	private record ClickBox(int x, int y, int w, int h) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record ImageClickTarget(ClickBox box, String imageUrl) {
	}

	private final EdenModClient mod;
	private EdenPanelLayout layout;

	private static final class TimerRequest {
		final long targetMs;
		final int generation;
		int retries;
		long lastReqMs;

		TimerRequest(long targetMs, int generation, long lastReqMs) {
			this.targetMs = targetMs;
			this.generation = generation;
			this.retries = 0;
			this.lastReqMs = lastReqMs;
		}
	}

	private int seenGeneration = -1;
	private LongEventState state;
	private final Map<String, TimerRequest> timerRequests = new HashMap<>();
	private long lastStateRequestMs = 0L;
	private boolean rerollPending = false;
	private long rerollPendingSinceMs = 0L;
	private int rerollPendingGeneration = -1;

	private int activeTab = 0; // 0 = Standard Missions, 1 = Special Missions
	private int selectedSpecialSlot = 1; // 1 to 7
	private int currentSpecialImageIndex = 0;
	private ClickBox specialImageNavPrevBox = null;
	private ClickBox specialImageNavNextBox = null;
	private int scavengerScrollOffset = 0;
	private int mysteryClueScrollOffset = 0;
	private String hoveredClueNote = null;
	private String hoveredTooltipTitle = "Clue Details";
	private ClickBox helpBadgeBox = null;
	private ClickBox mysteryRerollBox = null;
	private boolean hoveredMysteryReroll = false;
	private boolean triviaGuessArmed = false;
	private boolean guessPending = false;
	private long guessPendingSinceMs = 0L;
	private boolean initialRequestSent = false;

	private EditBox guessEditBox;
	private Button submitGuessBtn;
	private final List<ImageClickTarget> imageClickTargets = new ArrayList<>();

	public LongEventScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Event"), parent);
		this.mod = mod;
	}

	@Override
	protected void init() {
		super.init();
		layout = centeredLayout(BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		this.addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(layout.x(160), layout.y(322), layout.w(160), layout.h(20)).build());

		String prevGuess = guessEditBox != null ? guessEditBox.getValue() : "";
		guessEditBox = new EditBox(this.font, layout.x(24), layout.y(278), layout.w(280), layout.h(20), Component.literal("Guess"));
		guessEditBox.setHint(Component.literal("Enter suspect/solution..."));
		guessEditBox.setMaxLength(64);
		guessEditBox.setValue(prevGuess);
		guessEditBox.visible = false;
		guessEditBox.active = false;
		this.addRenderableWidget(guessEditBox);

		submitGuessBtn = Button.builder(Component.literal("Submit Guess"), b -> submitGuess(false)).bounds(layout.x(312), layout.y(278), layout.w(118), layout.h(20)).build();
		submitGuessBtn.visible = false;
		submitGuessBtn.active = false;
		this.addRenderableWidget(submitGuessBtn);

		if (!initialRequestSent && mod.socket() != null && mod.socket().isConnected()) {
			initialRequestSent = true;
			lastStateRequestMs = System.currentTimeMillis();
			mod.socket().sendLongEventStateRequest();
		}

		refreshSnapshot();
	}

	private void submitGuess() {
		submitGuess(false);
	}

	private void submitGuess(boolean useTriviaOverride) {
		if (guessEditBox == null || mod.socket() == null || !mod.socket().isConnected()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (guessPending && now - guessPendingSinceMs < 5000L) {
			return;
		}
		String guess = guessEditBox.getValue().trim();
		if (guess.isEmpty()) {
			return;
		}
		if (state == null) {
			return;
		}
		LongEventState.SpecialSlot slot = state.findSpecialSlot(4);
		if (slot == null || !slot.opened() || slot.completed()) {
			return;
		}
		boolean free = slot.canGuessFree() || (slot.nextGuessAtMs() > 0L && now >= slot.nextGuessAtMs());
		boolean freeExtra = state.rerollCost() == -1;
		boolean canUseTrivia = slot.canGuessTrivia() || freeExtra || state.triviaPoints() >= 1;
		boolean useTrivia = (useTriviaOverride || (!free && triviaGuessArmed)) && canUseTrivia;
		if (!free && !useTrivia) {
			return;
		}
		guessPending = true;
		guessPendingSinceMs = now;
		triviaGuessArmed = false;
		this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
		mod.socket().sendLongEventMysteryGuess(guess, useTrivia);
		guessEditBox.setValue("");
	}

	private void hideGuessWidgets() {
		triviaGuessArmed = false;
		if (guessEditBox != null) {
			guessEditBox.visible = false;
			guessEditBox.active = false;
			if (guessEditBox.isFocused()) {
				guessEditBox.setFocused(false);
			}
			if (this.getFocused() == guessEditBox) {
				this.setFocused(null);
			}
		}
		if (submitGuessBtn != null) {
			submitGuessBtn.visible = false;
			submitGuessBtn.active = false;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && guessEditBox != null && guessEditBox.isFocused() && guessEditBox.visible) {
			submitGuess(false);
			return true;
		}
		return super.keyPressed(event);
	}

	private void refreshSnapshot() {
		seenGeneration = mod.longEventGeneration();
		state = mod.knownLongEventState();
		guessPending = false;
		if (seenGeneration > rerollPendingGeneration) {
			rerollPending = false;
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (seenGeneration != mod.longEventGeneration()) {
			refreshSnapshot();
		}
		long now = System.currentTimeMillis();
		if (rerollPending && now - rerollPendingSinceMs > 5000L) {
			rerollPending = false;
		}
		if ((state == null || !state.isSnapshot()) && mod.socket() != null && mod.socket().isConnected()) {
			if (now - lastStateRequestMs > 3000L) {
				lastStateRequestMs = now;
				mod.socket().sendLongEventStateRequest();
			}
		}
		if (state != null && state.active() && mod.socket() != null && mod.socket().isConnected()) {
			boolean requestNeeded = false;
			for (LongEventState.Slot slot : state.slots()) {
				if (checkTimer(now, "slot:" + slot.slot(), slot.resetAtMs()) || checkTimer(now, "reroll:" + slot.slot(), slot.rerollAtMs())) {
					requestNeeded = true;
					break;
				}
			}
			if (!requestNeeded) {
				for (LongEventState.SpecialSlot ss : state.specialSlots()) {
					if (checkTimer(now, "special_unlock:" + ss.slotNum(), ss.unlockAtMs()) || checkTimer(now, "special_exp:" + ss.slotNum(), ss.expiresAtMs()) || checkTimer(now, "special_guess:" + ss.slotNum(), ss.nextGuessAtMs()) || checkTimer(now, "special_clue:" + ss.slotNum(), ss.nextClueAtMs())) {
						requestNeeded = true;
						break;
					}
					int itemIdx = 0;
					for (LongEventState.ScavengerItem it : ss.items()) {
						if (checkTimer(now, "scav_item:" + ss.slotNum() + ":" + (itemIdx++), it.unlockAtMs())) {
							requestNeeded = true;
							break;
						}
					}
					if (requestNeeded) {
						break;
					}
				}
			}
			if (requestNeeded) {
				mod.socket().sendLongEventStateRequest();
			}
		}
	}

	private boolean checkTimer(long now, String key, long targetMs) {
		if (targetMs > 0L && now >= targetMs) {
			TimerRequest req = timerRequests.get(key);
			if (req == null || req.targetMs != targetMs) {
				timerRequests.put(key, new TimerRequest(targetMs, seenGeneration, now));
				return true;
			}
			if (seenGeneration != req.generation) {
				return false;
			}
			if (req.retries < 2 && now - req.lastReqMs >= 3000L) {
				req.retries++;
				req.lastReqMs = now;
				return true;
			}
		}
		return false;
	}

	private String[] currentStandardSlots() {
		return (state != null && state.repeatSlot2Active()) ? STANDARD_SLOTS_4 : STANDARD_SLOTS_3;
	}

	private int standardCardBaseX(int slotIndex, int totalSlots) {
		return totalSlots == 4 ? STANDARD_CARD_BASE_X_4[slotIndex] : STANDARD_CARD_BASE_X_3[slotIndex];
	}

	private int standardCardBaseW(int totalSlots) {
		return totalSlots == 4 ? STANDARD_CARD_BASE_W_4 : STANDARD_CARD_BASE_W_3;
	}

	private ClickBox rerollBox(int slotIndex, int totalSlots) {
		if (layout == null || slotIndex < 0 || slotIndex >= totalSlots) {
			return null;
		}
		int cardX = layout.x(standardCardBaseX(slotIndex, totalSlots));
		int cardY = layout.y(CARD_BASE_Y);
		int cardW = layout.w(standardCardBaseW(totalSlots));
		int rerollSize = layout.w(14);
		int rx = cardX + cardW - rerollSize - layout.w(4);
		int ry = cardY + layout.h(4);
		return new ClickBox(rx, ry, rerollSize, rerollSize);
	}

	private ClickBox specialMiniCardBox(int slotIndex) {
		if (layout == null || slotIndex < 0 || slotIndex >= SPECIAL_CARD_BASE_X.length) {
			return null;
		}
		int cardX = layout.x(SPECIAL_CARD_BASE_X[slotIndex]);
		int cardY = layout.y(SPECIAL_CARD_BASE_Y);
		int cardW = layout.w(SPECIAL_CARD_BASE_W);
		int cardH = layout.h(SPECIAL_CARD_BASE_H);
		return new ClickBox(cardX, cardY, cardW, cardH);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);
		long nowMs = System.currentTimeMillis();

		LongEventState.Slot hoveredRerollSlot = null;
		String hoveredRerollSlotName = null;
		hoveredMysteryReroll = false;
		String hoveredImageUrl = null;
		hoveredClueNote = null;
		hoveredTooltipTitle = "Clue Details";
		helpBadgeBox = null;
		mysteryRerollBox = null;

		imageClickTargets.clear();
		specialImageNavPrevBox = null;
		specialImageNavNextBox = null;

		// Update Murder Mystery guess widgets visibility & state
		boolean showGuessWidgets = activeTab == 1 && selectedSpecialSlot == 4 && state != null && state.active();
		if (showGuessWidgets) {
			LongEventState.SpecialSlot slot4 = state.findSpecialSlot(4);
			boolean slot4Eligible = slot4 != null && slot4.opened() && !slot4.completed();
			if (slot4Eligible) {
				guessEditBox.visible = true;
				guessEditBox.active = true;
				submitGuessBtn.visible = true;
				if (guessPending && nowMs - guessPendingSinceMs < 5000L) {
					submitGuessBtn.setMessage(Component.literal("Submitting..."));
					submitGuessBtn.active = false;
				} else {
					if (guessPending) {
						guessPending = false;
					}
					boolean free = slot4.canGuessFree() || (slot4.nextGuessAtMs() > 0L && nowMs >= slot4.nextGuessAtMs());
					boolean freeExtra = state.rerollCost() == -1;
					boolean canUseTrivia = slot4.canGuessTrivia() || freeExtra || state.triviaPoints() >= 1;
					if (!canUseTrivia) {
						triviaGuessArmed = false;
					}
					boolean hasText = !guessEditBox.getValue().trim().isEmpty();
					boolean canSubmit = mod.socket() != null && mod.socket().isConnected() && hasText;
					if (free) {
						triviaGuessArmed = false;
						submitGuessBtn.setMessage(Component.literal("Submit Guess"));
						submitGuessBtn.active = canSubmit;
					} else if (triviaGuessArmed) {
						submitGuessBtn.setMessage(Component.literal(freeExtra ? "Submit (Free)" : "Submit (1 Trivia Pt)"));
						submitGuessBtn.active = canSubmit;
					} else {
						long rem = slot4.nextGuessAtMs() > nowMs ? slot4.nextGuessAtMs() - nowMs : 0L;
						submitGuessBtn.setMessage(Component.literal("Cooldown (" + LongEventState.formatCountdown(rem, false) + ")"));
						submitGuessBtn.active = false;
					}
				}
			} else {
				hideGuessWidgets();
			}
		} else {
			hideGuessWidgets();
		}

		pushReferencePose(g);
		layout.drawPanel(g);

		// Header: Pending LE on left, Ends in on right
		if (state != null && state.active()) {
			String pendingText = "Pending: " + state.pendingLe() + " LE";
			g.drawString(this.font, pendingText, layout.x(12), layout.y(10), 0xFFFFD24A);
			if (state.endsAtMs() > 0L) {
				long rem = state.endsAtMs() - nowMs;
				String endsText = rem > 0L ? "Ends in " + LongEventState.formatCountdown(rem, true) : "Ended";
				g.drawString(this.font, endsText, layout.x(BASE_PANEL_WIDTH - 12) - this.font.width(endsText), layout.y(10), 0xFFCCCCCC);
			}
		}

		// Top tab switcher (Missions vs ✦ Special Missions ✦)
		int tab0X = layout.x(140);
		int tab0Y = layout.y(6);
		int tab0W = layout.w(70);
		int tab0H = layout.h(16);

		int tab1X = layout.x(216);
		int tab1Y = layout.y(6);
		int tab1W = layout.w(134);
		int tab1H = layout.h(16);

		ClickBox tab0Box = new ClickBox(tab0X, tab0Y, tab0W, tab0H);
		ClickBox tab1Box = new ClickBox(tab1X, tab1Y, tab1W, tab1H);

		boolean tab0Hover = tab0Box.contains(scaledMouseX, scaledMouseY);
		int t0Bg = activeTab == 0 ? 0x992B2B2B : (tab0Hover ? 0x66333333 : 0x44161616);
		int t0Border = activeTab == 0 ? 0xFFFFD24A : (tab0Hover ? 0xFF888888 : 0xFF444444);
		g.fill(tab0X, tab0Y, tab0X + tab0W, tab0Y + tab0H, t0Bg);
		drawOutline(g, tab0X, tab0Y, tab0W, tab0H, t0Border);
		int t0Fg = activeTab == 0 ? 0xFFFFFFFF : 0xFFAAAAAA;
		g.drawCenteredString(this.font, "Missions", tab0X + tab0W / 2, tab0Y + (tab0H - 8) / 2, t0Fg);

		boolean tab1Hover = tab1Box.contains(scaledMouseX, scaledMouseY);
		int t1Bg = activeTab == 1 ? 0x992B2B2B : (tab1Hover ? 0x66333333 : 0x44161616);
		int t1Border = activeTab == 1 ? 0xFFFFD24A : (tab1Hover ? 0xFF888888 : 0xFF444444);
		g.fill(tab1X, tab1Y, tab1X + tab1W, tab1Y + tab1H, t1Bg);
		drawOutline(g, tab1X, tab1Y, tab1W, tab1H, t1Border);
		String tab1Text = "✦ Special Missions ✦";
		int t1TextW = this.font.width(tab1Text);
		int t1TextX = tab1X + (tab1W - t1TextW) / 2;
		int t1TextY = tab1Y + (tab1H - 8) / 2;
		drawRainbowWaveString(g, tab1Text, t1TextX, t1TextY, nowMs);

		// Help badge [?]
		int helpW = layout.w(18);
		int helpH = layout.h(18);
		int helpX = layout.x(449);
		int helpY = layout.y(323);
		helpBadgeBox = new ClickBox(helpX, helpY, helpW, helpH);
		boolean helpHover = helpBadgeBox.contains(scaledMouseX, scaledMouseY);
		g.fill(helpX, helpY, helpX + helpW, helpY + helpH, helpHover ? 0x66444444 : 0x44161616);
		drawOutline(g, helpX, helpY, helpW, helpH, helpHover ? 0xFFFFD24A : 0xFF555555);
		g.drawCenteredString(this.font, "?", helpX + helpW / 2, helpY + (helpH - 8) / 2, helpHover ? 0xFFFFD24A : 0xFFAAAAAA);

		// Error line banner if present
		String recentError = mod.recentLongEventError();
		if (!recentError.isEmpty()) {
			drawFittedCenteredString(g, recentError, layout.centerX(), layout.y(314), layout.w(300), 0xFFFF5555);
		}

		if (seenGeneration == 0 || state == null) {
			g.drawCenteredString(this.font, "Loading…", layout.centerX(), layout.y(155), 0xFFAAAAAA);
		} else if (!state.active()) {
			g.drawCenteredString(this.font, "No event is running", layout.centerX(), layout.y(155), 0xFFAAAAAA);
		} else if (activeTab == 0) {
			// Tab 0: Standard cards (Daily, Super, Repeatable 1, optional Repeatable 2)
			String[] slotsToRender = currentStandardSlots();
			int totalSlots = slotsToRender.length;
			for (int i = 0; i < totalSlots; i++) {
				String slotKey = slotsToRender[i];
				LongEventState.Slot slot = state.findSlot(slotKey);

				int cardX = layout.x(standardCardBaseX(i, totalSlots));
				int cardY = layout.y(CARD_BASE_Y);
				int cardW = layout.w(standardCardBaseW(totalSlots));
				int cardH = layout.h(CARD_BASE_H);
				int cardCenterX = cardX + cardW / 2;

				String slotLabel;
				if (i == 0) {
					if (slot != null && slot.maxCompletions() > 1) {
						slotLabel = "Daily (" + slot.completions() + "/" + slot.maxCompletions() + ")";
					} else {
						slotLabel = "Daily";
					}
				} else if (i == 1) {
					slotLabel = "Super";
				} else if (i == 2) {
					slotLabel = totalSlots == 4 ? "Repeatable 1" : "Repeatable";
				} else {
					slotLabel = "Repeatable 2";
				}

				// Slot title + live countdown above card
				g.drawCenteredString(this.font, slotLabel, cardCenterX, layout.y(25), 0xFFFFD24A);
				String countdown = LongEventState.slotCountdownText(slotKey, slot != null ? slot.resetAtMs() : 0L, nowMs);
				drawFittedCenteredString(g, countdown, cardCenterX, layout.y(36), cardW - 4, 0xFFCCCCCC);

				boolean completed = slot != null && slot.completed();
				int borderColor = completed ? 0xFF3A8A3A : 0xFF4A4A4A;
				g.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x66161616);
				drawOutline(g, cardX, cardY, cardW, cardH, borderColor);

				// Frame + centred tag icon
				int frameSize = layout.w(42);
				int frameX = cardCenterX - frameSize / 2;
				int frameY = cardY + layout.h(4);
				Identifier tagIcon = iconForTag(slot != null ? slot.tag() : "Other");
				if (tagIcon != null) {
					int iconSize = layout.w(26);
					drawTexture(g, tagIcon, frameX + (frameSize - iconSize) / 2, frameY + (frameSize - iconSize) / 2, iconSize, iconSize);
				}
				Identifier frameTex = frameForSlot(slotKey);
				if (frameTex != null) {
					drawTexture(g, frameTex, frameX, frameY, frameSize, frameSize);
				}

				if (slot == null || slot.missionId() == 0) {
					g.drawCenteredString(this.font, "No mission", cardCenterX, cardY + layout.h(42), 0xFF888888);
					continue;
				}

				// Reroll icon button in top-right corner
				ClickBox rBox = rerollBox(i, totalSlots);
				int rerollSize = rBox.w();
				int rerollX = rBox.x();
				int rerollY = rBox.y();
				boolean canReroll = !rerollPending && slot.canReroll();
				boolean canBuyReroll = !rerollPending && slot.canBuyReroll();
				boolean rHover = rBox.contains(scaledMouseX, scaledMouseY);
				if (rHover) {
					hoveredRerollSlot = slot;
					hoveredRerollSlotName = slotKey;
				}
				boolean shiftHeld = RaidGambitSafety.isShiftKeyDown();
				int rBg;
				int rBorder;
				int rFg;
				if (canReroll) {
					rBg = rHover ? 0xFF484848 : 0xFF303030;
					rBorder = rHover ? 0xFFFFD24A : 0xFF999999;
					rFg = rHover ? 0xFFFFD24A : 0xFFFFFFFF;
				} else if (canBuyReroll && shiftHeld && rHover) {
					rBg = 0xFF203838;
					rBorder = 0xFF55FFFF;
					rFg = 0xFF55FFFF;
				} else {
					rBg = 0xFF1C1C1C;
					rBorder = 0xFF404040;
					rFg = 0xFF666666;
				}
				g.fill(rerollX, rerollY, rerollX + rerollSize, rerollY + rerollSize, rBg);
				drawOutline(g, rerollX, rerollY, rerollSize, rerollSize, rBorder);
				g.drawCenteredString(this.font, "⟳", rerollX + rerollSize / 2, rerollY + (rerollSize - 8) / 2, rFg);

				boolean isOther = "Other".equalsIgnoreCase(slot.tag());
				String displayTitle = slot.title();
				if (isOther && slot.missionId() > 0 && !displayTitle.contains("#" + slot.missionId())) {
					displayTitle = displayTitle + " #" + slot.missionId();
				}
				int innerW = cardW - layout.w(12);
				List<FormattedCharSequence> titleLines = this.font.split(Component.literal(displayTitle), Math.max(20, innerW));
				int titleY = cardY + layout.h(48);
				int maxTitleLines = Math.min(2, titleLines.size());
				for (int lineIdx = 0; lineIdx < maxTitleLines; lineIdx++) {
					FormattedCharSequence seq = titleLines.get(lineIdx);
					g.drawString(this.font, seq, cardCenterX - this.font.width(seq) / 2, titleY + lineIdx * 9, 0xFFFFFFFF);
				}

				// Progress & +N LE row
				int statY = cardY + layout.h(69);
				int padX = cardX + layout.w(6);
				int rightX = cardX + cardW - layout.w(6);
				String rewardStr = "+" + slot.rewardLe() + " LE";
				int rewardW = this.font.width(rewardStr);
				int maxLeftW = Math.max(10, rightX - padX - rewardW - 4);
				if (completed) {
					drawFittedString(g, "Completed", padX, statY, maxLeftW, 0xFF55FF55);
				} else {
					String progStr = slot.progress() + "/" + slot.target();
					drawFittedString(g, progStr, padX, statY, maxLeftW, 0xFFDDDDDD);
				}
				g.drawString(this.font, rewardStr, rightX - rewardW, statY, 0xFFFFD24A);

				// Progress bar
				int barY = cardY + layout.h(80);
				int barW = cardW - layout.w(12);
				int barH = Math.max(3, layout.h(5));
				float ratio = completed ? 1.0f : Math.clamp(slot.progress() / (float) Math.max(1, slot.target()), 0.0f, 1.0f);
				drawProgressBar(g, padX, barY, barW, barH, ratio, completed);
			}

			// Master section (up to 3 tracks: Raids, Guild Wars, Missions)
			List<LongEventState.Master> masters = state.masters();
			if (!masters.isEmpty()) {
				g.drawString(this.font, "Event Objectives", layout.x(12), layout.y(148), 0xFFFFD24A);
				Identifier masterFrame = FRAME_MASTER;
				for (int idx = 0; idx < Math.min(3, masters.size()); idx++) {
					LongEventState.Master master = masters.get(idx);
					int rowX = layout.x(12);
					int rowY = layout.y(162 + idx * 36);
					int rowW = layout.w(456);
					int rowH = layout.h(32);

					boolean mDone = master.completed();
					g.fill(rowX, rowY, rowX + rowW, rowY + rowH, 0x66161616);
					drawOutline(g, rowX, rowY, rowW, rowH, mDone ? 0xFF3A8A3A : 0xFF4A4A4A);

					int fSize = layout.w(26);
					int fx = rowX + layout.w(3);
					int fy = rowY + (rowH - fSize) / 2;
					Identifier mIcon = iconForMaster(master.kind());
					if (mIcon != null) {
						int iSize = layout.w(18);
						drawTexture(g, mIcon, fx + (fSize - iSize) / 2, fy + (fSize - iSize) / 2, iSize, iSize);
					}
					drawTexture(g, masterFrame, fx, fy, fSize, fSize);

					int textLeft = rowX + layout.w(34);
					int topY = rowY + layout.h(4);
					String displayTitle = switch (master.kind().toLowerCase(Locale.ROOT)) {
						case "raids" -> "All Raids";
						case "missions" -> "Mission Completions";
						case "wars", "guild_wars" -> "Guild Wars";
						default -> master.title().replace(" Master", "");
					};
					String tierText = "Tier " + master.tier() + "/" + master.tierCount();
					g.drawString(this.font, displayTitle, textLeft, topY, 0xFFFFFFFF);
					int afterTitle = textLeft + this.font.width(displayTitle) + 6;
					g.drawString(this.font, tierText, afterTitle, topY, 0xFF55FFFF);

					String earnedText = "Earned: " + master.earnedLe() + " LE";
					g.drawString(this.font, earnedText, rowX + rowW - layout.w(6) - this.font.width(earnedText), topY, 0xFFFFD24A);

					int barY = rowY + layout.h(18);
					int barW = layout.w(230);
					int barH = Math.max(3, layout.h(5));
					float ratio = mDone ? 1.0f : Math.clamp(master.progress() / (float) Math.max(1, master.target()), 0.0f, 1.0f);
					drawProgressBar(g, textLeft, barY, barW, barH, ratio, mDone);

					int statusX = textLeft + barW + layout.w(8);
					int statusY = rowY + layout.h(16);
					if (mDone) {
						g.drawString(this.font, "All tiers complete", statusX, statusY, 0xFF55FF55);
					} else {
						String mProg = master.progress() + "/" + master.target();
						g.drawString(this.font, mProg, statusX, statusY, 0xFFDDDDDD);
						g.drawString(this.font, "+" + master.rewardLe() + " LE", statusX + this.font.width(mProg) + 8, statusY, 0xFFFFD24A);
					}
				}
			}
		} else {
			// Tab 1: Special Missions
			// Top row: mini-cards
			for (int i = 0; i < SPECIAL_CARD_BASE_X.length; i++) {
				int slotNum = i + 1;
				LongEventState.SpecialSlot s = state.findSpecialSlot(slotNum);
				int cardX = layout.x(SPECIAL_CARD_BASE_X[i]);
				int cardY = layout.y(SPECIAL_CARD_BASE_Y);
				int cardW = layout.w(SPECIAL_CARD_BASE_W);
				int cardH = layout.h(SPECIAL_CARD_BASE_H);
				int cardCenterX = cardX + cardW / 2;

				boolean selected = selectedSpecialSlot == slotNum;
				ClickBox mBox = new ClickBox(cardX, cardY, cardW, cardH);
				boolean hovered = mBox.contains(scaledMouseX, scaledMouseY);

				int bg = selected ? 0x992B2B2B : (hovered ? 0x66222222 : 0x55161616);
				int border = selected ? 0xFFFFD24A : (hovered ? 0xFF888888 : ((s != null && s.completed()) ? 0xFF3A8A3A : 0xFF444444));
				g.fill(cardX, cardY, cardX + cardW, cardY + cardH, bg);
				drawOutline(g, cardX, cardY, cardW, cardH, border);

				String title = (s != null && !s.title().isEmpty()) ? s.title() : SPECIAL_SLOT_DEFAULTS[i];
				drawFittedCenteredString(g, title, cardCenterX, cardY + layout.h(4), cardW - 6, 0xFFFFFFFF);

				String badgeText;
				int badgeColor;
				if (s != null && s.completed()) {
					badgeText = "Completed ✔";
					badgeColor = 0xFF55FF55;
				} else if (s != null && s.unlockAtMs() > 0 && nowMs < s.unlockAtMs()) {
					badgeText = "Locked ⏳";
					badgeColor = 0xFFAAAAAA;
				} else if (s != null && !s.opened() && s.raidRequirement() > 0) {
					badgeText = "Raids: " + s.raidProgress() + "/" + s.raidRequirement() + " ⚔";
					badgeColor = 0xFFFFB833;
				} else if (s != null && s.opened()) {
					badgeText = "Open ✦";
					badgeColor = 0xFF55FFFF;
				} else {
					badgeText = "Locked ⏳";
					badgeColor = 0xFFAAAAAA;
				}
				drawFittedCenteredString(g, badgeText, cardCenterX, cardY + layout.h(22), cardW - 6, badgeColor);

				int reward = s != null ? s.rewardLe() : 0;
				String rewardText = "+" + reward + " LE";
				drawFittedCenteredString(g, rewardText, cardCenterX, cardY + layout.h(38), cardW - 6, 0xFFFFD24A);
			}

			// Detail View (y=90 to y=310)
			int dX = layout.x(13);
			int dY = layout.y(90);
			int dW = layout.w(454);
			int dH = layout.h(220);
			g.fill(dX, dY, dX + dW, dY + dH, 0x66161616);
			drawOutline(g, dX, dY, dW, dH, 0xFF4A4A4A);

			LongEventState.SpecialSlot currentSpecial = state.findSpecialSlot(selectedSpecialSlot);
			if (currentSpecial != null && currentSpecial.unlockAtMs() > 0 && nowMs < currentSpecial.unlockAtMs()) {
				long rem = currentSpecial.unlockAtMs() - nowMs;
				String unlockStr = "Unlocks in " + LongEventState.formatCountdown(rem, true);
				g.drawCenteredString(this.font, "🔒 Mission Locked", dX + dW / 2, dY + layout.h(85), 0xFFAAAAAA);
				g.drawCenteredString(this.font, unlockStr, dX + dW / 2, dY + layout.h(105), 0xFFFFD24A);
			} else if (currentSpecial != null && !currentSpecial.opened() && currentSpecial.raidRequirement() > 0) {
				String raidReq = "Complete Raids to Unlock: " + currentSpecial.raidProgress() + " / " + currentSpecial.raidRequirement() + " Raids";
				g.drawCenteredString(this.font, "⚔ Raid Requirement", dX + dW / 2, dY + layout.h(70), 0xFFFFB833);
				g.drawCenteredString(this.font, raidReq, dX + dW / 2, dY + layout.h(90), 0xFFFFFFFF);
				int barW = layout.w(240);
				int barH = layout.h(8);
				int barX = dX + (dW - barW) / 2;
				int barY = dY + layout.h(110);
				float ratio = Math.clamp(currentSpecial.raidProgress() / (float) Math.max(1, currentSpecial.raidRequirement()), 0.0f, 1.0f);
				drawProgressBar(g, barX, barY, barW, barH, ratio, false);
			} else if (currentSpecial == null || !currentSpecial.opened()) {
				g.drawCenteredString(this.font, "🔒 Locked", dX + dW / 2, dY + layout.h(100), 0xFFAAAAAA);
			} else {
				switch (selectedSpecialSlot) {
					case 1 -> renderScavengerHuntDetail(g, dX, dY, dW, dH, currentSpecial, nowMs, scaledMouseX, scaledMouseY);
					case 2 -> renderRiddleDetail(g, dX, dY, dW, dH, currentSpecial, nowMs, scaledMouseX, scaledMouseY);
					case 3 -> renderMobHuntDetail(g, dX, dY, dW, dH, currentSpecial, nowMs, scaledMouseX, scaledMouseY);
					case 4 -> renderMurderMysteryDetail(g, dX, dY, dW, dH, currentSpecial, nowMs, scaledMouseX, scaledMouseY);
					case 5 -> renderPromptDetail(g, dX, dY, dW, dH, currentSpecial, "Build Battle", nowMs, scaledMouseX, scaledMouseY);
					case 6 -> renderPromptDetail(g, dX, dY, dW, dH, currentSpecial, "Doodle Battle", nowMs, scaledMouseX, scaledMouseY);
					case 7 -> renderPromptDetail(g, dX, dY, dW, dH, currentSpecial, "Raid Challenge", nowMs, scaledMouseX, scaledMouseY);
				}
			}
		}

		super.render(g, scaledMouseX, scaledMouseY, delta);
		popReferencePose(g);

		// Check hovered image target for tooltip preview
		for (ImageClickTarget target : imageClickTargets) {
			if (target.box().contains(scaledMouseX, scaledMouseY)) {
				hoveredImageUrl = target.imageUrl();
				break;
			}
		}

		// Render hover tooltips / image previews in native screen space
		if (hoveredRerollSlotName != null) {
			g.setComponentTooltipForNextFrame(this.font, buildRerollTooltip(hoveredRerollSlotName, hoveredRerollSlot, nowMs), mouseX, mouseY);
		} else if (hoveredMysteryReroll) {
			LongEventState.SpecialSlot slot4 = (state != null) ? state.findSpecialSlot(4) : null;
			g.setComponentTooltipForNextFrame(this.font, buildMysteryRerollTooltip(slot4, nowMs), mouseX, mouseY);
		} else if (helpBadgeBox != null && helpBadgeBox.contains(scaledMouseX, scaledMouseY)) {
			g.setComponentTooltipForNextFrame(this.font, List.of(Component.literal("Need help? Ask an Event Manager for clarification.").withStyle(ChatFormatting.WHITE)), mouseX, mouseY);
		} else if (hoveredImageUrl != null) {
			g.setComponentTooltipForNextFrame(this.font, List.of(Component.literal("Click to open image in browser").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
		} else if (hoveredClueNote != null && !hoveredClueNote.isEmpty()) {
			List<Component> clueLines = new ArrayList<>();
			String title = (hoveredTooltipTitle != null && !hoveredTooltipTitle.isEmpty()) ? hoveredTooltipTitle : "Clue Details";
			clueLines.add(Component.literal(title).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			for (String l : wrapWords(hoveredClueNote, 260)) {
				clueLines.add(Component.literal(l).withStyle(ChatFormatting.WHITE));
			}
			g.setComponentTooltipForNextFrame(this.font, clueLines, mouseX, mouseY);
		}
	}

	private List<String> getSpecialSlotImages(LongEventState.SpecialSlot slot) {
		if (slot == null) {
			return List.of();
		}
		Set<String> set = new LinkedHashSet<>();
		addSplitUrls(set, slot.imageUrl());
		for (LongEventState.ClueEntry ce : slot.clues()) {
			addSplitUrls(set, ce.imageUrl());
		}
		for (LongEventState.ScavengerItem it : slot.items()) {
			if (it.unlocked()) {
				addSplitUrls(set, it.imageUrl());
			}
		}
		return new ArrayList<>(set);
	}

	private void addSplitUrls(Set<String> set, String raw) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		for (String part : raw.split("[,\\s]+")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty() && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
				set.add(trimmed);
			}
		}
	}

	private void renderSpecialImageSection(GuiGraphics g, int boxX, int boxY, int boxW, int boxH, List<String> images, int mouseX, int mouseY) {
		g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0x44141414);
		drawOutline(g, boxX, boxY, boxW, boxH, 0xFF383838);

		if (images.isEmpty()) {
			g.drawCenteredString(this.font, "No Image Attached", boxX + boxW / 2, boxY + (boxH - 8) / 2, 0xFFAAAAAA);
			return;
		}

		int count = images.size();
		int idx = (currentSpecialImageIndex % count + count) % count;
		currentSpecialImageIndex = idx;
		String currentUrl = images.get(idx);

		if (count > 1) {
			int headerY = boxY + layout.h(4);
			String counter = (idx + 1) + " / " + count;
			int btnH = 11;
			int nextW = this.font.width("▶") + 6;
			int prevW = this.font.width("◀") + 6;
			int counterW = this.font.width(counter);
			int totalNavW = prevW + 4 + counterW + 4 + nextW;
			int navStartX = boxX + (boxW - totalNavW) / 2;
			int prevX = navStartX;
			int textX = prevX + prevW + 4;
			int nextX = textX + counterW + 4;

			specialImageNavPrevBox = new ClickBox(prevX, headerY - 1, prevW, btnH);
			specialImageNavNextBox = new ClickBox(nextX, headerY - 1, nextW, btnH);

			boolean prevHovered = specialImageNavPrevBox.contains(mouseX, mouseY);
			boolean nextHovered = specialImageNavNextBox.contains(mouseX, mouseY);

			g.fill(prevX, headerY - 1, prevX + prevW, headerY - 1 + btnH, prevHovered ? 0x99444444 : 0x55222222);
			drawOutline(g, prevX, headerY - 1, prevW, btnH, prevHovered ? 0xFFFFFFFF : 0xFF666666);
			g.drawString(this.font, "◀", prevX + 3, headerY, prevHovered ? 0xFFFFFFFF : 0xFFAAAAAA);

			g.drawString(this.font, counter, textX, headerY + 1, 0xFF55FFFF);

			g.fill(nextX, headerY - 1, nextX + nextW, headerY - 1 + btnH, nextHovered ? 0x99444444 : 0x55222222);
			drawOutline(g, nextX, headerY - 1, nextW, btnH, nextHovered ? 0xFFFFFFFF : 0xFF666666);
			g.drawString(this.font, "▶", nextX + 3, headerY, nextHovered ? 0xFFFFFFFF : 0xFFAAAAAA);
		}

		int imgAreaX = boxX + layout.w(6);
		int imgAreaY = boxY + layout.h(count > 1 ? 18 : 6);
		int imgAreaW = boxW - layout.w(12);
		int imgAreaH = boxH - layout.h(count > 1 ? 24 : 12);

		ImagePreviewManager.renderFitted(g, currentUrl, imgAreaX, imgAreaY, imgAreaW, imgAreaH);
		imageClickTargets.add(new ImageClickTarget(new ClickBox(imgAreaX, imgAreaY, imgAreaW, imgAreaH), currentUrl));
	}

	private void renderSpecialNoteSection(GuiGraphics g, int boxX, int boxY, int boxW, int boxH, String note) {
		g.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0x44141414);
		drawOutline(g, boxX, boxY, boxW, boxH, 0xFF383838);

		if (note != null && !note.isEmpty()) {
			List<String> lines = wrapWords(note, boxW - layout.w(16));
			int maxLines = Math.max(1, (boxH - layout.h(16)) / 11);
			for (int i = 0; i < Math.min(maxLines, lines.size()); i++) {
				g.drawString(this.font, lines.get(i), boxX + layout.w(8), boxY + layout.h(8 + i * 11), 0xFFDDDDDD);
			}
		}
	}

	private void renderScavengerHuntDetail(GuiGraphics g, int dX, int dY, int dW, int dH, LongEventState.SpecialSlot slot, long nowMs, int mouseX, int mouseY) {
		g.drawString(this.font, slot.title().isEmpty() ? "Scavenger Hunt" : slot.title(), dX + layout.w(10), dY + layout.h(8), 0xFFFFD24A);
		if (slot.expiresAtMs() > 0) {
			long rem = slot.expiresAtMs() - nowMs;
			String exp = rem > 0 ? "Ends in " + LongEventState.formatCountdown(rem, true) : "Ended";
			g.drawString(this.font, exp, dX + dW - layout.w(10) - this.font.width(exp), dY + layout.h(8), 0xFFCCCCCC);
		}
		if (!slot.note().isEmpty()) {
			drawFittedString(g, slot.note(), dX + layout.w(10), dY + layout.h(22), dW - layout.w(20), 0xFFBBBBBB);
		}

		List<String> images = getSpecialSlotImages(slot);
		List<LongEventState.ScavengerItem> items = slot.items();
		int startY = dY + layout.h(38);
		int rowH = layout.h(52);
		int gap = layout.h(4);
		int bodyH = dH - layout.h(48);

		if (!images.isEmpty()) {
			int colGap = layout.w(8);
			int colW = (dW - layout.w(20) - colGap) / 2;
			int imgX = dX + layout.w(10);
			int itemsX = imgX + colW + colGap;

			renderSpecialImageSection(g, imgX, startY, colW, bodyH, images, mouseX, mouseY);

			int maxScavScroll = Math.max(0, items.size() - 3);
			if (scavengerScrollOffset > maxScavScroll) {
				scavengerScrollOffset = maxScavScroll;
			}
			int visibleCount = Math.min(3, items.size() - scavengerScrollOffset);
			boolean hasScrollbar = items.size() > 3;
			int itemW = colW - layout.w(hasScrollbar ? 14 : 0);

			for (int i = 0; i < visibleCount; i++) {
				int itemIndex = scavengerScrollOffset + i;
				LongEventState.ScavengerItem it = items.get(itemIndex);
				int itemY = startY + i * (rowH + gap);

				g.fill(itemsX, itemY, itemsX + itemW, itemY + rowH, 0x44141414);
				drawOutline(g, itemsX, itemY, itemW, rowH, 0xFF383838);

				String diff = it.difficulty().isEmpty() ? "Item #" + (itemIndex + 1) : it.difficulty();
				int diffColor = switch (diff.toLowerCase(Locale.ROOT)) {
					case "easy" -> 0xFF55FF55;
					case "medium" -> 0xFFFFD24A;
					case "hard" -> 0xFFFF5555;
					default -> 0xFF55FFFF;
				};
				g.drawString(this.font, "[" + diff + "]", itemsX + layout.w(6), itemY + layout.h(6), diffColor);

				boolean itUnlocked = it.unlocked() && (it.unlockAtMs() <= 0L || nowMs >= it.unlockAtMs());
				if (!itUnlocked) {
					long rem = it.unlockAtMs() > nowMs ? it.unlockAtMs() - nowMs : 0L;
					String lockedText = rem > 0L ? "🔒 In " + LongEventState.formatCountdown(rem, true) : "🔒 Locked";
					g.drawString(this.font, lockedText, itemsX + layout.w(6), itemY + layout.h(22), 0xFFAAAAAA);
				} else {
					List<String> noteLines = wrapWords(it.note(), itemW - layout.w(12));
					for (int nl = 0; nl < Math.min(2, noteLines.size()); nl++) {
						g.drawString(this.font, noteLines.get(nl), itemsX + layout.w(6), itemY + layout.h(20 + nl * 10), 0xFFDDDDDD);
					}
					if (!it.imageUrl().isEmpty()) {
						renderImageLink(g, it.imageUrl(), itemsX + itemW - layout.w(48), itemY + layout.h(6), mouseX, mouseY);
					}
				}
			}
			if (hasScrollbar) {
				layout.drawScrollbar(g, itemsX + colW - layout.w(6), startY, layout.w(4), 3 * (rowH + gap) - gap, 3, items.size(), scavengerScrollOffset);
			}
		} else {
			int maxScavScroll = Math.max(0, items.size() - 3);
			if (scavengerScrollOffset > maxScavScroll) {
				scavengerScrollOffset = maxScavScroll;
			}
			int visibleCount = Math.min(3, items.size() - scavengerScrollOffset);
			boolean hasScrollbar = items.size() > 3;
			int itemW = dW - layout.w(hasScrollbar ? 26 : 20);

			for (int i = 0; i < visibleCount; i++) {
				int itemIndex = scavengerScrollOffset + i;
				LongEventState.ScavengerItem it = items.get(itemIndex);
				int itemY = startY + i * (rowH + gap);
				int itemX = dX + layout.w(10);

				g.fill(itemX, itemY, itemX + itemW, itemY + rowH, 0x44141414);
				drawOutline(g, itemX, itemY, itemW, rowH, 0xFF383838);

				String diff = it.difficulty().isEmpty() ? "Item #" + (itemIndex + 1) : it.difficulty();
				int diffColor = switch (diff.toLowerCase(Locale.ROOT)) {
					case "easy" -> 0xFF55FF55;
					case "medium" -> 0xFFFFD24A;
					case "hard" -> 0xFFFF5555;
					default -> 0xFF55FFFF;
				};
				g.drawString(this.font, "[" + diff + "]", itemX + layout.w(6), itemY + layout.h(6), diffColor);

				boolean itUnlocked = it.unlocked() && (it.unlockAtMs() <= 0L || nowMs >= it.unlockAtMs());
				if (!itUnlocked) {
					long rem = it.unlockAtMs() > nowMs ? it.unlockAtMs() - nowMs : 0L;
					String lockedText = rem > 0L ? "🔒 Unlocks in " + LongEventState.formatCountdown(rem, true) : "🔒 Locked";
					g.drawString(this.font, lockedText, itemX + layout.w(6), itemY + layout.h(22), 0xFFAAAAAA);
				} else {
					List<String> noteLines = wrapWords(it.note(), itemW - layout.w(60));
					for (int nl = 0; nl < Math.min(2, noteLines.size()); nl++) {
						g.drawString(this.font, noteLines.get(nl), itemX + layout.w(6), itemY + layout.h(20 + nl * 10), 0xFFDDDDDD);
					}
					if (!it.imageUrl().isEmpty()) {
						renderImageLink(g, it.imageUrl(), itemX + itemW - layout.w(48), itemY + layout.h(6), mouseX, mouseY);
					}
				}
			}
			if (hasScrollbar) {
				layout.drawScrollbar(g, dX + dW - layout.w(10), startY, layout.w(5), 3 * (rowH + gap) - gap, 3, items.size(), scavengerScrollOffset);
			}
		}
	}

	private void renderRiddleDetail(GuiGraphics g, int dX, int dY, int dW, int dH, LongEventState.SpecialSlot slot, long nowMs, int mouseX, int mouseY) {
		g.drawString(this.font, slot.title().isEmpty() ? "Riddle" : slot.title(), dX + layout.w(10), dY + layout.h(8), 0xFFFFD24A);
		if (slot.completed()) {
			g.drawString(this.font, "Solved ✔", dX + dW - layout.w(10) - this.font.width("Solved ✔"), dY + layout.h(8), 0xFF55FF55);
		}

		List<String> images = getSpecialSlotImages(slot);
		int bodyY = dY + layout.h(24);
		int bodyH = dH - layout.h(34);

		String clueNote = "";
		String clueImage = "";
		if (!slot.clues().isEmpty()) {
			LongEventState.ClueEntry ce = slot.clues().get(0);
			clueNote = ce.note();
			clueImage = ce.imageUrl();
		} else if (!slot.imageUrl().isEmpty() && images.isEmpty()) {
			clueImage = slot.imageUrl();
		}

		if (!images.isEmpty()) {
			int gap = layout.w(8);
			int colW = (dW - layout.w(20) - gap) / 2;
			int imgX = dX + layout.w(10);
			int rightX = imgX + colW + gap;
			renderSpecialImageSection(g, imgX, bodyY, colW, bodyH, images, mouseX, mouseY);

			int sectionH = (bodyH - layout.h(6)) / 2;

			// Riddle Box
			int rBoxY = bodyY;
			g.fill(rightX, rBoxY, rightX + colW, rBoxY + sectionH, 0x44141414);
			drawOutline(g, rightX, rBoxY, colW, sectionH, 0xFF383838);
			g.drawString(this.font, "Riddle:", rightX + layout.w(8), rBoxY + layout.h(6), 0xFFFFD24A);

			if (!slot.note().isEmpty()) {
				List<String> nLines = wrapWords(slot.note(), colW - layout.w(16));
				int maxLines = Math.max(1, (sectionH - layout.h(24)) / 10);
				for (int i = 0; i < Math.min(maxLines, nLines.size()); i++) {
					g.drawString(this.font, nLines.get(i), rightX + layout.w(8), rBoxY + layout.h(20 + i * 10), 0xFFDDDDDD);
				}
				if (mouseX >= rightX && mouseX < rightX + colW && mouseY >= rBoxY && mouseY < rBoxY + sectionH) {
					hoveredClueNote = slot.note();
					hoveredTooltipTitle = "Riddle";
				}
			}

			// Clue Box
			int cBoxY = rBoxY + sectionH + layout.h(6);
			g.fill(rightX, cBoxY, rightX + colW, cBoxY + sectionH, 0x44141414);
			drawOutline(g, rightX, cBoxY, colW, sectionH, 0xFF383838);
			g.drawString(this.font, "Clue:", rightX + layout.w(8), cBoxY + layout.h(6), 0xFF55FFFF);

			if (!clueNote.isEmpty()) {
				List<String> cLines = wrapWords(clueNote, colW - layout.w(16));
				int maxLines = Math.max(1, (sectionH - layout.h(24)) / 10);
				for (int i = 0; i < Math.min(maxLines, cLines.size()); i++) {
					g.drawString(this.font, cLines.get(i), rightX + layout.w(8), cBoxY + layout.h(20 + i * 10), 0xFFFFFFFF);
				}
				if (mouseX >= rightX && mouseX < rightX + colW && mouseY >= cBoxY && mouseY < cBoxY + sectionH) {
					hoveredClueNote = clueNote;
					hoveredTooltipTitle = "Clue Details";
				}
			}
		} else {
			int boxX = dX + layout.w(10);
			int boxW = dW - layout.w(20);
			int sectionH = (bodyH - layout.h(6)) / 2;

			// Riddle Box
			int rBoxY = bodyY;
			g.fill(boxX, rBoxY, boxX + boxW, rBoxY + sectionH, 0x44141414);
			drawOutline(g, boxX, rBoxY, boxW, sectionH, 0xFF383838);
			g.drawString(this.font, "Riddle:", boxX + layout.w(8), rBoxY + layout.h(6), 0xFFFFD24A);

			if (!slot.note().isEmpty()) {
				List<String> nLines = wrapWords(slot.note(), boxW - layout.w(16));
				int maxLines = Math.max(1, (sectionH - layout.h(24)) / 10);
				for (int i = 0; i < Math.min(maxLines, nLines.size()); i++) {
					g.drawString(this.font, nLines.get(i), boxX + layout.w(8), rBoxY + layout.h(20 + i * 10), 0xFFDDDDDD);
				}
				if (mouseX >= boxX && mouseX < boxX + boxW && mouseY >= rBoxY && mouseY < rBoxY + sectionH) {
					hoveredClueNote = slot.note();
					hoveredTooltipTitle = "Riddle";
				}
			}

			// Clue Box
			int cBoxY = rBoxY + sectionH + layout.h(6);
			g.fill(boxX, cBoxY, boxX + boxW, cBoxY + sectionH, 0x44141414);
			drawOutline(g, boxX, cBoxY, boxW, sectionH, 0xFF383838);
			g.drawString(this.font, "Your Clue:", boxX + layout.w(8), cBoxY + layout.h(6), 0xFF55FFFF);

			if (!clueNote.isEmpty()) {
				List<String> cLines = wrapWords(clueNote, boxW - layout.w(60));
				int maxLines = Math.max(1, (sectionH - layout.h(24)) / 10);
				for (int ci = 0; ci < Math.min(maxLines, cLines.size()); ci++) {
					g.drawString(this.font, cLines.get(ci), boxX + layout.w(8), cBoxY + layout.h(20 + ci * 10), 0xFFFFFFFF);
				}
				if (mouseX >= boxX && mouseX < boxX + boxW && mouseY >= cBoxY && mouseY < cBoxY + sectionH) {
					hoveredClueNote = clueNote;
					hoveredTooltipTitle = "Clue Details";
				}
			}
			if (!clueImage.isEmpty()) {
				renderImageLink(g, clueImage, boxX + boxW - layout.w(48), cBoxY + layout.h(6), mouseX, mouseY);
			}
		}
	}

	private void renderMobHuntDetail(GuiGraphics g, int dX, int dY, int dW, int dH, LongEventState.SpecialSlot slot, long nowMs, int mouseX, int mouseY) {
		g.drawString(this.font, slot.title().isEmpty() ? "Mob Hunt" : slot.title(), dX + layout.w(10), dY + layout.h(8), 0xFFFFD24A);
		if (slot.expiresAtMs() > 0) {
			long rem = slot.expiresAtMs() - nowMs;
			String exp = rem > 0 ? "Resets in " + LongEventState.formatCountdown(rem, false) : "Ended";
			g.drawString(this.font, exp, dX + dW - layout.w(10) - this.font.width(exp), dY + layout.h(8), 0xFFCCCCCC);
		}
		if (slot.completed()) {
			g.drawString(this.font, "Completed ✔", dX + layout.w(10), dY + layout.h(24), 0xFF55FF55);
		}

		List<String> images = getSpecialSlotImages(slot);
		int bodyY = dY + layout.h(38);
		int bodyH = dH - layout.h(48);
		if (!images.isEmpty()) {
			int gap = layout.w(8);
			int colW = (dW - layout.w(20) - gap) / 2;
			int imgX = dX + layout.w(10);
			int noteX = imgX + colW + gap;
			renderSpecialImageSection(g, imgX, bodyY, colW, bodyH, images, mouseX, mouseY);
			renderSpecialNoteSection(g, noteX, bodyY, colW, bodyH, slot.note());
		} else {
			int noteBoxX = dX + layout.w(10);
			int noteBoxW = dW - layout.w(20);
			renderSpecialNoteSection(g, noteBoxX, bodyY, noteBoxW, bodyH, slot.note());
		}
	}

	private void renderMurderMysteryDetail(GuiGraphics g, int dX, int dY, int dW, int dH, LongEventState.SpecialSlot slot, long nowMs, int mouseX, int mouseY) {
		g.drawString(this.font, slot.title().isEmpty() ? "Murder Mystery" : slot.title(), dX + layout.w(10), dY + layout.h(6), 0xFFFFD24A);
		if (slot.completed()) {
			g.drawString(this.font, "Mystery Solved! ✔", dX + dW - layout.w(10) - this.font.width("Mystery Solved! ✔"), dY + layout.h(6), 0xFF55FF55);
		}

		if (!slot.note().isEmpty()) {
			drawFittedString(g, slot.note(), dX + layout.w(10), dY + layout.h(18), dW - layout.w(20), 0xFFCCCCCC);
		}

		int progY = dY + layout.h(30);
		String clueProg = "Clue Progress: " + slot.clueRaidProgress() + " / " + slot.clueRaidTarget() + " Raids";
		g.drawString(this.font, clueProg, dX + layout.w(10), progY, 0xFFFFFFFF);
		if (slot.clueUnlockedToday()) {
			String unlockedMsg = "Clue unlocked! ✔";
			if (slot.nextClueAtMs() > nowMs) {
				unlockedMsg += " (Next in " + LongEventState.formatCountdown(slot.nextClueAtMs() - nowMs, false) + ")";
			}
			g.drawString(this.font, unlockedMsg, dX + layout.w(10) + this.font.width(clueProg) + layout.w(12), progY, 0xFF55FF55);
		} else {
			int cBarW = layout.w(110);
			int cBarH = Math.max(3, layout.h(5));
			int cBarX = dX + layout.w(10) + this.font.width(clueProg) + layout.w(10);
			float cRatio = Math.clamp(slot.clueRaidProgress() / (float) Math.max(1, slot.clueRaidTarget()), 0.0f, 1.0f);
			drawProgressBar(g, cBarX, progY + 1, cBarW, cBarH, cRatio, false);
		}

		List<String> images = getSpecialSlotImages(slot);
		List<LongEventState.ClueEntry> clues = slot.clues();

		int cluesStartY = dY + layout.h(44);
		int listH = layout.h(120);

		if (!images.isEmpty()) {
			int gap = layout.w(8);
			int colW = (dW - layout.w(20) - gap) / 2;
			int imgX = dX + layout.w(10);
			int cluesX = imgX + colW + gap;

			renderSpecialImageSection(g, imgX, cluesStartY, colW, listH, images, mouseX, mouseY);

			g.fill(cluesX, cluesStartY, cluesX + colW, cluesStartY + listH, 0x44141414);
			drawOutline(g, cluesX, cluesStartY, colW, listH, 0xFF383838);

			int headerY = cluesStartY + layout.h(6);
			g.drawString(this.font, "Clues (" + clues.size() + ")", cluesX + layout.w(8), headerY, 0xFF55FFFF);

			int listY = cluesStartY + layout.h(18);
			if (clues.isEmpty()) {
				g.drawString(this.font, "No clues unlocked yet.", cluesX + layout.w(8), listY, 0xFF888888);
			} else {
				int maxClueScroll = Math.max(0, clues.size() - 4);
				if (mysteryClueScrollOffset > maxClueScroll) {
					mysteryClueScrollOffset = maxClueScroll;
				}
				int visibleCount = Math.min(4, clues.size() - mysteryClueScrollOffset);
				boolean hasScrollbar = clues.size() > 4;
				int availableTextW = colW - layout.w(hasScrollbar ? 18 : 12);

				for (int ci = 0; ci < visibleCount; ci++) {
					int clueIndex = mysteryClueScrollOffset + ci;
					LongEventState.ClueEntry ce = clues.get(clueIndex);
					int rowY = listY + ci * layout.h(15);
					String prefix = "#" + (clueIndex + 1) + ": ";
					g.drawString(this.font, prefix, cluesX + layout.w(8), rowY, 0xFFFFD24A);
					int pWidth = this.font.width(prefix);
					drawFittedString(g, ce.note(), cluesX + layout.w(8) + pWidth, rowY, availableTextW - pWidth, 0xFFDDDDDD);
					if (mouseX >= cluesX + layout.w(8) && mouseX < cluesX + colW - layout.w(8) && mouseY >= rowY && mouseY < rowY + layout.h(15)) {
						hoveredClueNote = ce.note();
					}
				}
				if (hasScrollbar) {
					layout.drawScrollbar(g, cluesX + colW - layout.w(7), listY, layout.w(4), 4 * layout.h(15), 4, clues.size(), mysteryClueScrollOffset);
				}
			}
		} else {
			g.drawString(this.font, "Unlocked Clues (" + clues.size() + "):", dX + layout.w(10), cluesStartY, 0xFF55FFFF);
			int listY = cluesStartY + layout.h(12);
			if (clues.isEmpty()) {
				g.drawString(this.font, "No clues unlocked yet. Complete raids to reveal clues!", dX + layout.w(14), listY, 0xFF888888);
			} else {
				int maxClueScroll = Math.max(0, clues.size() - 4);
				if (mysteryClueScrollOffset > maxClueScroll) {
					mysteryClueScrollOffset = maxClueScroll;
				}
				int visibleCount = Math.min(4, clues.size() - mysteryClueScrollOffset);
				boolean hasScrollbar = clues.size() > 4;
				int availableTextW = dW - layout.w(hasScrollbar ? 85 : 75);

				for (int ci = 0; ci < visibleCount; ci++) {
					int clueIndex = mysteryClueScrollOffset + ci;
					LongEventState.ClueEntry ce = clues.get(clueIndex);
					int rowY = listY + ci * layout.h(15);
					String prefix = "• Clue #" + (clueIndex + 1) + ": ";
					g.drawString(this.font, prefix, dX + layout.w(14), rowY, 0xFFFFD24A);
					int pWidth = this.font.width(prefix);
					drawFittedString(g, ce.note(), dX + layout.w(14) + pWidth, rowY, availableTextW - pWidth, 0xFFDDDDDD);
					if (!ce.imageUrl().isEmpty()) {
						renderImageLink(g, ce.imageUrl(), dX + dW - layout.w(hasScrollbar ? 58 : 48), rowY, mouseX, mouseY);
					}
					if (mouseX >= dX + layout.w(14) && mouseX < dX + dW - layout.w(hasScrollbar ? 60 : 50) && mouseY >= rowY && mouseY < rowY + layout.h(15)) {
						hoveredClueNote = ce.note();
					}
				}
				if (hasScrollbar) {
					layout.drawScrollbar(g, dX + dW - layout.w(10), listY, layout.w(5), 4 * layout.h(15), 4, clues.size(), mysteryClueScrollOffset);
				}
			}
		}

		int statusY = layout.y(264);
		if (slot.completed()) {
			g.drawCenteredString(this.font, "Mystery Solved! ✔", dX + dW / 2, statusY, 0xFF55FF55);
		} else {
			if (slot.opened()) {
				int btnX = layout.x(434);
				int btnY = layout.y(278);
				int btnW = layout.w(20);
				int btnH = layout.h(20);
				mysteryRerollBox = new ClickBox(btnX, btnY, btnW, btnH);
				boolean mHover = mysteryRerollBox.contains(mouseX, mouseY);
				if (mHover) {
					hoveredMysteryReroll = true;
				}
				boolean free = slot.canGuessFree() || (slot.nextGuessAtMs() > 0L && nowMs >= slot.nextGuessAtMs());
				boolean canBuyGuess = !free && (slot.canGuessTrivia() || (state != null && (state.rerollCost() == -1 || state.triviaPoints() >= 1)));
				boolean shiftHeld = RaidGambitSafety.isShiftKeyDown();
				int rBg;
				int rBorder;
				int rFg;
				if (free) {
					rBg = mHover ? 0xFF484848 : 0xFF303030;
					rBorder = mHover ? 0xFFFFD24A : 0xFF999999;
					rFg = mHover ? 0xFFFFD24A : 0xFFFFFFFF;
				} else if (canBuyGuess && shiftHeld && mHover) {
					rBg = 0xFF203838;
					rBorder = 0xFF55FFFF;
					rFg = 0xFF55FFFF;
				} else {
					rBg = 0xFF1C1C1C;
					rBorder = 0xFF404040;
					rFg = 0xFF666666;
				}
				g.fill(btnX, btnY, btnX + btnW, btnY + btnH, rBg);
				drawOutline(g, btnX, btnY, btnW, btnH, rBorder);
				g.drawCenteredString(this.font, "⟳", btnX + btnW / 2, btnY + (btnH - 8) / 2, rFg);
			}

			if (!slot.lastGuessStatus().isEmpty()) {
				boolean incorrect = slot.lastGuessStatus().toLowerCase(Locale.ROOT).contains("incorrect");
				boolean correct = !incorrect && slot.lastGuessStatus().toLowerCase(Locale.ROOT).contains("correct");
				int statColor = correct ? 0xFF55FF55 : 0xFFFF5555;
				String displayStatus = incorrect ? "Incorrect!" : (correct ? "Correct!" : slot.lastGuessStatus());
				drawFittedCenteredString(g, displayStatus, dX + dW / 2, statusY, dW - layout.w(20), statColor);
			} else if (!slot.canGuessFree() && slot.nextGuessAtMs() > nowMs) {
				long rem = slot.nextGuessAtMs() - nowMs;
				String freeIn = "Next free guess in " + LongEventState.formatCountdown(rem, false);
				g.drawCenteredString(this.font, freeIn, dX + dW / 2, statusY, 0xFFAAAAAA);
			}
		}
	}

	private void renderPromptDetail(GuiGraphics g, int dX, int dY, int dW, int dH, LongEventState.SpecialSlot slot, String defaultTitle, long nowMs, int mouseX, int mouseY) {
		g.drawString(this.font, slot.title().isEmpty() ? defaultTitle : slot.title(), dX + layout.w(10), dY + layout.h(8), 0xFFFFD24A);
		if (slot.expiresAtMs() > 0) {
			long rem = slot.expiresAtMs() - nowMs;
			String exp = rem > 0 ? "Ends in " + LongEventState.formatCountdown(rem, true) : "Ended";
			g.drawString(this.font, exp, dX + dW - layout.w(10) - this.font.width(exp), dY + layout.h(8), 0xFFCCCCCC);
		}
		if (slot.completed()) {
			g.drawString(this.font, "Completed ✔", dX + layout.w(10), dY + layout.h(24), 0xFF55FF55);
		}

		List<String> images = getSpecialSlotImages(slot);
		int bodyY = dY + layout.h(38);
		int bodyH = dH - layout.h(48);
		if (!images.isEmpty()) {
			int gap = layout.w(8);
			int colW = (dW - layout.w(20) - gap) / 2;
			int imgX = dX + layout.w(10);
			int noteX = imgX + colW + gap;
			renderSpecialImageSection(g, imgX, bodyY, colW, bodyH, images, mouseX, mouseY);
			renderSpecialNoteSection(g, noteX, bodyY, colW, bodyH, slot.note());
			if (mouseX >= noteX && mouseX < noteX + colW && mouseY >= bodyY && mouseY < bodyY + bodyH && !slot.note().isEmpty()) {
				hoveredClueNote = slot.note();
				hoveredTooltipTitle = slot.title().isEmpty() ? defaultTitle : slot.title();
			}
		} else {
			int noteBoxX = dX + layout.w(10);
			int noteBoxW = dW - layout.w(20);
			renderSpecialNoteSection(g, noteBoxX, bodyY, noteBoxW, bodyH, slot.note());
			if (mouseX >= noteBoxX && mouseX < noteBoxX + noteBoxW && mouseY >= bodyY && mouseY < bodyY + bodyH && !slot.note().isEmpty()) {
				hoveredClueNote = slot.note();
				hoveredTooltipTitle = slot.title().isEmpty() ? defaultTitle : slot.title();
			}
		}
	}

	private void renderImageLink(GuiGraphics g, String url, int x, int y, int mouseX, int mouseY) {
		Component imgLabel = Component.literal("[Image]").withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);
		int lw = this.font.width(imgLabel);
		ClickBox box = new ClickBox(x - 2, y - 1, lw + 4, 10);
		imageClickTargets.add(new ImageClickTarget(box, url));
		g.drawString(this.font, imgLabel, x, y, 0xFF55FFFF);
	}

	private void drawRainbowWaveString(GuiGraphics g, String text, int startX, int y, long nowMs) {
		float baseHue = ((nowMs % 4000L) / 4000.0f);
		int curX = startX;
		for (int i = 0; i < text.length(); i++) {
			char ch = text.charAt(i);
			String chStr = String.valueOf(ch);
			int chW = this.font.width(chStr);
			float charHue = (baseHue + (float) i * 0.05f) % 1.0f;
			int rgb = Mth.hsvToRgb(charHue, 0.75f, 1.0f);
			int color = 0xFF000000 | (rgb & 0x00FFFFFF);
			g.drawString(this.font, chStr, curX, y, color);
			curX += chW;
		}
	}

	private List<String> wrapWords(String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		if (text == null || text.isBlank() || maxWidth <= 0) {
			return lines;
		}
		String[] words = text.trim().split("\\s+");
		StringBuilder current = new StringBuilder();
		for (String word : words) {
			if (current.length() == 0) {
				if (this.font.width(word) > maxWidth) {
					for (char ch : word.toCharArray()) {
						if (this.font.width(current.toString() + ch) > maxWidth && current.length() > 0) {
							lines.add(current.toString());
							current.setLength(0);
						}
						current.append(ch);
					}
				} else {
					current.append(word);
				}
			} else {
				String candidate = current + " " + word;
				if (this.font.width(candidate) <= maxWidth) {
					current.append(" ").append(word);
				} else {
					lines.add(current.toString());
					current.setLength(0);
					if (this.font.width(word) > maxWidth) {
						for (char ch : word.toCharArray()) {
							if (this.font.width(current.toString() + ch) > maxWidth && current.length() > 0) {
								lines.add(current.toString());
								current.setLength(0);
							}
							current.append(ch);
						}
					} else {
						current.append(word);
					}
				}
			}
		}
		if (current.length() > 0) {
			lines.add(current.toString());
		}
		return lines;
	}

	private List<Component> buildRerollTooltip(String slotKey, LongEventState.Slot slot, long nowMs) {
		List<Component> lines = new ArrayList<>();
		if (slot != null && slot.canReroll()) {
			lines.add(Component.literal("Reroll this mission").withStyle(ChatFormatting.GOLD));
			lines.add(Component.literal(LongEventState.rerollRuleText(slotKey)).withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal("The rerolled mission's type can't roll in this slot for the next 2 rolls").withStyle(ChatFormatting.GRAY));
		} else {
			lines.add(Component.literal("Reroll on cooldown").withStyle(ChatFormatting.RED));
			lines.add(Component.literal(LongEventState.rerollRuleText(slotKey)).withStyle(ChatFormatting.GRAY));
			lines.add(Component.literal("The rerolled mission's type can't roll in this slot for the next 2 rolls").withStyle(ChatFormatting.GRAY));
			if (slot != null && slot.rerollAtMs() > 0L) {
				long rem = Math.max(0L, slot.rerollAtMs() - nowMs);
				boolean useDays = rem >= 86_400_000L;
				lines.add(Component.literal("Available in " + LongEventState.formatCountdown(rem, useDays)).withStyle(ChatFormatting.RED));
			}
		}

		if (slot != null && !slot.completed()) {
			int cost = state != null ? state.rerollCost() : 0;
			int pts = state != null ? state.triviaPoints() : 0;
			if (cost == -1) {
				lines.add(Component.empty());
				lines.add(Component.literal("Shift-Click for free extra reroll").withStyle(ChatFormatting.AQUA));
				lines.add(Component.literal("Cost: Free").withStyle(ChatFormatting.GREEN));
			} else if (cost > 0) {
				lines.add(Component.empty());
				if (slot.canBuyReroll()) {
					lines.add(Component.literal("Shift-Click to buy extra reroll").withStyle(ChatFormatting.AQUA));
					lines.add(Component.literal("Cost: " + cost + " Points (You have " + pts + ")").withStyle(ChatFormatting.DARK_AQUA));
				} else {
					lines.add(Component.literal("Shift-Click to buy extra reroll").withStyle(ChatFormatting.DARK_GRAY));
					lines.add(Component.literal("Requires " + cost + " Points (You have " + pts + ")").withStyle(ChatFormatting.RED));
				}
			}
		}
		return lines;
	}

	private List<Component> buildMysteryRerollTooltip(LongEventState.SpecialSlot slot, long nowMs) {
		List<Component> lines = new ArrayList<>();
		boolean free = slot != null && (slot.canGuessFree() || (slot.nextGuessAtMs() > 0L && nowMs >= slot.nextGuessAtMs()));
		if (free) {
			lines.add(Component.literal("Guess available").withStyle(ChatFormatting.GOLD));
			lines.add(Component.literal("Murder Mystery: once every 6 hours").withStyle(ChatFormatting.GRAY));
		} else {
			lines.add(Component.literal("Guess on cooldown").withStyle(ChatFormatting.RED));
			lines.add(Component.literal("Murder Mystery: once every 6 hours").withStyle(ChatFormatting.GRAY));
			if (slot != null && slot.nextGuessAtMs() > nowMs) {
				long rem = slot.nextGuessAtMs() - nowMs;
				lines.add(Component.literal("Available in " + LongEventState.formatCountdown(rem, false)).withStyle(ChatFormatting.RED));
			}
		}

		if (slot != null && !slot.completed()) {
			int cost = state != null ? state.rerollCost() : 0;
			int pts = state != null ? state.triviaPoints() : 0;
			lines.add(Component.empty());
			if (cost == -1) {
				lines.add(Component.literal("Shift-Click for free extra guess").withStyle(ChatFormatting.AQUA));
				lines.add(Component.literal("Cost: Free").withStyle(ChatFormatting.GREEN));
			} else if (slot.canGuessTrivia() || pts >= 1) {
				lines.add(Component.literal("Shift-Click to buy extra guess").withStyle(ChatFormatting.AQUA));
				lines.add(Component.literal("Cost: 1 Point (You have " + pts + ")").withStyle(ChatFormatting.DARK_AQUA));
			} else {
				lines.add(Component.literal("Shift-Click to buy extra guess").withStyle(ChatFormatting.DARK_GRAY));
				lines.add(Component.literal("Requires 1 Point (You have 0)").withStyle(ChatFormatting.RED));
			}
		}
		return lines;
	}

	private void drawFittedString(GuiGraphics g, String text, int x, int y, int maxWidth, int color) {
		int textW = this.font.width(text);
		if (textW <= maxWidth || textW <= 0) {
			g.drawString(this.font, text, x, y, color);
			return;
		}
		float s = maxWidth / (float) textW;
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(s, s);
		g.drawString(this.font, text, 0, 0, color);
		g.pose().popMatrix();
	}

	private void drawFittedCenteredString(GuiGraphics g, String text, int centerX, int y, int maxWidth, int color) {
		int textW = this.font.width(text);
		if (textW <= maxWidth || textW <= 0) {
			g.drawCenteredString(this.font, text, centerX, y, color);
			return;
		}
		float s = maxWidth / (float) textW;
		g.pose().pushMatrix();
		g.pose().translate(centerX, y);
		g.pose().scale(s, s);
		g.drawString(this.font, text, -textW / 2, 0, color);
		g.pose().popMatrix();
	}

	private static void drawOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + 1, color);
		g.fill(x, y + h - 1, x + w, y + h, color);
		g.fill(x, y, x + 1, y + h, color);
		g.fill(x + w - 1, y, x + w, y + h, color);
	}

	private static void drawProgressBar(GuiGraphics g, int x, int y, int w, int h, float ratio, boolean completed) {
		g.fill(x, y, x + w, y + h, 0xFF141414);
		int fillW = Math.round((w - 2) * Math.clamp(ratio, 0.0f, 1.0f));
		if (fillW > 0) {
			g.fill(x + 1, y + 1, x + 1 + fillW, y + h - 1, completed ? 0xFF55DD55 : 0xFFFFB833);
		}
		drawOutline(g, x, y, w, h, 0xFF3A3A3A);
	}

	private static void drawTexture(GuiGraphics g, Identifier id, int x, int y, int w, int h) {
		if (id == null) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / 64.0f, h / 64.0f);
		g.blit(RenderPipelines.GUI_TEXTURED, id, 0, 0, 0.0f, 0.0f, 64, 64, 64, 64);
		g.pose().popMatrix();
	}

	private static Identifier frameForSlot(String slotKey) {
		return switch (slotKey.toLowerCase(Locale.ROOT)) {
			case "daily" -> FRAME_DAILY;
			case "special", "weekly", "super" -> FRAME_WEEKLY;
			default -> FRAME_REPEATABLE;
		};
	}

	private static Identifier iconForTag(String tag) {
		if (tag == null) {
			return ICON_OTHER;
		}
		return switch (tag.trim().toUpperCase(Locale.ROOT)) {
			case "NOTG" -> ICON_NOTG;
			case "NOL" -> ICON_NOL;
			case "TCC" -> ICON_TCC;
			case "TNA" -> ICON_TNA;
			case "WTP" -> ICON_WTP;
			case "ALL RAIDS" -> ICON_ALL_RAIDS;
			case "GUILD WARS" -> ICON_GUILD_WARS;
			case "TRIVIA" -> ICON_TRIVIA;
			case "DICEROLL" -> ICON_DICEROLL;
			case "COINFLIP" -> ICON_COINFLIP;
			default -> ICON_OTHER;
		};
	}

	private static Identifier iconForMaster(String kind) {
		if ("raids".equalsIgnoreCase(kind)) {
			return ICON_ALL_RAIDS;
		}
		if ("wars".equalsIgnoreCase(kind) || "guild_wars".equalsIgnoreCase(kind)) {
			return ICON_GUILD_WARS;
		}
		return ICON_EDEN;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		MouseButtonEvent scaled = rescale(event);
		if (scaled.button() == 0) {
			double mx = scaled.x();
			double my = scaled.y();

			// Tab switcher click
			int tab0X = layout.x(140);
			int tab0Y = layout.y(6);
			int tab0W = layout.w(70);
			int tab0H = layout.h(16);
			ClickBox tab0Box = new ClickBox(tab0X, tab0Y, tab0W, tab0H);
			if (tab0Box.contains(mx, my)) {
				if (activeTab != 0) {
					activeTab = 0;
					currentSpecialImageIndex = 0;
					scavengerScrollOffset = 0;
					mysteryClueScrollOffset = 0;
					this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
				}
				return true;
			}

			int tab1X = layout.x(216);
			int tab1Y = layout.y(6);
			int tab1W = layout.w(134);
			int tab1H = layout.h(16);
			ClickBox tab1Box = new ClickBox(tab1X, tab1Y, tab1W, tab1H);
			if (tab1Box.contains(mx, my)) {
				if (activeTab != 1) {
					activeTab = 1;
					currentSpecialImageIndex = 0;
					scavengerScrollOffset = 0;
					mysteryClueScrollOffset = 0;
					this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
				}
				return true;
			}

			// Special Mission image carousel navigation buttons
			if (activeTab == 1 && state != null && state.active()) {
				LongEventState.SpecialSlot currentSpecial = state.findSpecialSlot(selectedSpecialSlot);
				List<String> images = getSpecialSlotImages(currentSpecial);
				if (images.size() > 1) {
					if (specialImageNavPrevBox != null && specialImageNavPrevBox.contains(mx, my)) {
						currentSpecialImageIndex = (currentSpecialImageIndex - 1 + images.size()) % images.size();
						this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
						return true;
					}
					if (specialImageNavNextBox != null && specialImageNavNextBox.contains(mx, my)) {
						currentSpecialImageIndex = (currentSpecialImageIndex + 1) % images.size();
						this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
						return true;
					}
				}
			}

			// Clickable image links
			for (ImageClickTarget target : imageClickTargets) {
				if (target.box().contains(mx, my)) {
					openUrlSafely(target.imageUrl());
					return true;
				}
			}

			if (state != null && state.active()) {
				if (activeTab == 0) {
					String[] slotsToRender = currentStandardSlots();
					int totalSlots = slotsToRender.length;
					for (int i = 0; i < totalSlots; i++) {
						String slotKey = slotsToRender[i];
						LongEventState.Slot slot = state.findSlot(slotKey);
						if (slot == null || slot.missionId() == 0) {
							continue;
						}
						ClickBox rBox = rerollBox(i, totalSlots);
						if (rBox != null && rBox.contains(mx, my)) {
							if (mod.socket() != null && !rerollPending) {
								boolean shift = RaidGambitSafety.isShiftKeyDown();
								if (slot.canReroll()) {
									this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
									rerollPending = true;
									rerollPendingSinceMs = System.currentTimeMillis();
									rerollPendingGeneration = mod.longEventGeneration();
									mod.socket().sendLongEventReroll(slotKey, false);
									return true;
								} else if (shift && slot.canBuyReroll()) {
									this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
									rerollPending = true;
									rerollPendingSinceMs = System.currentTimeMillis();
									rerollPendingGeneration = mod.longEventGeneration();
									mod.socket().sendLongEventReroll(slotKey, true);
									return true;
								}
							}
							return true;
						}
					}
				} else if (activeTab == 1) {
					if (mysteryRerollBox != null && mysteryRerollBox.contains(mx, my)) {
						if (!guessPending && mod.socket() != null && mod.socket().isConnected()) {
							LongEventState.SpecialSlot slot4 = state.findSpecialSlot(4);
							if (slot4 != null && slot4.opened() && !slot4.completed()) {
								boolean free = slot4.canGuessFree() || (slot4.nextGuessAtMs() > 0L && System.currentTimeMillis() >= slot4.nextGuessAtMs());
								boolean shift = RaidGambitSafety.isShiftKeyDown();
								int pts = state.triviaPoints();
								boolean canBuy = slot4.canGuessTrivia() || state.rerollCost() == -1 || pts >= 1;
								if (free) {
									this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
									if (!guessEditBox.getValue().trim().isEmpty()) {
										submitGuess(false);
									} else {
										guessEditBox.setFocused(true);
										this.setFocused(guessEditBox);
									}
									return true;
								} else if (shift && canBuy) {
									this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
									if (!guessEditBox.getValue().trim().isEmpty()) {
										submitGuess(true);
									} else {
										triviaGuessArmed = true;
										guessEditBox.setFocused(true);
										this.setFocused(guessEditBox);
									}
									return true;
								}
							}
						}
						return true;
					}
					for (int i = 0; i < SPECIAL_CARD_BASE_X.length; i++) {
						ClickBox mBox = specialMiniCardBox(i);
						if (mBox != null && mBox.contains(mx, my)) {
							if (selectedSpecialSlot != i + 1) {
								selectedSpecialSlot = i + 1;
								currentSpecialImageIndex = 0;
								scavengerScrollOffset = 0;
								mysteryClueScrollOffset = 0;
							}
							this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
							return true;
						}
					}
				}
			}
		}
		return super.mouseClicked(scaled, bl);
	}

	private void openUrlSafely(String url) {
		if (url == null || url.isBlank()) {
			return;
		}
		try {
			URI uri = URI.create(url.trim());
			String scheme = uri.getScheme();
			if (scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
				ConfirmLinkScreen.confirmLinkNow(this, url.trim(), false);
			}
		} catch (Exception ignored) {
		}
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(rescale(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double d, double e) {
		return super.mouseDragged(rescale(event), d / uiScale, e / uiScale);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double d, double e) {
		double scaledMouseX = mouseX / uiScale;
		double scaledMouseY = mouseY / uiScale;
		if (activeTab == 1 && layout != null && state != null && state.active()) {
			int dX = layout.x(13);
			int dY = layout.y(90);
			int dW = layout.w(454);
			int dH = layout.h(220);
			if (scaledMouseX >= dX && scaledMouseX < dX + dW && scaledMouseY >= dY && scaledMouseY < dY + dH) {
				if (selectedSpecialSlot == 1) {
					LongEventState.SpecialSlot slot1 = state.findSpecialSlot(1);
					if (slot1 != null && slot1.items().size() > 3) {
						int maxScroll = slot1.items().size() - 3;
						scavengerScrollOffset = Math.clamp(scavengerScrollOffset - (int) Math.signum(e), 0, maxScroll);
						return true;
					}
				} else if (selectedSpecialSlot == 4) {
					LongEventState.SpecialSlot slot4 = state.findSpecialSlot(4);
					if (slot4 != null && slot4.clues().size() > 4) {
						int maxScroll = slot4.clues().size() - 4;
						mysteryClueScrollOffset = Math.clamp(mysteryClueScrollOffset - (int) Math.signum(e), 0, maxScroll);
						return true;
					}
				}
			}
		}
		return super.mouseScrolled(mouseX / uiScale, mouseY / uiScale, d, e);
	}

}