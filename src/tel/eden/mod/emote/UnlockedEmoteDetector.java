package tel.eden.mod.emote;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import tel.eden.mod.EdenModClient;

/**
 * Learns which {@code /emote} animations the player has unlocked by scanning Wynncraft's
 * emotes menu whenever it is open: each emote item's tooltip carries a "Command: /emote
 * &lt;name&gt;" line. Newly seen names are merged into the mod config so the emote-wheel
 * favorites picker ({@link EmoteConfigScreen}) can offer them.
 */
public final class UnlockedEmoteDetector {
	private UnlockedEmoteDetector() {
	}

	// Wynncraft's emotes menu title (private-use glyphs), same marker avomod2 keys off.
	private static final String EMOTES_TITLE = "\uDAFF\uDFF8\uE033\uDAFF\uDF80\uF016";
	private static final Pattern EMOTE_COMMAND = Pattern.compile("/emote ([A-Za-z0-9_]+)");

	private static int tickCounter;

	public static void onTick(Minecraft mc) {
		if (mc.player == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
			return;
		}
		if (!screen.getTitle().getString().equals(EMOTES_TITLE)) {
			return;
		}
		// The menu is static once open; a periodic scan is plenty and keeps this cheap.
		if (tickCounter++ % 20 != 0) {
			return;
		}
		List<String> unlocked = EdenModClient.instance().config().emoteUnlocked;
		boolean changed = false;
		for (Slot slot : screen.getMenu().slots) {
			ItemStack stack = slot.getItem();
			if (stack.isEmpty()) {
				continue;
			}
			for (Component line : stack.getTooltipLines(Item.TooltipContext.EMPTY, mc.player, TooltipFlag.NORMAL)) {
				Matcher matcher = EMOTE_COMMAND.matcher(line.getString());
				if (matcher.find()) {
					String name = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
					if (!unlocked.contains(name)) {
						unlocked.add(name);
						changed = true;
					}
				}
			}
		}
		if (changed) {
			List<String> sorted = new ArrayList<>(unlocked);
			sorted.sort(String::compareTo);
			EdenModClient.instance().config().emoteUnlocked = sorted;
			EdenModClient.instance().config().save();
		}
	}
}
