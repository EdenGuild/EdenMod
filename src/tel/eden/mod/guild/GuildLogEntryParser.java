package tel.eden.mod.guild;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/** Extracts a guild-log row from its menu item without looking at any player slots. */
public final class GuildLogEntryParser {
	// The timestamp is the Paper item's coloured hover name, not its lore. Matches the
	// format Wynncraft uses for every guild-log row, e.g. "2 hours ago (09/22/26 09:30 AM EDT)".
	private static final Pattern TIMESTAMP = Pattern.compile("\\d+ (?:month|week|day|hour|minute|second)s? ago \\(\\d{1,2}/\\d{1,2}/\\d{2} \\d{1,2}:\\d{2} (?:AM|PM) (?:EST|EDT)\\)");
	private GuildLogEntryParser() {
	}

	/**
	 * The lore format is {@code time}, one blank line, then one or more actual log lines.
	 * Empty/spacer items return empty rather than becoming synthetic log records.
	 */
	public static Optional<GuildLogEntry> parse(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return Optional.empty();
		}
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null || lore.lines().isEmpty()) {
			return Optional.empty();
		}
		List<String> lines = new ArrayList<>();
		for (var line : lore.lines()) {
			lines.add(line.getString());
		}
		return parseLines(stack.getHoverName().getString(), lines);
	}

	/** Visible for tests; callers normally use {@link #parse(ItemStack)}. */
	static Optional<GuildLogEntry> parseLines(String occurredAt, List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return Optional.empty();
		}
		if (!TIMESTAMP.matcher(ChatFormatting.stripFormatting(occurredAt)).matches()) {
			return Optional.empty();
		}
		// Wynncraft pads lore with exactly one empty line at either end.
		int firstText = lines.getFirst().trim().isEmpty() ? 1 : 0;
		int lastText = lines.getLast().trim().isEmpty() ? lines.size() - 1 : lines.size();
		if (firstText >= lastText) {
			return Optional.empty();
		}
		List<String> text = lines.subList(firstText, lastText).stream().map(String::trim).filter(line -> !line.isEmpty()).toList();
		return text.isEmpty() ? Optional.empty() : Optional.of(new GuildLogEntry(ChatFormatting.stripFormatting(occurredAt), String.join("\n", text)));
	}
}
