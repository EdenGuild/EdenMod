package tel.eden.mod.party;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import tel.eden.mod.chat.ChatText;
import tel.eden.mod.chat.PlayerNameResolver;
import tel.eden.mod.war.ScoreboardCapture;

/**
 * Tracks party and raid members' live HP from the sidebar scoreboard, deducing
 * maximum HP from observed full-health indicators.
 */
public final class PartyHealthTracker {
	private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");
	private static final Pattern BRACKETED_NUMBER = Pattern.compile("\\[(\\d+)\\]");
	private static final long FULL_BAR_STABLE_MS = 2000L;
	private static final long REFRESH_INTERVAL_MS = 250L;

	public record PlayerHealthData(UUID uuid, String name, int hp, int maxHp, float percent, boolean overMax, int partySlot) {
	}

	record ParsedPartyLine(int hp, String nickname, int level, boolean online, boolean fullHealthBar) {
	}

	private static class MaxHpState {
		int stableMaxHp;
		int candidateHp;
		long candidateSinceMs;

		MaxHpState(int initialHp) {
			this.stableMaxHp = initialHp;
			this.candidateHp = initialHp;
			this.candidateSinceMs = System.currentTimeMillis();
		}
	}

	private static final Map<UUID, PlayerHealthData> HEALTH_BY_UUID = new ConcurrentHashMap<>();
	private static final Map<UUID, MaxHpState> MAX_HP_BY_UUID = new ConcurrentHashMap<>();
	private static long lastRefreshMs = 0L;
	private static int tickCounter = 0;

	private PartyHealthTracker() {
	}

	public static PlayerHealthData getHealth(UUID uuid) {
		if (uuid == null) {
			return null;
		}
		return HEALTH_BY_UUID.get(uuid);
	}

	public static void tick() {
		tickCounter++;
		if (tickCounter % 5 != 0) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastRefreshMs < REFRESH_INTERVAL_MS) {
			return;
		}
		lastRefreshMs = now;
		updatePartyHealth(now);
	}

	public static void reset() {
		HEALTH_BY_UUID.clear();
		MAX_HP_BY_UUID.clear();
		lastRefreshMs = 0L;
	}

	private static void updatePartyHealth(long now) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			reset();
			return;
		}

		Scoreboard scoreboard = ScoreboardCapture.hasSidebar() ? ScoreboardCapture.scoreboard() : mc.level.getScoreboard();
		if (scoreboard == null) {
			return;
		}

		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (sidebar == null) {
			HEALTH_BY_UUID.clear();
			MAX_HP_BY_UUID.clear();
			return;
		}

		List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(sidebar));
		entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());

		boolean inPartySection = false;
		List<ParsedPartyLine> parsedRows = new ArrayList<>();
		List<Component> rowComponents = new ArrayList<>();

		for (PlayerScoreEntry entry : entries) {
			Component component = entry.display();
			if (component == null) {
				PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
				component = team != null ? PlayerTeam.formatNameForTeam(team, entry.ownerName()) : entry.ownerName();
			}
			String text = component.getString().trim();

			if (text.contains("Party:") || text.contains("Raid:")) {
				inPartySection = true;
				continue;
			}
			if (inPartySection && (text.startsWith("Lv.") || text.contains("Territory:") || text.contains("Defense:") || text.contains("Area:") || text.contains("Objectives:") || text.contains("Guild Obj:") || text.startsWith("À") || text.isEmpty())) {
				inPartySection = false;
			}

			if (inPartySection) {
				ParsedPartyLine parsed = parsePartyLine(component, true);
				if (parsed != null) {
					parsedRows.add(parsed);
					rowComponents.add(component);
				}
			}
		}

		if (parsedRows.isEmpty()) {
			HEALTH_BY_UUID.clear();
			MAX_HP_BY_UUID.clear();
			return;
		}

		List<String> wynnPartyMembers = WynntilsPartyBridge.getPartyMembers();
		String localName = (mc.player != null && mc.player.getGameProfile() != null) ? mc.player.getGameProfile().name() : null;
		List<String> candidateRoster = matchCandidateRoster(parsedRows, wynnPartyMembers, localName);

		Set<UUID> seenUuids = new HashSet<>();

		for (int i = 0; i < parsedRows.size(); i++) {
			ParsedPartyLine row = parsedRows.get(i);
			Component comp = rowComponents.get(i);

			if (!row.online()) {
				continue;
			}

			if (i < candidateRoster.size() && candidateRoster.get(i) != null) {
				PlayerNameResolver.recordAlias(row.nickname(), candidateRoster.get(i));
			}

			UUID uuid = resolvePlayerUuid(mc, row.nickname(), comp, wynnPartyMembers, i, parsedRows.size());
			if (uuid == null) {
				continue;
			}
			seenUuids.add(uuid);

			MaxHpState maxState = MAX_HP_BY_UUID.computeIfAbsent(uuid, k -> new MaxHpState(row.hp()));
			int maxHp = updateMaxHp(maxState, row.hp(), row.fullHealthBar(), now);

			float percent = maxHp > 0 ? Math.max(0.0f, Math.min(1.0f, row.hp() / (float) maxHp)) : 1.0f;
			boolean overMax = maxHp > 0 && row.hp() > maxHp;

			int slot = resolvePartySlot(row.nickname(), wynnPartyMembers, i);
			HEALTH_BY_UUID.put(uuid, new PlayerHealthData(uuid, row.nickname(), row.hp(), maxHp, percent, overMax, slot));
		}

		HEALTH_BY_UUID.keySet().retainAll(seenUuids);
		MAX_HP_BY_UUID.keySet().retainAll(seenUuids);
	}

	static ParsedPartyLine parsePartyLine(Component component, boolean inPartySection) {
		if (component == null || !inPartySection) {
			return null;
		}
		String text = component.getString().trim();
		if (text.isEmpty()) {
			return null;
		}

		BracketedNumber level = lastBracketedNumber(text);
		if (level == null) {
			// Offline member: "- PlayerName"
			if (text.startsWith("-")) {
				String name = text.substring(1).trim();
				if (ChatText.IGN.matcher(name).matches()) {
					return new ParsedPartyLine(0, name, 0, false, false);
				}
			}
			return null;
		}

		Matcher hpMatcher = FIRST_NUMBER.matcher(text);
		hpMatcher.region(0, level.start());
		if (!hpMatcher.find()) {
			return null;
		}

		int hp = Integer.parseInt(hpMatcher.group(1));

		int nickStart = hpMatcher.end();
		while (nickStart < level.start() && !isNicknameChar(text.charAt(nickStart))) {
			nickStart++;
		}
		int nickEnd = level.start();
		while (nickEnd > nickStart && !isNicknameChar(text.charAt(nickEnd - 1))) {
			nickEnd--;
		}
		if (nickStart >= nickEnd) {
			return null;
		}

		String nickname = text.substring(nickStart, nickEnd).trim();
		if (nickname.isEmpty()) {
			return null;
		}

		// Check if health bracket contains full health (all red characters)
		boolean fullBar = isFullHealthBar(component, text, hpMatcher.start(), hpMatcher.end(), level.start());

		return new ParsedPartyLine(hp, nickname, level.value(), true, fullBar);
	}

	private static boolean isNicknameChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
	}

	private static boolean isFullHealthBar(Component component, String text, int hpStart, int hpEnd, int levelStart) {
		int barStart = text.lastIndexOf('[', hpStart);
		int barEnd = text.indexOf(']', hpEnd);
		if (barStart < 0 || barEnd < 0 || barEnd >= levelStart || barEnd <= barStart) {
			return false;
		}

		int[] nonRed = new int[1];
		int[] red = new int[1];
		int[] cursor = new int[1];

		component.visit((style, str) -> {
			if (str == null || str.isEmpty()) {
				return java.util.Optional.empty();
			}
			TextColor color = style.getColor();
			int rgb = color != null ? color.getValue() : -1;
			for (int j = 0; j < str.length(); j++) {
				int idx = cursor[0]++;
				if (idx <= barStart || idx >= barEnd) {
					continue;
				}
				char c = str.charAt(j);
				if (Character.isWhitespace(c) || c == '[' || c == ']') {
					continue;
				}
				if (isRedColor(rgb)) {
					red[0]++;
				} else {
					nonRed[0]++;
				}
			}
			return java.util.Optional.empty();
		}, Style.EMPTY);

		return red[0] > 0 && nonRed[0] == 0;
	}

	private static boolean isRedColor(int rgb) {
		if (rgb < 0) {
			return false;
		}
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return r >= 130 && g <= 100 && b <= 100 && r > g && r > b;
	}

	private static int updateMaxHp(MaxHpState state, int currentHp, boolean fullBar, long now) {
		if (fullBar) {
			if (state.stableMaxHp <= 0) {
				state.stableMaxHp = currentHp;
				state.candidateHp = currentHp;
				state.candidateSinceMs = now;
				return currentHp;
			}
			if (currentHp != state.candidateHp) {
				state.candidateHp = currentHp;
				state.candidateSinceMs = now;
			} else if (now - state.candidateSinceMs >= FULL_BAR_STABLE_MS) {
				state.stableMaxHp = currentHp;
			}
		} else {
			state.candidateHp = currentHp;
			state.candidateSinceMs = now;
		}

		return state.stableMaxHp > 0 ? state.stableMaxHp : currentHp;
	}

	private static UUID resolvePlayerUuid(Minecraft mc, String displayedNick, Component rowComponent, List<String> partyMembers, int rowIndex, int totalRows) {
		if (mc.level == null) {
			return null;
		}

		// 1. Check hover/insertion on scoreboard row
		String realName = ChatText.resolveRealNameAnywhere(rowComponent, displayedNick);
		if (realName == null) {
			realName = PlayerNameResolver.resolveKnown(displayedNick).orElse(displayedNick);
		}

		// 2. Match with world players
		for (Player player : mc.level.players()) {
			String pName = player.getGameProfile().name();
			if (pName.equalsIgnoreCase(realName) || pName.equalsIgnoreCase(displayedNick)) {
				return player.getUUID();
			}
		}

		// 3. Fallback: if only 1 non-local player is in the world and only 1 non-local party row
		List<Player> otherPlayers = new ArrayList<>();
		for (Player p : mc.level.players()) {
			if (mc.player == null || !p.getUUID().equals(mc.player.getUUID())) {
				otherPlayers.add(p);
			}
		}
		if (otherPlayers.size() == 1 && totalRows <= 2) {
			Player solo = otherPlayers.get(0);
			PlayerNameResolver.recordAlias(displayedNick, solo.getGameProfile().name());
			return solo.getUUID();
		}

		return null;
	}

	static List<String> matchCandidateRoster(List<ParsedPartyLine> rows, List<String> wynnPartyMembers, String localUsername) {
		if (wynnPartyMembers == null || wynnPartyMembers.isEmpty()) {
			return List.of();
		}

		List<List<String>> candidates = new ArrayList<>();
		if (wynnPartyMembers.size() == rows.size()) {
			candidates.add(wynnPartyMembers);
		}

		if (localUsername != null && !containsIgnoreCase(wynnPartyMembers, localUsername)) {
			if (wynnPartyMembers.size() + 1 == rows.size()) {
				List<String> localFirst = new ArrayList<>(rows.size());
				localFirst.add(localUsername);
				localFirst.addAll(wynnPartyMembers);
				candidates.add(localFirst);

				List<String> localLast = new ArrayList<>(rows.size());
				localLast.addAll(wynnPartyMembers);
				localLast.add(localUsername);
				candidates.add(localLast);
			}
		}

		for (List<String> candidate : candidates) {
			if (candidateMatches(rows, candidate, localUsername)) {
				return candidate;
			}
		}

		return candidates.isEmpty() ? List.of() : candidates.get(0);
	}

	private static boolean candidateMatches(List<ParsedPartyLine> rows, List<String> candidate, String localUsername) {
		for (int i = 0; i < rows.size(); i++) {
			ParsedPartyLine row = rows.get(i);
			String expectedUser = candidate.get(i);

			if (localUsername != null && expectedUser.equalsIgnoreCase(localUsername)) {
				if (isLocalRow(row, localUsername)) {
					return true;
				}
			}

			String known = PlayerNameResolver.canonicalize(row.nickname());
			if (known != null && known.equalsIgnoreCase(expectedUser)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isLocalRow(ParsedPartyLine row, String localUsername) {
		if (row.nickname().equalsIgnoreCase(localUsername)) {
			return true;
		}
		String known = PlayerNameResolver.canonicalize(row.nickname());
		if (known != null && known.equalsIgnoreCase(localUsername)) {
			return true;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.player != null) {
				int localHp = (int) Math.ceil(mc.player.getHealth());
				if (row.hp() == localHp) {
					return true;
				}
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	private static boolean containsIgnoreCase(List<String> list, String target) {
		if (list == null || target == null) {
			return false;
		}
		for (String s : list) {
			if (s.equalsIgnoreCase(target)) {
				return true;
			}
		}
		return false;
	}

	private static int resolvePartySlot(String name, List<String> partyMembers, int fallbackSlot) {
		if (partyMembers != null && !partyMembers.isEmpty()) {
			String canonical = PlayerNameResolver.canonicalize(name);
			for (int i = 0; i < partyMembers.size(); i++) {
				String member = partyMembers.get(i);
				if (member != null && (member.equalsIgnoreCase(name) || (canonical != null && member.equalsIgnoreCase(canonical)))) {
					return i;
				}
			}
		}
		return fallbackSlot;
	}

	private record BracketedNumber(int value, int start, int end) {
	}

	private static BracketedNumber lastBracketedNumber(String text) {
		Matcher matcher = BRACKETED_NUMBER.matcher(text);
		BracketedNumber last = null;
		while (matcher.find()) {
			try {
				last = new BracketedNumber(Integer.parseInt(matcher.group(1)), matcher.start(), matcher.end());
			} catch (NumberFormatException ignored) {
			}
		}
		return last;
	}
}
