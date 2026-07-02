package tel.eden.mod.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * Detects guild raid completions in Wynncraft system chat and extracts the party,
 * raid name, and loot. Wynncraft sends these as multiline aqua announcements with
 * private-use glyph prefixes. The party is 1-4 players and the claimed loot varies
 * (any of aspects, emeralds, guild experience, seasonal rating may be present or
 * absent, in any order), e.g.:
 *
 * <pre>
 *   Player1, Player2, and Player3 finished Nest of the Grootslangs and claimed
 *   2x Aspects, 2048x Emeralds, +633m Guild Experience, and +440 Seasonal Rating
 *   Player1 finished Nest of the Grootslangs and claimed 2x Aspects,
 *   +158m Guild Experience, and +110 Seasonal Rating
 * </pre>
 *
 * <p>The displayed party names are <em>nicknames</em>; the real account usernames are
 * recovered from each name segment's hover text, exactly as for guild chat
 * ({@link GuildChatParser}).
 */
public final class RaidCompletionParser {
	// Detect a raid by "finished" closely followed by a distinctive keyword of one of
	// the five raids. This is robust because the loot — and sometimes the tail of the
	// raid name — is rendered in a custom icon font (private-use glyphs), so the literal
	// "claimed <loot>" text isn't reliably present in the raw chat component (Wynntils
	// only converts the glyphs to readable text after our capture hook runs).
	private static final Pattern RAID_DETECT = Pattern.compile("finished\\s+[^:]{0,30}?(Grootslang|Orphion|Canyon|Nameless|Wartorn)", Pattern.CASE_INSENSITIVE);
	private static final Map<String, String> RAID_BY_KEYWORD = Map.of("grootslang", "Nest of the Grootslangs", "orphion", "Orphion's Nexus of Light", "canyon", "The Canyon Colossus", "nameless", "The Nameless Anomaly", "wartorn", "The Wartorn Palace");
	private static final Pattern NAMES = Pattern.compile("^(.+?)\\s+finished\\b");
	// Loot figures, pulled best-effort (each absent when rendered as glyphs).
	private static final Pattern ASPECTS = Pattern.compile("(\\d+)x Aspects");
	private static final Pattern EMERALDS = Pattern.compile("(\\d+)x Emeralds");
	private static final Pattern GUILD_EXP = Pattern.compile("\\+([\\d.]+)m Guild Experience");
	private static final Pattern HOVER_REAL_NAME_PATTERN = Pattern.compile("(?:'(?:s)? real name is\\s+|Real Username:\\s*)([a-zA-Z0-9_]{3,16})", Pattern.CASE_INSENSITIVE);
	private static final Pattern IGN = Pattern.compile("[a-zA-Z0-9_]{3,16}");
	private static final Pattern COMMA = Pattern.compile("\\s*,\\s*");

	private RaidCompletionParser() {
	}

	private record Segment(String text, Style style, String hover) {
	}

	/** Cheap keyword gate so the regex/cleanup runs only for plausible raid lines. */
	public static boolean isRaidCandidate(Component message) {
		return message != null && message.getString().contains("finished");
	}

	/** Parse a system-chat component, returning a raid completion if it is one. */
	public static Optional<RaidCompletion> parse(Component message) {
		if (!isRaidCandidate(message)) {
			return Optional.empty();
		}
		// Flatten color codes/formatting for regex parsing
		String rawText = message.getString();
		String cleaned = ChatText.normalize(rawText).replace(",and ", ", and ");
		Matcher detect = RAID_DETECT.matcher(cleaned);
		if (!detect.find()) {
			return Optional.empty();
		}
		String raidName = RAID_BY_KEYWORD.get(detect.group(1).toLowerCase(Locale.ROOT));
		Matcher names = NAMES.matcher(cleaned);
		if (raidName == null || !names.find()) {
			return Optional.empty();
		}
		List<String> party = resolveParty(splitNames(names.group(1)), message);
		if (party.isEmpty()) {
			return Optional.empty();
		}
		// Loot is best-effort: it's frequently a custom-font glyph run, not literal
		// text, so pull whatever figures are present and default the rest. The
		// per-player reward is fixed backend-side and does not depend on these.
		int aspects = firstInt(ASPECTS, cleaned);
		int emeralds = firstInt(EMERALDS, cleaned);
		String guildExp = firstGroup(GUILD_EXP, cleaned);
		return Optional.of(new RaidCompletion(party, raidName, aspects, emeralds, guildExp));
	}

	/** First integer captured by {@code pattern} in {@code text}, or 0 if absent. */
	private static int firstInt(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
	}

	/** First group-1 capture of {@code pattern} in {@code text}, or "" if absent. */
	private static String firstGroup(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group(1) : "";
	}

	private static List<String> splitNames(String namesPart) {
		String canonical = namesPart.replace(", and ", ", ").replace(" and ", ", ").trim();
		List<String> names = new ArrayList<>();
		for (String token : COMMA.split(canonical)) {
			String trimmed = token.trim();
			if (!trimmed.isEmpty()) {
				names.add(trimmed);
			}
		}
		return names;
	}

	private static String cleanName(String name) {
		if (name == null) {
			return null;
		}
		String cleaned = name.replaceAll("&<\\d+>", "").replaceAll("&[a-f0-9k-or]", "");
		int slash = cleaned.indexOf('/');
		if (slash >= 0) {
			cleaned = cleaned.substring(0, slash);
		}
		int paren = cleaned.indexOf('(');
		if (paren >= 0) {
			cleaned = cleaned.substring(0, paren);
		}
		return cleaned.trim();
	}

	private static String hoverUsername(String hover) {
		if (hover == null || hover.isBlank()) {
			return null;
		}
		String text = hover.replace('’', '\'').replace('‘', '\'');
		Matcher matcher = HOVER_REAL_NAME_PATTERN.matcher(text);
		return matcher.find() ? matcher.group(1) : null;
	}

	private static List<Segment> collect(Component message) {
		List<Segment> segments = new ArrayList<>();
		message.visit((style, text) -> {
			if (!text.isEmpty()) {
				HoverEvent hover = style.getHoverEvent();
				String hoverStr = (hover instanceof HoverEvent.ShowText showText) ? showText.value().getString() : null;
				segments.add(new Segment(text, style, hoverStr));
			}
			return Optional.empty();
		}, Style.EMPTY);
		return segments;
	}

	/**
	 * Resolve each displayed party name to a real username using the hover/insertion
	 * metadata carried on the style segments matching the name. Falls back to the
	 * cleaned displayed name when it is already a valid username.
	 */
	private static List<String> resolveParty(List<String> displayed, Component message) {
		List<Segment> segments = collect(message);
		Set<String> resolved = new LinkedHashSet<>();
		for (String name : displayed) {
			String cleanedName = cleanName(name);
			if (cleanedName.isEmpty()) {
				continue;
			}
			String real = null;

			// Pass 1: try exact match on cleaned segment name
			for (Segment seg : segments) {
				String cleanedSegText = cleanName(seg.text());
				if (cleanedSegText.equalsIgnoreCase(cleanedName)) {
					String hover = hoverUsername(seg.hover());
					if (hover != null) {
						real = hover;
						break;
					}
					String insertion = seg.style().getInsertion();
					if (insertion != null && isIgn(insertion)) {
						real = insertion;
						break;
					}
				}
			}

			// Pass 2: partial match fallback (avoid matching single letters to longer segments)
			if (real == null) {
				for (Segment seg : segments) {
					String cleanedSegText = cleanName(seg.text());
					if (!cleanedSegText.isEmpty() && (cleanedSegText.contains(cleanedName) || cleanedName.contains(cleanedSegText))) {
						if (cleanedName.length() == 1 && !cleanedSegText.equalsIgnoreCase(cleanedName)) {
							continue;
						}
						String hover = hoverUsername(seg.hover());
						if (hover != null) {
							real = hover;
							break;
						}
						String insertion = seg.style().getInsertion();
						if (insertion != null && isIgn(insertion)) {
							real = insertion;
							break;
						}
					}
				}
			}

			if (real == null && isIgn(cleanedName)) {
				real = cleanedName;
			}
			if (real != null && isIgn(real)) {
				resolved.add(real);
			}
		}
		return List.copyOf(new ArrayList<>(resolved));
	}

	private static boolean isIgn(String value) {
		return value != null && IGN.matcher(value).matches();
	}
}
