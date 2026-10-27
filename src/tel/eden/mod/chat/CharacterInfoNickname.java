package tel.eden.mod.chat;

import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import tel.eden.mod.EdenLogger;

/**
 * Learns the local player's nickname from the Character Info menu (the compass in hotbar slot 8
 * opens it). Its slot 7 holds an item whose first lines read "{@code <rank> <nickname>}" followed
 * by an empty line and the character's levels and class. Wynntils identifies the same menu by its
 * title glyphs and reads the same slot, but only for the class and level lines.
 *
 * <p>Like in chat, a nickname is the italic part of that line, so it is taken from the item's name
 * and the lines above its first empty one by style, which avoids having to know how the rank is
 * written. Reads again each time the menu opens, so a class change is picked up the next time the
 * menu is looked at.
 */
public final class CharacterInfoNickname {
	private static final EdenLogger LOGGER = EdenLogger.get();
	// The Character Info menu's title, the same glyphs Wynntils matches (CharacterInfoContainer).
	public static final String TITLE_GLYPHS = "󏿜";
	public static final int INFO_SLOT = 7;
	private static final int MAX_NICKNAME_LENGTH = 40;
	private static int lastSeenContainerId = -1;

	private CharacterInfoNickname() {
	}

	/** The hotbar slot (0-based) whose compass opens the Character Info menu, the same one Wynntils uses. */
	public static final int HOTBAR_SLOT = 7;
	private static final java.util.regex.Pattern CHARACTER_ID = java.util.regex.Pattern.compile("[a-z0-9]{8}");

	/** Whether {@code stack} is the compass: its first lore line is the character id (the check Wynntils makes before using it). */
	public static boolean isCompass(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		return lore != null && !lore.lines().isEmpty() && CHARACTER_ID.matcher(ChatFormatting.stripFormatting(lore.lines().get(0).getString()).trim()).matches();
	}

	/** Client-thread tick: if the Character Info menu has just been opened, read our nickname from it. */
	public static void onTick(Minecraft mc) {
		if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || mc.player == null || !screen.getTitle().getString().contains(TITLE_GLYPHS)) {
			lastSeenContainerId = -1;
			return;
		}
		int containerId = screen.getMenu().containerId;
		if (containerId == lastSeenContainerId || screen.getMenu().slots.size() <= INFO_SLOT) {
			return;
		}
		ItemStack item = screen.getMenu().getSlot(INFO_SLOT).getItem();
		if (item.isEmpty()) {
			return;
		}
		lastSeenContainerId = containerId;
		learn(item, mc.player.getGameProfile().name());
	}

	/** Record the nickname found on the Character Info item for {@code localUsername}; visible for tests. */
	public static Optional<String> learn(ItemStack item, String localUsername) {
		ItemLore lore = item.get(DataComponents.LORE);
		List<Component> lines = lore == null ? List.of() : lore.lines();
		Optional<String> nickname = italicText(item.getHoverName());
		for (int i = 0; nickname.isEmpty() && i < lines.size() && !lines.get(i).getString().isBlank(); i++) {
			nickname = italicText(lines.get(i));
		}
		nickname.ifPresentOrElse(nick -> {
			LOGGER.info("Character info: nickname '{}' belongs to {}", nick, localUsername);
			PlayerNameResolver.recordAlias(nick, localUsername);
		}, () -> LOGGER.info("Character info: no italic nickname found for {}", localUsername));
		return nickname;
	}

	/** The italic text of {@code line}, trimmed, if there is any and it is a plausible nickname. */
	static Optional<String> italicText(Component line) {
		StringBuilder italic = new StringBuilder();
		line.visit((style, text) -> {
			if (style.isItalic()) {
				italic.append(text);
			}
			return Optional.empty();
		}, Style.EMPTY);
		String text = ChatFormatting.stripFormatting(italic.toString()).replaceAll("[^\\x20-\\x7E]", " ").replaceAll("\\s+", " ").trim();
		return text.isEmpty() || text.length() > MAX_NICKNAME_LENGTH ? Optional.empty() : Optional.of(text);
	}
}
