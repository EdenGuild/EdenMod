package tel.eden.mod.raid;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.config.BridgeConfig;

/**
 * Enforces raid gambit ready safety in Wynncraft raid lobbies.
 *
 * <p>Prevents players from accidentally readying up without their desired minimum number
 * of gambits enabled. Chest slots 33, 34, and 35 are the clickable ready buttons in the
 * raid lobby menu. If fewer than {@link BridgeConfig#raidGambitMinimum} gambits are enabled,
 * clicking to ready up requires holding Shift to confirm.
 */
public final class RaidGambitSafety {
	public static final int READY_SLOT_MIN = 33;
	public static final int READY_SLOT_MAX = 35;
	public static final int STATUS_INDICATOR_SLOT = 27;
	public static final int CONTAINER_SLOTS = 36;
	public static final int TOTAL_SLOTS = 72;

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

	/** Checks if the raid preparation menu has a guild raid available and all players are opted in. */
	public static boolean isGuildRaidAvailable(AbstractContainerMenu menu) {
		if (menu == null) {
			return false;
		}
		for (int i = 0; i < Math.min(CONTAINER_SLOTS, menu.slots.size()); i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (!stack.isEmpty()) {
				String name = ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim();
				if (name.equalsIgnoreCase("Guild Raid Available") || name.contains("Guild Raid Available")) {
					return isAllGuildMembersOptedIn(getLoreLines(stack));
				}
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

	/** Evaluates whether a ready click should be blocked based on active gambits and settings. */
	public static boolean evaluateSafetyBlock(int slotIndex, boolean isRaidMenu, boolean isAlreadyReadied, int enabledGambits, ClickType clickType, boolean shiftHeld, BridgeConfig config) {
		return evaluateSafetyBlock(slotIndex, isRaidMenu, isAlreadyReadied, enabledGambits, true, clickType, shiftHeld, config);
	}

	/** Evaluates whether a ready click should be blocked based on active gambits, guild raid status, and settings. */
	public static boolean evaluateSafetyBlock(int slotIndex, boolean isRaidMenu, boolean isAlreadyReadied, int enabledGambits, boolean isGuildRaid, ClickType clickType, boolean shiftHeld, BridgeConfig config) {
		if (config == null) {
			return false;
		}
		if (slotIndex < READY_SLOT_MIN || slotIndex > READY_SLOT_MAX) {
			return false;
		}
		if (!isRaidMenu) {
			return false;
		}
		if (isAlreadyReadied) {
			return false;
		}
		if (config.requireGuildRaid && !isGuildRaid) {
			return true;
		}
		boolean isShift = (clickType == ClickType.QUICK_MOVE || shiftHeld);
		if (isShift) {
			return false;
		}
		if (config.raidGambitSafety && config.raidGambitMinimum > 0 && enabledGambits < config.raidGambitMinimum) {
			return true;
		}
		return false;
	}

	/** Determines whether the clicked slot should be blocked from readying up. */
	public static boolean shouldBlockReadyClick(AbstractContainerMenu menu, Slot slot, ClickType clickType, BridgeConfig config) {
		if (config == null) {
			return false;
		}
		boolean gambitActive = config.raidGambitSafety && config.raidGambitMinimum > 0;
		if (!gambitActive && !config.requireGuildRaid) {
			return false;
		}
		if (!isReadyClickSlot(slot)) {
			return false;
		}
		if (!isRaidReadyMenu(menu)) {
			return false;
		}
		if (isPlayerAlreadyReadied(menu)) {
			return false;
		}
		int enabled = countEnabledGambits(menu);
		boolean isGuildRaid = isGuildRaidAvailable(menu);
		return evaluateSafetyBlock(slot.index, true, false, enabled, isGuildRaid, clickType, isShiftKeyDown(), config);
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

	private static List<String> getLoreLines(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		return lore == null ? List.of() : lore.lines().stream().map(Component::getString).toList();
	}
}
