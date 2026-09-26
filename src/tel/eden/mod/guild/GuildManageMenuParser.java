package tel.eden.mod.guild;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/** Extracts stable status fields from the 27-slot root {@code /gu man} menu. */
public final class GuildManageMenuParser {
	private static final int SEASON_STATUS_SLOT = 11;
	private static final int WEEKLY_OBJECTIVES_SLOT = 13;
	private static final int TERRITORIES_SLOT = 14;
	private static final int DIPLOMACY_SLOT = 26;
	// The item's own name, e.g. "Season 32 Status" — not lore, so it's prepended
	// separately (see linesWithName) rather than coming from lines().
	private static final Pattern SEASON_NUMBER = Pattern.compile("Season (\\d+) Status", Pattern.CASE_INSENSITIVE);
	// Matched per-line (not against the whole joined blob) so the capture has a clean
	// end-of-line boundary instead of running on into whatever text follows it once
	// every lore line is collapsed into one string.
	private static final Pattern SEASON_ENDS_IN = Pattern.compile("Season will end in (.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SEASON_RATING = Pattern.compile("Season Rating \\(SR\\):\\s*([\\d,]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SEASON_POSITION = Pattern.compile("Position:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern WEEKLY_GOAL = Pattern.compile("Current Guild Goal:\\s*(\\d+)\\s*/\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern TERRITORIES = Pattern.compile("Territories\\s*\\[(\\d+)]", Pattern.CASE_INSENSITIVE);
	private static final Pattern ALLIANCE_HEADING = Pattern.compile("^Guild Alliance:$", Pattern.CASE_INSENSITIVE);
	// Any other bare "Words:" heading — e.g. "Pending Invites:" — marks the end of the
	// alliance section. Sent invites are listed in the exact same "- Name [TAG]" shape,
	// so without this the parser can't tell an actual ally from a pending invite.
	private static final Pattern SECTION_HEADING = Pattern.compile("^[A-Za-z][A-Za-z ]*:$");
	private static final Pattern ALLIANCE = Pattern.compile("^-\\s*(.+?)\\s*\\[([A-Za-z0-9]{2,5})]$");

	private GuildManageMenuParser() {
	}

	/** Parse the root menu; an entirely unrecognised/unfinished menu yields empty. */
	public static Optional<GuildManageSnapshot> parse(List<ItemStack> items) {
		if (items == null || items.size() <= DIPLOMACY_SLOT) {
			return Optional.empty();
		}
		return parseText(Map.of(SEASON_STATUS_SLOT, linesWithName(items.get(SEASON_STATUS_SLOT)), WEEKLY_OBJECTIVES_SLOT, lines(items.get(WEEKLY_OBJECTIVES_SLOT)), TERRITORIES_SLOT, List.of(items.get(TERRITORIES_SLOT).getHoverName().getString()), DIPLOMACY_SLOT, lines(items.get(DIPLOMACY_SLOT))));
	}

	/**
	 * Visible for tests. Keys are root-menu slot numbers and values are unformatted
	 * lore/name lines. An incomplete reading (any field still unresolved) yields
	 * empty rather than a partial snapshot: Wynncraft populates this menu's several
	 * items across more than one packet, and a caller that reports the first,
	 * partially-loaded reading it sees — nulling out fields that simply haven't
	 * arrived yet — would immediately follow it with a second report once the rest
	 * lands. Waiting for every field avoids that spurious first report entirely.
	 */
	static Optional<GuildManageSnapshot> parseText(Map<Integer, List<String>> textBySlot) {
		if (textBySlot == null) {
			return Optional.empty();
		}
		List<String> seasonLines = textBySlot.get(SEASON_STATUS_SLOT);
		String season = joined(seasonLines);
		String weekly = joined(textBySlot.get(WEEKLY_OBJECTIVES_SLOT));
		String territories = joined(textBySlot.get(TERRITORIES_SLOT));
		Integer seasonNumber = integer(SEASON_NUMBER, season);
		String seasonEndsIn = firstLineMatch(seasonLines, SEASON_ENDS_IN);
		Long rating = number(SEASON_RATING, season);
		Integer position = integer(SEASON_POSITION, season);
		Matcher goal = WEEKLY_GOAL.matcher(weekly);
		Integer completed = null;
		Integer required = null;
		if (goal.find()) {
			completed = Integer.valueOf(goal.group(1));
			required = Integer.valueOf(goal.group(2));
		}
		Integer territoryCount = integer(TERRITORIES, territories);
		List<GuildManageSnapshot.Alliance> alliances = alliances(textBySlot.get(DIPLOMACY_SLOT));
		boolean complete = seasonNumber != null && seasonEndsIn != null && rating != null && position != null && completed != null && required != null && territoryCount != null && alliances != null;
		if (!complete) {
			return Optional.empty();
		}
		return Optional.of(new GuildManageSnapshot(seasonNumber, seasonEndsIn, rating, position, completed, required, territoryCount, alliances));
	}

	/**
	 * Null means no Diplomacy section was present; an empty list means it explicitly
	 * listed no allies. Package-visible (not just via {@link #parseText}) so alliance
	 * parsing can be tested on its own, without also having to fabricate a complete
	 * season/weekly/territory fixture just to satisfy {@link #parseText}'s
	 * everything-or-nothing readiness gate.
	 */
	static List<GuildManageSnapshot.Alliance> alliances(List<String> rawLines) {
		if (rawLines == null) {
			return null;
		}
		boolean headingSeen = false;
		boolean inAllianceSection = false;
		List<GuildManageSnapshot.Alliance> result = new java.util.ArrayList<>();
		for (String raw : rawLines) {
			String line = ChatFormatting.stripFormatting(raw).trim();
			if (ALLIANCE_HEADING.matcher(line).matches()) {
				headingSeen = true;
				inAllianceSection = true;
				continue;
			}
			if (!headingSeen) {
				continue;
			}
			if (inAllianceSection && SECTION_HEADING.matcher(line).matches()) {
				// A later section, e.g. "Pending Invites:" — its entries aren't allies.
				inAllianceSection = false;
				continue;
			}
			if (!inAllianceSection) {
				continue;
			}
			Matcher matcher = ALLIANCE.matcher(line);
			if (matcher.matches()) {
				result.add(new GuildManageSnapshot.Alliance(matcher.group(1).trim(), matcher.group(2)));
			}
		}
		return headingSeen ? List.copyOf(result) : null;
	}

	private static List<String> lines(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		return lore == null ? List.of() : lore.lines().stream().map(line -> line.getString()).toList();
	}

	/** The item's own display name followed by its lore lines — e.g. "Season 32 Status" isn't lore. */
	private static List<String> linesWithName(ItemStack stack) {
		List<String> withName = new java.util.ArrayList<>();
		withName.add(stack.getHoverName().getString());
		withName.addAll(lines(stack));
		return withName;
	}

	private static String joined(List<String> lines) {
		if (lines == null) {
			return "";
		}
		return ChatFormatting.stripFormatting(String.join(" ", lines)).replaceAll("\\s+", " ");
	}

	/** {@code pattern}'s first capture group from the first line it matches, or null. */
	private static String firstLineMatch(List<String> rawLines, Pattern pattern) {
		if (rawLines == null) {
			return null;
		}
		for (String raw : rawLines) {
			String line = ChatFormatting.stripFormatting(raw).trim();
			Matcher matcher = pattern.matcher(line);
			if (matcher.find()) {
				return matcher.group(1).trim();
			}
		}
		return null;
	}

	private static Long number(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? Long.valueOf(matcher.group(1).replace(",", "")) : null;
	}

	private static Integer integer(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}
}
