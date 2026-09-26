package tel.eden.mod.guild;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/** Parses slot 27 of the 45-slot guild member-management menu. */
public final class GuildRewardStorageParser {
	public static final int STORAGE_SLOT = 27;
	// Comma-tolerant on both sides: emeralds in particular can run into the
	// hundreds of thousands, rendered like other large guild figures elsewhere
	// (e.g. Season Rating) with thousands separators.
	private static final Pattern COUNT = Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)");

	private GuildRewardStorageParser() {
	}

	private record Counts(long current, long max) {
	}

	public static Optional<GuildRewardStorageSnapshot> parse(List<ItemStack> items) {
		if (items == null || items.size() <= STORAGE_SLOT)
			return Optional.empty();
		ItemLore lore = items.get(STORAGE_SLOT).get(DataComponents.LORE);
		if (lore == null)
			return Optional.empty();
		Counts aspects = count(lore, "Aspects:");
		Counts tomes = count(lore, "Guild Tomes:");
		Counts emeralds = count(lore, "Emeralds:");
		if (aspects == null || tomes == null || emeralds == null)
			return Optional.empty();
		return Optional.of(new GuildRewardStorageSnapshot((int) aspects.current(), (int) aspects.max(), (int) tomes.current(), (int) tomes.max(), emeralds.current(), emeralds.max()));
	}

	private static Counts count(ItemLore lore, String key) {
		for (Component line : lore.lines()) {
			String text = line.getString();
			if (!text.contains(key))
				continue;
			Matcher matcher = COUNT.matcher(text);
			if (matcher.find())
				return new Counts(parseLong(matcher.group(1)), parseLong(matcher.group(2)));
		}
		return null;
	}

	private static long parseLong(String withCommas) {
		return Long.parseLong(withCommas.replace(",", ""));
	}
}
