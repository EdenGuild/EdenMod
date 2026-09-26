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
		return parseText(Map.of(SEASON_STATUS_SLOT, lines(items.get(SEASON_STATUS_SLOT)), WEEKLY_OBJECTIVES_SLOT, lines(items.get(WEEKLY_OBJECTIVES_SLOT)), TERRITORIES_SLOT, List.of(items.get(TERRITORIES_SLOT).getHoverName().getString()), DIPLOMACY_SLOT, lines(items.get(DIPLOMACY_SLOT))));
	}

	/** Visible for tests. Keys are root-menu slot numbers and values are unformatted lore/name lines. */
	static Optional<GuildManageSnapshot> parseText(Map<Integer, List<String>> textBySlot) {
		if (textBySlot == null) {
			return Optional.empty();
		}
		String season = joined(textBySlot.get(SEASON_STATUS_SLOT));
		String weekly = joined(textBySlot.get(WEEKLY_OBJECTIVES_SLOT));
		String territories = joined(textBySlot.get(TERRITORIES_SLOT));
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
		GuildManageSnapshot snapshot = new GuildManageSnapshot(rating, position, completed, required, territoryCount, alliances);
		return rating == null && position == null && completed == null && territoryCount == null && alliances == null ? Optional.empty() : Optional.of(snapshot);
	}

	/** Null means no Diplomacy section was present; an empty list means it explicitly listed no allies. */
	private static List<GuildManageSnapshot.Alliance> alliances(List<String> rawLines) {
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

	private static String joined(List<String> lines) {
		if (lines == null) {
			return "";
		}
		return ChatFormatting.stripFormatting(String.join(" ", lines)).replaceAll("\\s+", " ");
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
