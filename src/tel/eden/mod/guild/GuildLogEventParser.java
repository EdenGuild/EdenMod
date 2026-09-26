package tel.eden.mod.guild;

import java.util.Optional;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;

/** Parses the high-volume, reconciliation-relevant Guild Log rows into typed metadata. */
public final class GuildLogEventParser {
	// Confirmed against real production log text: a reward row is a single line,
	// "X rewarded Y to Z" — no trailing "from Guild Rewards" (an earlier, never-verified
	// assumption that silently dropped every real reward row). Kept optional rather
	// than removed outright in case some reward shape does append it. The reward
	// portion itself is captured loosely (.+?) and classified separately below by
	// REWARD_ASPECTS/REWARD_TOMES/REWARD_EMERALDS: raid loot elsewhere in this same
	// menu system is quantified as "Nx Aspects", so a reward row plausibly uses that
	// phrasing too (not just the singular "an Aspect"/"a Guild Tome") — better to
	// accept both than to silently drop a real row we'd only ever seen one variant of.
	// Giver/receiver are captured loosely (.+?), not as a strict username charset:
	// Wynncraft nicknames can contain spaces (confirmed in production — a row read
	// "catboy rewarded 1024 Emeralds to buddy jingu", where "buddy jingu" is one
	// player's nickname), and a strict charset silently dropped the whole row as
	// unrecognized instead of just failing to resolve the name.
	private static final Pattern REWARD = Pattern.compile("^(.+?) rewarded (.+?) to (.+?)(?:\\s+from Guild Rewards)?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern REWARD_ASPECTS = Pattern.compile("^(?:an|(\\d+)x) Aspects?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern REWARD_TOMES = Pattern.compile("^(?:a|(\\d+)x) Guild Tomes?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern REWARD_EMERALDS = Pattern.compile("^(\\d+) Emeralds$", Pattern.CASE_INSENSITIVE);
	// Same nickname-with-spaces reasoning as REWARD above.
	private static final Pattern BANK = Pattern.compile("^(.+?) (deposited|withdrew) (?:(\\d+)x )?(.+?) (?:to|from) the Guild Bank \\((.+)\\)$", Pattern.CASE_INSENSITIVE);
	// Mirrors BankEventParser.CHARGES: peels a trailing "[3/3]"-style bracket off the
	// item name. Without this, a bracketed item's semantic key never matches the
	// ticker-parsed version (which already strips it), defeating cross-path dedup
	// against a live bank event and double-reporting the same withdrawal/deposit.
	private static final Pattern CHARGES = Pattern.compile("^(.*?)(?:\\s+\\[([^\\]]+)\\])?$");
	private static final Pattern RAID = Pattern.compile("^(.+?) finished (.+?) and claimed (.+)$", Pattern.CASE_INSENSITIVE);
	private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	private static final Pattern ASPECTS = Pattern.compile("(\\d+)x Aspects", Pattern.CASE_INSENSITIVE);
	private static final Pattern EMERALDS = Pattern.compile("(\\d[\\d,]*)x Emeralds", Pattern.CASE_INSENSITIVE);
	private static final Pattern GUILD_EXPERIENCE = Pattern.compile("\\+([\\d,]+)m Guild Experience", Pattern.CASE_INSENSITIVE);
	private static final Pattern SEASONAL_RATING = Pattern.compile("\\+(\\d[\\d,]*) Seasonal Rating", Pattern.CASE_INSENSITIVE);

	private GuildLogEventParser() {
	}

	public static Optional<GuildLogEvent> parse(GuildLogEntry entry) {
		if (entry == null)
			return Optional.empty();
		String text = normalized(entry.text());
		Matcher reward = REWARD.matcher(text);
		if (reward.matches()) {
			String rewardText = reward.group(2).trim();
			Matcher aspects = REWARD_ASPECTS.matcher(rewardText);
			Matcher tomes = REWARD_TOMES.matcher(rewardText);
			Matcher emeralds = REWARD_EMERALDS.matcher(rewardText);
			String kind;
			int amount;
			if (aspects.matches()) {
				kind = "aspects";
				amount = aspects.group(1) == null ? 1 : Integer.parseInt(aspects.group(1));
			} else if (tomes.matches()) {
				kind = "tomes";
				amount = tomes.group(1) == null ? 1 : Integer.parseInt(tomes.group(1));
			} else if (emeralds.matches()) {
				kind = "emeralds";
				amount = Integer.parseInt(emeralds.group(1));
			} else {
				return Optional.empty();
			}
			return Optional.of(new GuildLogEvent.Reward(entry.occurredAt(), reward.group(1), reward.group(3), kind, amount));
		}
		Matcher bank = BANK.matcher(text);
		if (bank.matches()) {
			Integer quantity = bank.group(3) == null ? null : Integer.valueOf(bank.group(3));
			Matcher charges = CHARGES.matcher(bank.group(4).trim());
			String item = charges.matches() ? charges.group(1).trim() : bank.group(4).trim();
			String chargesValue = charges.matches() ? charges.group(2) : null;
			return Optional.of(new GuildLogEvent.Bank(entry.occurredAt(), bank.group(1), bank.group(2).toLowerCase(java.util.Locale.ROOT), quantity, item, chargesValue, bank.group(5).trim()));
		}
		Matcher raid = RAID.matcher(text);
		if (raid.matches()) {
			List<String> participants = participants(raid.group(1));
			if (participants.isEmpty())
				return Optional.empty();
			String rewards = raid.group(3);
			return Optional.of(new GuildLogEvent.Raid(entry.occurredAt(), participants, raid.group(2).trim(), number(ASPECTS, rewards), number(EMERALDS, rewards), longNumber(GUILD_EXPERIENCE, rewards), number(SEASONAL_RATING, rewards)));
		}
		return Optional.empty();
	}

	private static List<String> participants(String names) {
		return java.util.Arrays.stream(names.replace(", and ", ",").split("(?:,\\s*|\\s+and\\s+)")).map(String::trim).filter(name -> USERNAME.matcher(name).matches()).toList();
	}

	private static Integer number(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? Integer.valueOf(matcher.group(1).replace(",", "")) : null;
	}

	private static Long longNumber(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? Long.valueOf(matcher.group(1).replace(",", "")) : null;
	}

	private static String normalized(String text) {
		// Wynncraft prepends wrapped lore lines with private-use icon glyphs. They can
		// occur in the middle of words after the client joins lines, so remove every
		// non-basic-Latin character before matching canonical server text.
		return ChatFormatting.stripFormatting(text == null ? "" : text).replaceAll("[^\\x20-\\x7E]", " ").replaceAll("\\s+", " ").trim();
	}
}
