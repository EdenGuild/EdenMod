package tel.eden.mod.gui;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.ImagePreviewManager;
import tel.eden.mod.raid.RaidGambitSafety;
import tel.eden.mod.net.LongEventState;

/** Screen displaying Daily, Weekly, Repeatable, and Master long-event missions. */
public final class LongEventScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 480;
	private static final int BASE_PANEL_HEIGHT = 350;
	private static final String[] SLOT_ORDER = {"daily", "special", "repeatable", "repeatable2"};
	private static final String[] SLOT_LABELS = {"Daily", "Special", "Repeatable", "Repeatable 2"};
	private static final int[] CARD_BASE_X = {12, 128, 244, 360};
	private static final int CARD_BASE_W = 108;
	private static final int CARD_BASE_Y = 48;
	private static final int CARD_BASE_H = 146;

	private static final SoundEvent CLICK_SOUND = SoundEvent.createVariableRangeEvent(Identifier.parse("minecraft:ui.button.click"));
	private static final Map<String, LoadedTexture> TEXTURE_CACHE = new HashMap<>();

	private record LoadedTexture(Identifier id, int width, int height) {
		private static final LoadedTexture EMPTY = new LoadedTexture(null, 0, 0);
	}

	private record ClickBox(int x, int y, int w, int h) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final Screen parent;
	private final EdenModClient mod;
	private EdenPanelLayout layout;
	private int seenGeneration = -1;
	private LongEventState state;
	private final Map<String, Long> requestedResetAtMs = new HashMap<>();
	private long lastStateRequestMs = 0L;

	public LongEventScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Event"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		super.init();
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(layout.x(160), layout.y(322), layout.w(160), layout.h(20)).build());

		preloadTextures();

		if (mod.socket() != null && mod.socket().isConnected()) {
			lastStateRequestMs = System.currentTimeMillis();
			mod.socket().sendLongEventStateRequest();
		}

		refreshSnapshot();
	}

	private void refreshSnapshot() {
		seenGeneration = mod.longEventGeneration();
		state = mod.knownLongEventState();
	}

	@Override
	public void tick() {
		super.tick();
		if (seenGeneration != mod.longEventGeneration()) {
			refreshSnapshot();
		}
		long now = System.currentTimeMillis();
		if (state == null && mod.socket() != null && mod.socket().isConnected()) {
			if (now - lastStateRequestMs > 3000L) {
				lastStateRequestMs = now;
				mod.socket().sendLongEventStateRequest();
			}
		}
		if (state != null && state.active() && mod.socket() != null) {
			for (LongEventState.Slot slot : state.slots()) {
				long resetAt = slot.resetAtMs();
				if (resetAt > 0L && now >= resetAt) {
					Long prev = requestedResetAtMs.get(slot.slot());
					if (prev == null || prev != resetAt) {
						requestedResetAtMs.put(slot.slot(), resetAt);
						mod.socket().sendLongEventStateRequest();
						break;
					}
				}
			}
		}
	}

	private ClickBox rerollBox(int slotIndex) {
		if (layout == null || slotIndex < 0 || slotIndex >= CARD_BASE_X.length) {
			return null;
		}
		int cardX = layout.x(CARD_BASE_X[slotIndex]);
		int cardY = layout.y(CARD_BASE_Y);
		int cardW = layout.w(CARD_BASE_W);
		int rerollSize = layout.w(14);
		int rx = cardX + cardW - rerollSize - layout.w(4);
		int ry = cardY + layout.h(4);
		return new ClickBox(rx, ry, rerollSize, rerollSize);
	}

	private ClickBox imageLinkBox(int slotIndex) {
		if (layout == null || slotIndex < 0 || slotIndex >= CARD_BASE_X.length) {
			return null;
		}
		int cardX = layout.x(CARD_BASE_X[slotIndex]);
		int cardY = layout.y(CARD_BASE_Y);
		int cardW = layout.w(CARD_BASE_W);
		int cardH = layout.h(CARD_BASE_H);
		int cardCenterX = cardX + cardW / 2;
		int lw = this.font.width("[Image]");
		int lx = cardCenterX - lw / 2;
		int ly = cardY + cardH - layout.h(12);
		return new ClickBox(lx - 2, ly - 1, lw + 4, 10);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);
		long nowMs = System.currentTimeMillis();

		LongEventState.Slot hoveredRerollSlot = null;
		String hoveredRerollSlotName = null;
		String hoveredImageUrl = null;
		LongEventState.Slot hoveredNoteSlot = null;

		pushReferencePose(g);
		layout.drawBackground(g);
		layout.drawPanel(g);
		super.render(g, scaledMouseX, scaledMouseY, delta);

		// Header
		g.drawCenteredString(this.font, "Event", layout.centerX(), layout.y(10), 0xFFFFFFFF);
		if (state != null && state.active()) {
			String pendingText = "Pending: " + state.pendingLe() + " LE";
			g.drawString(this.font, pendingText, layout.x(12), layout.y(10), 0xFFFFD24A);
			if (state.endsAtMs() > 0L) {
				long rem = state.endsAtMs() - nowMs;
				String endsText = rem > 0L ? "Ends in " + LongEventState.formatCountdown(rem, true) : "Ended";
				g.drawString(this.font, endsText, layout.x(BASE_PANEL_WIDTH - 12) - this.font.width(endsText), layout.y(10), 0xFFCCCCCC);
			}
		}

		if (seenGeneration == 0 || state == null) {
			g.drawCenteredString(this.font, "Loading…", layout.centerX(), layout.y(155), 0xFFAAAAAA);
		} else if (!state.active()) {
			g.drawCenteredString(this.font, "No event is running", layout.centerX(), layout.y(155), 0xFFAAAAAA);
		} else {
			// Four mission cards: Daily, Special, Repeatable, Repeatable 2
			for (int i = 0; i < SLOT_ORDER.length; i++) {
				String slotKey = SLOT_ORDER[i];
				String slotLabel = SLOT_LABELS[i];
				boolean isLockedRep2 = "repeatable2".equals(slotKey) && (state == null || !state.repeatSlot2Active());
				LongEventState.Slot slot = isLockedRep2 ? null : state.findSlot(slotKey);

				int cardX = layout.x(CARD_BASE_X[i]);
				int cardY = layout.y(CARD_BASE_Y);
				int cardW = layout.w(CARD_BASE_W);
				int cardH = layout.h(CARD_BASE_H);
				int cardCenterX = cardX + cardW / 2;

				// Slot title + live countdown above card
				g.drawCenteredString(this.font, slotLabel, cardCenterX, layout.y(25), isLockedRep2 ? 0xFF888888 : 0xFFFFD24A);
				String countdown = isLockedRep2 ? "Locked" : LongEventState.slotCountdownText(slotKey, slot != null ? slot.resetAtMs() : 0L, nowMs);
				drawFittedCenteredString(g, countdown, cardCenterX, layout.y(36), cardW - 4, isLockedRep2 ? 0xFF666666 : 0xFFCCCCCC);

				// Card panel
				if (isLockedRep2) {
					g.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x33161616);
					drawOutline(g, cardX, cardY, cardW, cardH, 0xFF333333);
					g.drawCenteredString(this.font, "Locked Slot", cardCenterX, cardY + layout.h(62), 0xFF666666);
					g.drawCenteredString(this.font, "Inactive", cardCenterX, cardY + layout.h(74), 0xFF444444);
					continue;
				}

				boolean completed = slot != null && slot.completed();
				int borderColor = completed ? 0xFF3A8A3A : 0xFF4A4A4A;
				g.fill(cardX, cardY, cardX + cardW, cardY + cardH, 0x66161616);
				drawOutline(g, cardX, cardY, cardW, cardH, borderColor);

				// Frame + centred tag icon
				int frameSize = layout.w(42);
				int frameX = cardX + (cardW - frameSize) / 2;
				int frameY = cardY + layout.h(4);
				LoadedTexture tagIcon = iconForTag(slot != null ? slot.tag() : "Other");
				if (tagIcon != null) {
					int iconSize = layout.w(26);
					drawTexture(g, tagIcon, frameX + (frameSize - iconSize) / 2, frameY + (frameSize - iconSize) / 2, iconSize, iconSize);
				}
				LoadedTexture frameTex = frameForSlot(slotKey);
				if (frameTex != null) {
					drawTexture(g, frameTex, frameX, frameY, frameSize, frameSize);
				}

				if (slot == null || slot.missionId() == 0) {
					g.drawCenteredString(this.font, "No mission", cardCenterX, cardY + layout.h(72), 0xFF888888);
					continue;
				}

				// Reroll icon button in top-right corner
				ClickBox rBox = rerollBox(i);
				int rerollSize = rBox.w();
				int rerollX = rBox.x();
				int rerollY = rBox.y();
				boolean canReroll = slot.canReroll();
				boolean canBuyReroll = slot.canBuyReroll();
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
				int innerW = cardW - layout.w(10);
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
				String progStr = slot.progress() + "/" + slot.target();
				String rewardStr = "+" + slot.rewardLe() + " LE";
				g.drawString(this.font, progStr, padX, statY, 0xFFDDDDDD);
				if (completed) {
					g.drawCenteredString(this.font, "Completed", cardCenterX, statY, 0xFF55FF55);
				}
				g.drawString(this.font, rewardStr, rightX - this.font.width(rewardStr), statY, 0xFFFFD24A);

				// Progress bar
				int barY = cardY + layout.h(80);
				int barW = cardW - layout.w(12);
				int barH = Math.max(3, layout.h(5));
				float ratio = completed ? 1.0f : Math.clamp(slot.progress() / (float) Math.max(1, slot.target()), 0.0f, 1.0f);
				drawProgressBar(g, padX, barY, barW, barH, ratio, completed);

				// Note + optional [Image] hover/click
				boolean hasNoteOrImage = !slot.note().isEmpty() || !slot.imageUrl().isEmpty();
				if (hasNoteOrImage) {
					int noteTop = cardY + layout.h(89);
					boolean hasImage = !slot.imageUrl().isEmpty();
					int maxNoteLines = hasImage ? 3 : 4;
					if (!slot.note().isEmpty()) {
						List<String> noteLines = wrapWords(slot.note(), Math.max(20, innerW));
						boolean isTruncated = noteLines.size() > maxNoteLines;
						int visibleLines = Math.min(maxNoteLines, noteLines.size());

						for (int nIdx = 0; nIdx < visibleLines; nIdx++) {
							int lineY = noteTop + nIdx * 9;
							String lineText = noteLines.get(nIdx);
							if (isTruncated && nIdx == visibleLines - 1) {
								int dotW = this.font.width("...");
								while (!lineText.isEmpty() && this.font.width(lineText) + dotW + 2 > innerW) {
									lineText = lineText.substring(0, lineText.length() - 1).trim();
								}
								g.drawString(this.font, lineText, padX, lineY, 0xFFBBBBBB);
								int dotX = padX + this.font.width(lineText) + 2;
								g.drawString(this.font, "...", dotX, lineY, 0xFFFFD24A);
							} else {
								g.drawString(this.font, lineText, padX, lineY, 0xFFBBBBBB);
							}
						}

						if (isTruncated) {
							int noteH = visibleLines * 9;
							if (scaledMouseX >= padX && scaledMouseX <= padX + innerW && scaledMouseY >= noteTop && scaledMouseY <= noteTop + noteH) {
								hoveredNoteSlot = slot;
							}
						}
					}
					if (hasImage) {
						Component imgLabel = Component.literal("[Image]").withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE);
						ClickBox imgBox = imageLinkBox(i);
						if (imgBox.contains(scaledMouseX, scaledMouseY)) {
							hoveredImageUrl = slot.imageUrl();
						}
						int lx = cardCenterX - this.font.width(imgLabel) / 2;
						int ly = cardY + cardH - layout.h(12);
						g.drawString(this.font, imgLabel, lx, ly, 0xFF55FFFF);
					}
				}
			}

			// Master section (up to 3 tracks: Raids, Guild Wars, Missions)
			List<LongEventState.Master> masters = state.masters();
			if (!masters.isEmpty()) {
				g.drawString(this.font, "Event Objectives", layout.x(12), layout.y(200), 0xFFFFD24A);
				LoadedTexture masterFrame = getTexture("assets/edenmod/textures/gui/event/frame_master.png");
				for (int idx = 0; idx < Math.min(3, masters.size()); idx++) {
					LongEventState.Master master = masters.get(idx);
					int rowX = layout.x(12);
					int rowY = layout.y(212 + idx * 35);
					int rowW = layout.w(456);
					int rowH = layout.h(32);

					boolean mDone = master.completed();
					g.fill(rowX, rowY, rowX + rowW, rowY + rowH, 0x66161616);
					drawOutline(g, rowX, rowY, rowW, rowH, mDone ? 0xFF3A8A3A : 0xFF4A4A4A);

					int fSize = layout.w(26);
					int fx = rowX + layout.w(3);
					int fy = rowY + (rowH - fSize) / 2;
					LoadedTexture mIcon = iconForMaster(master.kind());
					if (mIcon != null) {
						int iSize = layout.w(18);
						drawTexture(g, mIcon, fx + (fSize - iSize) / 2, fy + (fSize - iSize) / 2, iSize, iSize);
					}
					if (masterFrame != null) {
						drawTexture(g, masterFrame, fx, fy, fSize, fSize);
					}

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
		}

		popReferencePose(g);

		// Render hover tooltips / image previews in native screen space
		if (hoveredRerollSlotName != null) {
			g.setComponentTooltipForNextFrame(this.font, buildRerollTooltip(hoveredRerollSlotName, hoveredRerollSlot, nowMs), mouseX, mouseY);
		} else if (hoveredImageUrl != null) {
			if (ImagePreviewManager.isPreviewable(hoveredImageUrl)) {
				int imgW = ImagePreviewManager.getWidth(hoveredImageUrl);
				int imgH = ImagePreviewManager.getHeight(hoveredImageUrl);
				int boxW = imgW > 0 ? imgW : 100;
				int boxH = imgH > 0 ? imgH : 16;
				int rx = (this.width - boxW) / 2;
				int ry = (this.height - boxH) / 2;
				g.fill(rx - 2, ry - 2, rx + boxW + 2, ry + boxH + 2, 0xCC000000);
				ImagePreviewManager.renderPreview(g, hoveredImageUrl, rx, ry);
			} else {
				g.setComponentTooltipForNextFrame(this.font, List.of(Component.literal("Click to open image").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
			}
		} else if (hoveredNoteSlot != null) {
			g.setComponentTooltipForNextFrame(this.font, buildNoteTooltip(hoveredNoteSlot), mouseX, mouseY);
		}
	}

	private List<Component> buildNoteTooltip(LongEventState.Slot slot) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.literal("Mission Instructions").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		lines.add(Component.empty());
		for (String wrappedLine : wrapWords(slot.note(), 220)) {
			lines.add(Component.literal(wrappedLine).withStyle(ChatFormatting.WHITE));
		}
		if (!slot.imageUrl().isEmpty()) {
			lines.add(Component.empty());
			lines.add(Component.literal("Image attached (click [Image] below to open)").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC));
		}
		return lines;
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
			if (cost > 0) {
				lines.add(Component.empty());
				if (slot.canBuyReroll()) {
					lines.add(Component.literal("Shift-Click to buy extra reroll").withStyle(ChatFormatting.AQUA));
					lines.add(Component.literal("Cost: " + cost + " Trivia Points (You have " + pts + ")").withStyle(ChatFormatting.DARK_AQUA));
				} else {
					lines.add(Component.literal("Shift-Click to buy extra reroll").withStyle(ChatFormatting.DARK_GRAY));
					lines.add(Component.literal("Requires " + cost + " Trivia Points (You have " + pts + ")").withStyle(ChatFormatting.RED));
				}
			}
		}
		return lines;
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

	private static void drawTexture(GuiGraphics g, LoadedTexture tex, int x, int y, int w, int h) {
		if (tex == null || tex.id() == null) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(w / (float) tex.width(), h / (float) tex.height());
		g.blit(RenderPipelines.GUI_TEXTURED, tex.id(), 0, 0, 0.0f, 0.0f, tex.width(), tex.height(), tex.width(), tex.height());
		g.pose().popMatrix();
	}

	private static LoadedTexture frameForSlot(String slotKey) {
		String file = switch (slotKey.toLowerCase(Locale.ROOT)) {
			case "daily" -> "frame_daily.png";
			case "special", "weekly" -> "frame_weekly.png";
			default -> "frame_repeatable.png";
		};
		return getTexture("assets/edenmod/textures/gui/event/" + file);
	}

	private static LoadedTexture iconForTag(String tag) {
		if (tag == null) {
			return getTexture("assets/edenmod/textures/gui/event/other.png");
		}
		return switch (tag.trim().toUpperCase(Locale.ROOT)) {
			case "NOTG" -> getTexture("assets/edenmod/textures/gui/icons/notg.png");
			case "NOL" -> getTexture("assets/edenmod/textures/gui/icons/nol.png");
			case "TCC" -> getTexture("assets/edenmod/textures/gui/icons/tcc.png");
			case "TNA" -> getTexture("assets/edenmod/textures/gui/icons/tna.png");
			case "WTP" -> getTexture("assets/edenmod/textures/gui/icons/wtp.png");
			case "ALL RAIDS" -> getTexture("assets/edenmod/textures/gui/event/all_raids.png");
			case "GUILD WARS" -> getTexture("assets/edenmod/textures/gui/event/guild_wars.png");
			case "TRIVIA" -> getTexture("assets/edenmod/textures/gui/event/trivia.png");
			case "DICEROLL" -> getTexture("assets/edenmod/textures/gui/event/diceroll.png");
			case "COINFLIP" -> getTexture("assets/edenmod/textures/gui/event/coinflip.png");
			default -> getTexture("assets/edenmod/textures/gui/event/other.png");
		};
	}

	private static LoadedTexture iconForMaster(String kind) {
		if ("raids".equalsIgnoreCase(kind)) {
			return getTexture("assets/edenmod/textures/gui/event/all_raids.png");
		}
		if ("wars".equalsIgnoreCase(kind) || "guild_wars".equalsIgnoreCase(kind)) {
			return getTexture("assets/edenmod/textures/gui/event/guild_wars.png");
		}
		return getTexture("assets/edenmod/icon.png");
	}

	private static final String[] PRELOAD_TEXTURES = {"assets/edenmod/textures/gui/event/frame_daily.png", "assets/edenmod/textures/gui/event/frame_weekly.png", "assets/edenmod/textures/gui/event/frame_repeatable.png", "assets/edenmod/textures/gui/event/frame_master.png", "assets/edenmod/textures/gui/event/all_raids.png", "assets/edenmod/textures/gui/event/guild_wars.png", "assets/edenmod/textures/gui/event/trivia.png", "assets/edenmod/textures/gui/event/diceroll.png", "assets/edenmod/textures/gui/event/coinflip.png", "assets/edenmod/textures/gui/event/other.png", "assets/edenmod/textures/gui/icons/notg.png", "assets/edenmod/textures/gui/icons/nol.png", "assets/edenmod/textures/gui/icons/tcc.png", "assets/edenmod/textures/gui/icons/tna.png", "assets/edenmod/textures/gui/icons/wtp.png", "assets/edenmod/icon.png"};

	public static void preloadTextures() {
		for (String path : PRELOAD_TEXTURES) {
			getTexture(path);
		}
	}

	private static LoadedTexture getTexture(String resourcePath) {
		LoadedTexture cached = TEXTURE_CACHE.get(resourcePath);
		if (cached != null) {
			if (cached == LoadedTexture.EMPTY) {
				return null;
			}
			var tm = Minecraft.getInstance().getTextureManager();
			var tex = tm.getTexture(cached.id());
			if (tex != null && tex.getTexture() != null) {
				return cached;
			}
			TEXTURE_CACHE.remove(resourcePath);
		}
		try (InputStream stream = LongEventScreen.class.getClassLoader().getResourceAsStream(resourcePath)) {
			if (stream != null) {
				NativeImage img = NativeImage.read(stream);
				int w = img.getWidth();
				int h = img.getHeight();
				String slug = resourcePath.replace("assets/edenmod/", "").replace('/', '_').replace('.', '_');
				Identifier loc = Identifier.parse("edenmod:event_tex/" + slug);
				Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(() -> slug, img));
				LoadedTexture loaded = new LoadedTexture(loc, w, h);
				TEXTURE_CACHE.put(resourcePath, loaded);
				return loaded;
			}
		} catch (Exception ignored) {
		}
		TEXTURE_CACHE.put(resourcePath, LoadedTexture.EMPTY);
		return null;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		MouseButtonEvent scaled = rescale(event);
		if (scaled.button() == 0 && state != null && state.active()) {
			double mx = scaled.x();
			double my = scaled.y();
			for (int i = 0; i < SLOT_ORDER.length; i++) {
				String slotKey = SLOT_ORDER[i];
				if ("repeatable2".equals(slotKey) && (state == null || !state.repeatSlot2Active())) {
					continue;
				}
				LongEventState.Slot slot = state.findSlot(slotKey);
				if (slot == null || slot.missionId() == 0) {
					continue;
				}
				ClickBox rBox = rerollBox(i);
				if (rBox != null && rBox.contains(mx, my)) {
					if (mod.socket() != null) {
						boolean shift = RaidGambitSafety.isShiftKeyDown();
						if (slot.canReroll()) {
							this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
							mod.socket().sendLongEventReroll(slotKey, false);
							return true;
						} else if (shift && slot.canBuyReroll()) {
							this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(CLICK_SOUND, 1.0f));
							mod.socket().sendLongEventReroll(slotKey, true);
							return true;
						}
					}
					return true;
				}
				if (!slot.imageUrl().isEmpty()) {
					ClickBox imgBox = imageLinkBox(i);
					if (imgBox != null && imgBox.contains(mx, my)) {
						openUrlSafely(slot.imageUrl());
						return true;
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
		return super.mouseScrolled(mouseX / uiScale, mouseY / uiScale, d, e);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
