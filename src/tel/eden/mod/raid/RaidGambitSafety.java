package tel.eden.mod.raid;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.config.BridgeConfig;

/**
 * Enforces raid gambit and guild raid ready safety in Wynncraft raid lobbies.
 *
 * <p>Prevents players from accidentally readying up without their desired minimum number
 * of gambits enabled, or into a non-guild raid when guild raid safety is enabled.
 * Chest slots 33, 34, and 35 are the clickable ready buttons in the raid lobby menu.
 */
public final class RaidGambitSafety {
	public static final int READY_SLOT_MIN = 33;
	public static final int READY_SLOT_MAX = 35;
	public static final int STATUS_INDICATOR_SLOT = 27;
	public static final int CONTAINER_SLOTS = 36;
	public static final int TOTAL_SLOTS = 72;

	private static final Identifier LOGO_TEXTURE = Identifier.parse("edenmod:icon.png");
	private static final int LOGO_SRC_SIZE = 256;
	private static List<String> activePopupMessages = null;
	private static long popupExpiresAtMs = 0L;

	private RaidGambitSafety() {
	}

	/** Returns true if the menu matches the Wynncraft raid preparation container. */
	public static boolean isRaidReadyMenu(AbstractContainerMenu menu) {
		if (menu == null || menu.slots.size() < TOTAL_SLOTS) {
			return false;
		}
		Slot statusSlot = menu.getSlot(STATUS_INDICATOR_SLOT);
		if (statusSlot == null || statusSlot.getItem().isEmpty()) {
			return false;
		}
		String statusName = ChatFormatting.stripFormatting(statusSlot.getItem().getHoverName().getString()).trim();
		if (!statusName.equalsIgnoreCase("Not Ready!") && !statusName.equalsIgnoreCase("Ready!")) {
			return false;
		}
		boolean hasGambit = false;
		for (int i = 0; i < Math.min(CONTAINER_SLOTS, menu.slots.size()); i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (!stack.isEmpty() && isGambitItem(stack)) {
				hasGambit = true;
				break;
			}
		}
		return hasGambit;
	}

	/** Checks whether an item stack represents a gambit option. */
	public static boolean isGambitItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		String name = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
		if (name.endsWith("Gambit") || name.contains("Gambit")) {
			return true;
		}
		return isGambitLore(getLoreLines(stack));
	}

	/** Checks whether an item lore contains gambit status indicators. */
	public static boolean isGambitLore(List<String> loreLines) {
		if (loreLines == null) {
			return false;
		}
		for (String line : loreLines) {
			String stripped = ChatFormatting.stripFormatting(line).trim();
			if (stripped.equalsIgnoreCase("Click to Disable") || stripped.equalsIgnoreCase("Click to Enable") || stripped.contains("Rewards for Enabling") || stripped.contains("You may only enable 3 gambits")) {
				return true;
			}
		}
		return false;
	}

	/** Checks if a gambit is currently active (has "Click to Disable" in lore). */
	public static boolean isGambitEnabled(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		return isGambitEnabledLore(getLoreLines(stack));
	}

	/** Checks if lore lines include the active "Click to Disable" prompt. */
	public static boolean isGambitEnabledLore(List<String> loreLines) {
		if (loreLines == null) {
			return false;
		}
		for (String line : loreLines) {
			String stripped = ChatFormatting.stripFormatting(line).trim();
			if (stripped.equalsIgnoreCase("Click to Disable")) {
				return true;
			}
		}
		return false;
	}

	/** Counts the number of active gambits (0 to 3) in the raid container. */
	public static int countEnabledGambits(AbstractContainerMenu menu) {
		if (menu == null) {
			return 0;
		}
		int count = 0;
		for (int i = 0; i < Math.min(CONTAINER_SLOTS, menu.slots.size()); i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (isGambitEnabled(stack)) {
				count++;
			}
		}
		return count;
	}

	/** Checks if the clicked slot is one of the ready-up buttons (slots 33, 34, 35). */
	public static boolean isReadyClickSlot(Slot slot) {
		return slot != null && slot.index >= READY_SLOT_MIN && slot.index <= READY_SLOT_MAX;
	}

	/** Checks if the player is already readied up (in which case clicking unreadies). */
	public static boolean isPlayerAlreadyReadied(AbstractContainerMenu menu) {
		if (menu == null || menu.slots.size() < TOTAL_SLOTS) {
			return false;
		}
		Slot statusSlot = menu.getSlot(STATUS_INDICATOR_SLOT);
		if (statusSlot != null && !statusSlot.getItem().isEmpty()) {
			String statusName = ChatFormatting.stripFormatting(statusSlot.getItem().getHoverName().getString()).trim();
			if (statusName.equalsIgnoreCase("Ready!")) {
				return true;
			}
		}
		for (int i = READY_SLOT_MIN; i <= READY_SLOT_MAX; i++) {
			if (i < menu.slots.size()) {
				Slot s = menu.getSlot(i);
				if (s != null && !s.getItem().isEmpty()) {
					String name = ChatFormatting.stripFormatting(s.getItem().getHoverName().getString()).toLowerCase(Locale.ROOT);
					if (name.contains("unready")) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Checks whether either Shift key is currently held down. */
	public static boolean isShiftKeyDown() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.getWindow() != null) {
				return InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT) || InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	/** Checks whether item lore lines indicate guild raid availability. */
	public static boolean isGuildRaidLore(List<String> loreLines) {
		if (loreLines == null) {
			return false;
		}
		for (String line : loreLines) {
			if (line != null && ChatFormatting.stripFormatting(line).toLowerCase(Locale.ROOT).contains("guild raid available")) {
				return true;
			}
		}
		return false;
	}

	/** Checks whether an item stack represents the guild raid status indicator. */
	public static boolean isGuildRaidItem(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		String name = ChatFormatting.stripFormatting(stack.getHoverName().getString()).toLowerCase(Locale.ROOT);
		if (name.contains("guild raid available")) {
			return true;
		}
		return isGuildRaidLore(getLoreLines(stack));
	}

	/** Checks if the raid preparation menu has a guild raid available and all players are opted in. */
	public static boolean isGuildRaidAvailable(AbstractContainerMenu menu) {
		if (menu == null) {
			return false;
		}
		for (int i = 0; i < Math.min(CONTAINER_SLOTS, menu.slots.size()); i++) {
			Slot slot = menu.getSlot(i);
			if (slot == null) {
				continue;
			}
			ItemStack stack = slot.getItem();
			if (!stack.isEmpty() && isGuildRaidItem(stack)) {
				List<String> lore = getLoreLines(stack);
				return lore.isEmpty() || isAllGuildMembersOptedIn(lore);
			}
		}
		return false;
	}

	/** Checks whether all players have opted in to the guild raid (no ✖ / opted out indicators). */
	public static boolean isAllGuildMembersOptedIn(List<String> loreLines) {
		if (loreLines == null || loreLines.isEmpty()) {
			return false;
		}
		for (String line : loreLines) {
			if (line == null) {
				continue;
			}
			String stripped = ChatFormatting.stripFormatting(line).trim();
			if (stripped.contains("✖") || stripped.contains("\u2716") || stripped.toLowerCase(Locale.ROOT).contains("opted out")) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Returns active block reason messages in priority order (minimum gambit error first,
	 * guild raid safety error second).
	 */
	public static List<String> getActiveBlockReasons(int slotIndex, boolean isRaidMenu, boolean isAlreadyReadied, int enabledGambits, boolean isGuildRaid, ClickType clickType, boolean shiftHeld, BridgeConfig config) {
		if (config == null || slotIndex < READY_SLOT_MIN || slotIndex > READY_SLOT_MAX || !isRaidMenu || isAlreadyReadied) {
			return List.of();
		}
		boolean isShift = (clickType == ClickType.QUICK_MOVE || shiftHeld);
		List<String> reasons = new ArrayList<>(2);

		if (!isShift && config.raidGambitSafety && config.raidGambitMinimum > 0 && enabledGambits < config.raidGambitMinimum) {
			reasons.add(getWarningMessage(config.raidGambitMinimum));
		}

		if (config.requireGuildRaid && !isGuildRaid) {
			reasons.add(getGuildRaidWarningMessage());
		}

		return reasons;
	}

	/** Returns active block reason messages for a clicked container slot. */
	public static List<String> getActiveBlockReasons(AbstractContainerMenu menu, Slot slot, ClickType clickType, BridgeConfig config) {
		if (config == null || !isReadyClickSlot(slot) || !isRaidReadyMenu(menu) || isPlayerAlreadyReadied(menu)) {
			return List.of();
		}
		int enabled = countEnabledGambits(menu);
		boolean isGuildRaid = isGuildRaidAvailable(menu);
		return getActiveBlockReasons(slot.index, true, false, enabled, isGuildRaid, clickType, isShiftKeyDown(), config);
	}

	/** Evaluates whether a ready click should be blocked based on active gambits and settings. */
	public static boolean evaluateSafetyBlock(int slotIndex, boolean isRaidMenu, boolean isAlreadyReadied, int enabledGambits, ClickType clickType, boolean shiftHeld, BridgeConfig config) {
		return evaluateSafetyBlock(slotIndex, isRaidMenu, isAlreadyReadied, enabledGambits, true, clickType, shiftHeld, config);
	}

	/** Evaluates whether a ready click should be blocked based on active gambits, guild raid status, and settings. */
	public static boolean evaluateSafetyBlock(int slotIndex, boolean isRaidMenu, boolean isAlreadyReadied, int enabledGambits, boolean isGuildRaid, ClickType clickType, boolean shiftHeld, BridgeConfig config) {
		return !getActiveBlockReasons(slotIndex, isRaidMenu, isAlreadyReadied, enabledGambits, isGuildRaid, clickType, shiftHeld, config).isEmpty();
	}

	/** Determines whether the clicked slot should be blocked from readying up. */
	public static boolean shouldBlockReadyClick(AbstractContainerMenu menu, Slot slot, ClickType clickType, BridgeConfig config) {
		return !getActiveBlockReasons(menu, slot, clickType, config).isEmpty();
	}

	/** Generates the user-facing warning message with correct singular/plural phrasing. */
	public static String getWarningMessage(int minRequired) {
		String noun = (minRequired == 1) ? "gambit" : "gambits";
		return "You have less than " + minRequired + " " + noun + " selected, Shift-Click to ready up anyways.";
	}

	/** Generates the colored tooltip warning component. */
	public static Component getTooltipWarning(int minRequired) {
		return Component.literal("§c" + getWarningMessage(minRequired));
	}

	/** Generates the user-facing warning message for non-guild raid readying. */
	public static String getGuildRaidWarningMessage() {
		return "This raid is not a Guild Raid, disable Require guild raid to ready in settings to ready up anyways.";
	}

	/** Generates the colored tooltip warning component for non-guild raid readying. */
	public static Component getGuildRaidTooltipWarning() {
		return Component.literal("§c" + getGuildRaidWarningMessage());
	}

	/** Sets active popup warning message(s) to display at the top of the screen. */
	public static void showPopupWarning(List<String> messages) {
		if (messages != null && !messages.isEmpty()) {
			activePopupMessages = List.copyOf(messages);
			popupExpiresAtMs = System.currentTimeMillis() + 3500L;
		}
	}

	/** Returns current active popup warning messages if active and not expired. */
	public static List<String> getActivePopupMessages() {
		if (activePopupMessages != null && System.currentTimeMillis() < popupExpiresAtMs) {
			return activePopupMessages;
		}
		return null;
	}

	/** Clears active popup warning state. */
	public static void clearPopupWarning() {
		activePopupMessages = null;
		popupExpiresAtMs = 0L;
	}

	/** Renders top-of-screen popup box warning with the Eden logo and gold/wood frame. */
	public static void renderPopupWarning(GuiGraphics graphics, Font font, int screenWidth) {
		List<String> messages = getActivePopupMessages();
		if (messages == null || messages.isEmpty() || graphics == null || font == null) {
			return;
		}

		int logoSize = 24;
		int maxTextWidth = Math.max(120, screenWidth - (logoSize + 50));
		List<FormattedCharSequence> wrappedLines = new ArrayList<>();
		for (String msg : messages) {
			wrappedLines.addAll(font.split(Component.literal("§c" + msg), maxTextWidth));
		}

		int maxLineWidth = 0;
		for (FormattedCharSequence line : wrappedLines) {
			maxLineWidth = Math.max(maxLineWidth, font.width(line));
		}

		int boxWidth = Math.min(screenWidth - 20, maxLineWidth + logoSize + 28);
		int lineHeight = font.lineHeight;
		int textTotalHeight = wrappedLines.size() * (lineHeight + 2) - 2;
		int boxHeight = Math.max(logoSize + 12, textTotalHeight + 14);
		int boxX = (screenWidth - boxWidth) / 2;
		int boxY = 6;

		// Outer dark wood outline with clipped 1px corners
		graphics.fill(boxX + 1, boxY, boxX + boxWidth - 1, boxY + boxHeight, 0xFF3C2812);
		graphics.fill(boxX, boxY + 1, boxX + boxWidth, boxY + boxHeight - 1, 0xFF3C2812);

		// Outer wood base
		graphics.fill(boxX + 1, boxY + 1, boxX + boxWidth - 1, boxY + boxHeight - 1, 0xFF5A3E1E);

		// Gold border
		graphics.fill(boxX + 2, boxY + 2, boxX + boxWidth - 2, boxY + boxHeight - 2, 0xFFC6973A);

		// Gold highlights (top & left)
		graphics.fill(boxX + 2, boxY + 2, boxX + boxWidth - 2, boxY + 3, 0xFFF8DC82);
		graphics.fill(boxX + 2, boxY + 2, boxX + 3, boxY + boxHeight - 2, 0xFFF8DC82);

		// Gold shadows (bottom & right)
		graphics.fill(boxX + 2, boxY + boxHeight - 3, boxX + boxWidth - 2, boxY + boxHeight - 2, 0xFF8A621E);
		graphics.fill(boxX + boxWidth - 3, boxY + 2, boxX + boxWidth - 2, boxY + boxHeight - 2, 0xFF8A621E);

		// Inner dark wood rim
		graphics.fill(boxX + 4, boxY + 4, boxX + boxWidth - 4, boxY + boxHeight - 4, 0xFF281A0B);

		// Interior dark background
		graphics.fill(boxX + 5, boxY + 5, boxX + boxWidth - 5, boxY + boxHeight - 5, 0xF214110E);

		// Eden logo on left
		int logoX = boxX + 8;
		int logoY = boxY + (boxHeight - logoSize) / 2;
		graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, logoX, logoY, 0.0f, 0.0f, logoSize, logoSize, LOGO_SRC_SIZE, LOGO_SRC_SIZE, LOGO_SRC_SIZE, LOGO_SRC_SIZE);

		// Text lines to the right of logo
		int textStartX = logoX + logoSize + 7;
		int textStartY = boxY + (boxHeight - textTotalHeight) / 2;
		for (int i = 0; i < wrappedLines.size(); i++) {
			FormattedCharSequence line = wrappedLines.get(i);
			int lineY = textStartY + i * (lineHeight + 2);
			graphics.drawString(font, line, textStartX, lineY, 0xFFFFFFFF, true);
		}
	}

	/** Plays rejection sound when a ready click is blocked. */
	public static void notifyBlocked(Minecraft mc) {
		if (mc == null) {
			return;
		}
		try {
			if (mc.getSoundManager() != null) {
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS, 0.8f));
			}
		} catch (Throwable ignored) {
		}
	}

	/** Plays rejection sound when a ready click is blocked. */
	public static void notifyBlocked(Minecraft mc, int minRequired) {
		notifyBlocked(mc);
	}

	static List<String> getLoreLines(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return List.of();
		}
		ItemLore lore = stack.get(DataComponents.LORE);
		return lore == null ? List.of() : lore.lines().stream().map(Component::getString).toList();
	}
}
