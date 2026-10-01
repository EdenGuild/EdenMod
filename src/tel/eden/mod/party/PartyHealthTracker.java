package tel.eden.mod.party;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
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
	private static final long FULL_BAR_DOWNWARD_STABLE_MS = 20000L;
	private static final long REFRESH_INTERVAL_MS = 250L;

	public record PlayerHealthData(UUID uuid, String name, int hp, int maxHp, float percent, boolean overMax, float overPercent, int partySlot) {
		public PlayerHealthData(UUID uuid, String name, int hp, int maxHp, float percent, boolean overMax, int partySlot) {
			this(uuid, name, hp, maxHp, percent, overMax, calculateOverPercent(hp, maxHp, overMax), partySlot);
		}

		public static float calculateOverPercent(int hp, int maxHp, boolean overMax) {
			if (!overMax || maxHp <= 0 || hp <= maxHp) {
				return 0.0f;
			}
			return Math.max(0.0f, Math.min(1.0f, (float) (hp - maxHp) / (float) maxHp));
		}
	}

	record ParsedPartyLine(int hp, String nickname, int level, boolean online, boolean fullHealthBar, float visualFraction) {
	}

	record BarAnalysis(boolean fullBar, float visualFraction) {
	}

	record HealthCalculation(int maxHp, float percent, boolean overMax) {
	}

	static class MaxHpState {
		int stableMaxHp;
		int candidateHp;
		long candidateSinceMs;
		long downwardCandidateSinceMs;

		MaxHpState() {
			this.stableMaxHp = 0;
			this.candidateHp = 0;
			this.candidateSinceMs = 0L;
			this.downwardCandidateSinceMs = 0L;
		}
	}

	private static final Map<UUID, PlayerHealthData> HEALTH_BY_UUID = new ConcurrentHashMap<>();
	private static final Map<UUID, MaxHpState> MAX_HP_BY_UUID = new ConcurrentHashMap<>();
	private static final Map<String, UUID> UUID_BY_PLAYER_NAME = new ConcurrentHashMap<>();
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
		UUID_BY_PLAYER_NAME.clear();
		lastRefreshMs = 0L;
		PartyHealthBarRenderer.reset();
		PlayerNameResolver.reset();
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

		List<String> rawWynnPartyMembers = WynntilsPartyBridge.getPartyMembers();
		List<String> wynnPartyMembers = deduplicatePreservingOrder(rawWynnPartyMembers);
		String localName = (mc.player != null && mc.player.getGameProfile() != null) ? mc.player.getGameProfile().name() : null;
		List<String> candidateRoster = matchCandidateRoster(parsedRows, wynnPartyMembers, localName);

		Set<UUID> seenUuids = new HashSet<>();

		// 1. If local player is present, record exact live health if available from Wynntils or vanilla stats
		if (mc.player != null) {
			UUID myUuid = mc.player.getUUID();
			String myName = (mc.player.getGameProfile() != null) ? mc.player.getGameProfile().name() : "";
			WynntilsPartyBridge.LiveHealth localLive = WynntilsPartyBridge.getLocalPlayerHealth();
			int currentHp;
			int maxHp;
			float percent;
			boolean overMax;

			if (localLive != null) {
				currentHp = localLive.current();
				maxHp = localLive.max();
				percent = localLive.percent();
				overMax = currentHp > maxHp;
			} else {
				float vHp = mc.player.getHealth();
				float vMax = mc.player.getMaxHealth();
				currentHp = (int) Math.ceil(vHp);
				maxHp = (int) Math.ceil(vMax);
				percent = (maxHp > 0) ? Math.max(0.0f, Math.min(1.0f, vHp / vMax)) : 1.0f;
				overMax = false;
			}

			int slot = resolvePartySlot(myName, null, wynnPartyMembers, 0, false);
			float overPercent = PlayerHealthData.calculateOverPercent(currentHp, maxHp, overMax);
			HEALTH_BY_UUID.put(myUuid, new PlayerHealthData(myUuid, myName, currentHp, maxHp, percent, overMax, overPercent, slot));
			seenUuids.add(myUuid);
		}

		UUID[] rowUuids = resolvePartyUuids(mc, parsedRows, rowComponents, candidateRoster, wynnPartyMembers);

		for (int i = 0; i < parsedRows.size(); i++) {
			ParsedPartyLine row = parsedRows.get(i);
			if (!row.online()) {
				continue;
			}

			UUID uuid = i < rowUuids.length ? rowUuids[i] : null;
			if (uuid == null) {
				continue;
			}
			seenUuids.add(uuid);

			if (mc.player != null && uuid.equals(mc.player.getUUID()) && HEALTH_BY_UUID.containsKey(uuid)) {
				// Local player already accurately tracked from Wynntils CharacterStats
				continue;
			}

			WynntilsPartyBridge.LiveHealth hadesLive = WynntilsPartyBridge.getHadesHealth(uuid);
			int maxHp;
			float percent;
			boolean overMax;

			if (hadesLive != null) {
				maxHp = hadesLive.max();
				percent = hadesLive.percent();
				overMax = hadesLive.current() > hadesLive.max();
				if (maxHp > 0) {
					MaxHpState maxState = MAX_HP_BY_UUID.computeIfAbsent(uuid, k -> new MaxHpState());
					maxState.stableMaxHp = maxHp;
				}
			} else {
				MaxHpState maxState = MAX_HP_BY_UUID.computeIfAbsent(uuid, k -> new MaxHpState());
				HealthCalculation calc = calculateHealth(maxState, row.hp(), row.level(), row.fullHealthBar(), row.visualFraction(), now);
				maxHp = calc.maxHp();
				percent = calc.percent();
				overMax = calc.overMax();
			}

			boolean sharedNick = isSharedNickname(parsedRows, row.nickname());
			String realName = resolvePlayerRealName(mc, uuid, candidateRoster, i);
			int slot = resolvePartySlot(realName, row.nickname(), wynnPartyMembers, i, sharedNick);
			float overPercent = PlayerHealthData.calculateOverPercent(row.hp(), maxHp, overMax);
			HEALTH_BY_UUID.put(uuid, new PlayerHealthData(uuid, row.nickname(), row.hp(), maxHp, percent, overMax, overPercent, slot));
		}

		HEALTH_BY_UUID.keySet().retainAll(seenUuids);
	}

	record StyledChar(char ch, int color) {
	}

	static List<StyledChar> extractStyledChars(Component message) {
		List<StyledChar> result = new ArrayList<>();
		if (message == null) {
			return result;
		}
		for (Component fragment : message.toFlatList()) {
			Style style = fragment.getStyle();
			TextColor textColor = style.getColor();
			int baseColor = textColor != null ? textColor.getValue() : -1;
			int currentColor = baseColor;

			String text = fragment.getString();
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (c == '§' || c == '\u00A7') {
					if (i + 1 < text.length()) {
						char code = Character.toLowerCase(text.charAt(i + 1));
						ChatFormatting formatting = ChatFormatting.getByCode(code);
						if (formatting == ChatFormatting.RESET) {
							currentColor = baseColor;
						} else if (formatting != null && formatting.getColor() != null) {
							currentColor = formatting.getColor();
						}
						i++;
					}
					continue;
				}
				result.add(new StyledChar(c, currentColor));
			}
		}
		return result;
	}

	static ParsedPartyLine parsePartyLine(Component component, boolean inPartySection) {
		if (component == null || !inPartySection) {
			return null;
		}
		List<StyledChar> styledChars = extractStyledChars(component);
		if (styledChars.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder(styledChars.size());
		for (StyledChar sc : styledChars) {
			sb.append(sc.ch());
		}
		String text = sb.toString().trim();
		if (text.isEmpty()) {
			return null;
		}

		BracketedNumber level = lastBracketedNumber(text);
		if (level == null) {
			// Offline member: "- PlayerName"
			if (text.startsWith("-")) {
				String name = text.substring(1).trim();
				if (ChatText.IGN.matcher(name).matches()) {
					return new ParsedPartyLine(0, name, 0, false, false, 0.0f);
				}
			}
			return null;
		}

		int openBracket = text.indexOf('[');
		int closeBracket = openBracket >= 0 ? text.indexOf(']', openBracket) : -1;
		int hp;
		String nickname;
		int barContentStart = -1;
		int barContentEnd = -1;

		if (openBracket >= 0 && closeBracket > openBracket && closeBracket < level.start()) {
			// Structured health bar: "- [||18630||] meep [120]" or "[18630] meep [120]"
			String barContent = text.substring(openBracket + 1, closeBracket);
			Matcher barMatcher = FIRST_NUMBER.matcher(barContent);
			if (!barMatcher.find()) {
				return null;
			}
			hp = Integer.parseInt(barMatcher.group(1));
			barContentStart = openBracket + 1;
			barContentEnd = closeBracket;
			nickname = text.substring(closeBracket + 1, level.start()).trim();
		} else {
			// Fallback: e.g. "18630 meep [120]"
			Matcher hpMatcher = FIRST_NUMBER.matcher(text);
			hpMatcher.region(0, level.start());
			if (!hpMatcher.find()) {
				return null;
			}
			hp = Integer.parseInt(hpMatcher.group(1));
			barContentStart = hpMatcher.start();
			barContentEnd = hpMatcher.end();
			nickname = text.substring(hpMatcher.end(), level.start()).trim();
		}

		// Trim any non-nickname characters from nickname bounds
		int nickStart = 0;
		while (nickStart < nickname.length() && !isNicknameChar(nickname.charAt(nickStart))) {
			nickStart++;
		}
		int nickEnd = nickname.length();
		while (nickEnd > nickStart && !isNicknameChar(nickname.charAt(nickEnd - 1))) {
			nickEnd--;
		}
		if (nickStart >= nickEnd) {
			return null;
		}
		nickname = nickname.substring(nickStart, nickEnd).trim();
		if (nickname.isEmpty()) {
			return null;
		}

		BarAnalysis bar = analyzeHealthBar(styledChars, barContentStart, barContentEnd);
		return new ParsedPartyLine(hp, nickname, level.value(), true, bar.fullBar(), bar.visualFraction());
	}

	private static boolean isNicknameChar(char c) {
		return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
	}

	private static BarAnalysis analyzeHealthBar(List<StyledChar> styledChars, int barStart, int barEnd) {
		if (styledChars == null || barStart < 0 || barEnd <= barStart || barEnd > styledChars.size()) {
			return new BarAnalysis(false, 1.0f);
		}

		int healthChars = 0;
		int deficitChars = 0;

		for (int i = barStart; i < barEnd; i++) {
			StyledChar sc = styledChars.get(i);
			char c = sc.ch();
			if (Character.isWhitespace(c) || c == '[' || c == ']') {
				continue;
			}
			int rgb = sc.color();
			if (isHealthColor(rgb)) {
				healthChars++;
			} else if (isDeficitColor(rgb)) {
				deficitChars++;
			}
		}

		int total = healthChars + deficitChars;
		if (total == 0) {
			return new BarAnalysis(false, 1.0f);
		}
		boolean full = deficitChars == 0 && healthChars > 0;
		float fraction = (float) healthChars / (float) total;
		return new BarAnalysis(full, fraction);
	}

	private static boolean isHealthColor(int rgb) {
		if (rgb < 0) {
			return false;
		}
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		if (r >= 130 && g <= 100 && b <= 100 && r > g && r > b) {
			return true;
		}
		if (g >= 130 && r <= 100 && b <= 100 && g > r && g > b) {
			return true;
		}
		return false;
	}

	private static boolean isDeficitColor(int rgb) {
		if (rgb < 0) {
			return false;
		}
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return rgb == 0x555555 || (r >= 50 && r <= 110 && g >= 50 && g <= 110 && b >= 50 && b <= 110 && Math.abs(r - g) <= 15 && Math.abs(r - b) <= 15);
	}

	static HealthCalculation calculateHealth(MaxHpState state, int currentHp, int level, boolean fullBar, float visualFraction, long now) {
		if (fullBar) {
			if (state.stableMaxHp <= 0) {
				state.stableMaxHp = currentHp;
				state.candidateHp = currentHp;
				state.candidateSinceMs = now;
				state.downwardCandidateSinceMs = 0L;
			} else if (currentHp != state.candidateHp) {
				state.candidateHp = currentHp;
				state.candidateSinceMs = now;
				state.downwardCandidateSinceMs = (currentHp < state.stableMaxHp) ? now : 0L;
			} else if (currentHp > state.stableMaxHp && now - state.candidateSinceMs >= FULL_BAR_STABLE_MS) {
				state.stableMaxHp = currentHp;
			} else if (currentHp < state.stableMaxHp && state.downwardCandidateSinceMs > 0L && now - state.downwardCandidateSinceMs >= FULL_BAR_DOWNWARD_STABLE_MS) {
				state.stableMaxHp = currentHp;
				state.downwardCandidateSinceMs = 0L;
			}
		} else {
			state.candidateHp = currentHp;
			state.candidateSinceMs = now;
			state.downwardCandidateSinceMs = 0L;
		}

		int maxHp;
		float percent;

		if (state.stableMaxHp > 0) {
			maxHp = state.stableMaxHp;
			percent = Math.max(0.0f, Math.min(1.0f, currentHp / (float) maxHp));
		} else {
			int estimatedMax = 0;
			if (visualFraction > 0.05f) {
				estimatedMax = Math.round(currentHp / visualFraction);
			} else {
				estimatedMax = currentHp * 8;
			}

			if (level > 0) {
				int levelBasedMin = 50 + (level * 115);
				estimatedMax = Math.max(estimatedMax, levelBasedMin);
			}

			maxHp = Math.max(currentHp, estimatedMax);
			percent = Math.max(0.0f, Math.min(1.0f, currentHp / (float) maxHp));
		}

		boolean overMax = state.stableMaxHp > 0 && currentHp > state.stableMaxHp;
		return new HealthCalculation(maxHp, percent, overMax);
	}

	private static boolean isRealPlayer(Minecraft mc, Player player) {
		if (player == null) {
			return false;
		}
		if (mc != null && mc.player != null && player.getUUID().equals(mc.player.getUUID())) {
			return true;
		}
		if (mc != null && mc.getConnection() != null) {
			PlayerInfo info = mc.getConnection().getPlayerInfo(player.getUUID());
			if (info == null) {
				return false;
			}
		}
		String name = player.getGameProfile() != null ? player.getGameProfile().name() : null;
		if (name == null || !ChatText.IGN.matcher(name).matches()) {
			return false;
		}
		String custom = player.getCustomName() != null ? player.getCustomName().getString() : "";
		if (custom.contains("Click") || custom.contains("Leave") || custom.contains("Merchant") || custom.contains("Master") || custom.contains("Select")) {
			return false;
		}
		return true;
	}

	static UUID[] resolvePartyUuids(Minecraft mc, List<ParsedPartyLine> rows, List<Component> rowComponents, List<String> candidateRoster, List<String> wynnPartyMembers) {
		if (mc == null || mc.level == null || rows == null || rows.isEmpty()) {
			return new UUID[0];
		}

		UUID[] result = new UUID[rows.size()];
		Set<UUID> claimed = new HashSet<>();

		String localIgn = (mc.player != null && mc.player.getGameProfile() != null) ? mc.player.getGameProfile().name() : "";
		UUID localUuid = mc.player != null ? mc.player.getUUID() : null;

		// Pass 1: Local player identification
		for (int i = 0; i < rows.size(); i++) {
			ParsedPartyLine row = rows.get(i);
			if (isLocalRow(row, localIgn)) {
				result[i] = localUuid;
				if (localUuid != null) {
					claimed.add(localUuid);
					if (!localIgn.isEmpty()) {
						UUID_BY_PLAYER_NAME.put(localIgn.toLowerCase(Locale.ROOT), localUuid);
					}
				}
				if (!localIgn.isEmpty() && !isSharedNickname(rows, row.nickname())) {
					PlayerNameResolver.recordAlias(row.nickname(), localIgn);
				}
				break;
			}
		}

		// Pass 2: Direct / known / candidate matching with real world players
		for (int i = 0; i < rows.size(); i++) {
			if (result[i] != null || !rows.get(i).online()) {
				continue;
			}
			ParsedPartyLine row = rows.get(i);
			Component comp = (rowComponents != null && i < rowComponents.size()) ? rowComponents.get(i) : null;
			boolean sharedNick = isSharedNickname(rows, row.nickname());

			String real = null;
			if (comp != null) {
				real = ChatText.resolveRealNameAnywhere(comp, row.nickname());
			}

			// If nickname is unique, check learned alias; if shared, candidate roster is positional truth
			if (real == null && !sharedNick) {
				real = PlayerNameResolver.resolveKnown(row.nickname()).orElse(null);
			}

			// If the resolved alias belongs to a player already claimed by another row, discard it
			if (real != null && isPlayerClaimed(mc, claimed, real)) {
				real = null;
			}

			// Candidate roster fallback
			if (real == null && candidateRoster != null && i < candidateRoster.size() && candidateRoster.get(i) != null) {
				String cand = candidateRoster.get(i);
				if (!isPlayerClaimed(mc, claimed, cand)) {
					real = cand;
				}
			}

			String nameToFind = real != null ? real : row.nickname();
			String nickLower = row.nickname().toLowerCase(Locale.ROOT);
			String findLower = nameToFind.toLowerCase(Locale.ROOT);

			for (Player p : mc.level.players()) {
				if (!isRealPlayer(mc, p) || claimed.contains(p.getUUID())) {
					continue;
				}
				String pName = p.getGameProfile() != null ? p.getGameProfile().name() : null;
				if (pName == null) {
					continue;
				}
				String pNameLower = pName.toLowerCase(Locale.ROOT);

				// 1. Exact match on real name or nickname (if nickname is not shared across rows)
				if (pNameLower.equals(findLower) || (!sharedNick && pNameLower.equals(nickLower))) {
					result[i] = p.getUUID();
					claimed.add(p.getUUID());
					UUID_BY_PLAYER_NAME.put(pName.toLowerCase(Locale.ROOT), p.getUUID());
					if (!sharedNick) {
						PlayerNameResolver.recordAlias(row.nickname(), pName);
					}
					break;
				}
				// 2. Prefix match (e.g. nickname "Eternal" matches real IGN "EternalOblivion")
				if (!sharedNick && nickLower.length() >= 3 && pNameLower.startsWith(nickLower)) {
					result[i] = p.getUUID();
					claimed.add(p.getUUID());
					UUID_BY_PLAYER_NAME.put(pName.toLowerCase(Locale.ROOT), p.getUUID());
					PlayerNameResolver.recordAlias(row.nickname(), pName);
					break;
				}
				// 3. Custom nametag / display contains nickname
				String custom = p.getCustomName() != null ? p.getCustomName().getString().toLowerCase(Locale.ROOT) : "";
				if (!sharedNick && !custom.isBlank() && custom.contains(nickLower)) {
					result[i] = p.getUUID();
					claimed.add(p.getUUID());
					UUID_BY_PLAYER_NAME.put(pName.toLowerCase(Locale.ROOT), p.getUUID());
					PlayerNameResolver.recordAlias(row.nickname(), pName);
					break;
				}
			}

			// Fallback: If player entity is out of render distance, check persistent UUID cache or server tab list
			if (result[i] == null && real != null) {
				result[i] = claimOfflineUuid(mc, real, claimed);
			}
		}

		// Pass 3: Confirmed party member elimination pass
		// If rows remain unmatched, and confirmed party members from Wynntils remain unclaimed,
		// match them safely (never matching arbitrary strangers).
		List<Integer> unmatchedRowIndices = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			if (result[i] == null && rows.get(i).online()) {
				unmatchedRowIndices.add(i);
			}
		}

		if (!unmatchedRowIndices.isEmpty() && wynnPartyMembers != null && !wynnPartyMembers.isEmpty()) {
			List<Player> unclaimedPartyPlayersInWorld = new ArrayList<>();
			for (Player p : mc.level.players()) {
				if (!isRealPlayer(mc, p) || claimed.contains(p.getUUID())) {
					continue;
				}
				String pName = p.getGameProfile() != null ? p.getGameProfile().name() : null;
				if (pName != null && containsIgnoreCase(wynnPartyMembers, pName)) {
					unclaimedPartyPlayersInWorld.add(p);
				}
			}

			// Step 3a: Match candidate roster expected player for each unmatched row
			for (Iterator<Integer> it = unmatchedRowIndices.iterator(); it.hasNext();) {
				int rowIdx = it.next();
				if (candidateRoster != null && rowIdx < candidateRoster.size()) {
					String expected = candidateRoster.get(rowIdx);
					if (expected != null) {
						Player matched = null;
						for (Player p : unclaimedPartyPlayersInWorld) {
							if (p.getGameProfile() != null && p.getGameProfile().name().equalsIgnoreCase(expected)) {
								matched = p;
								break;
							}
						}
						if (matched != null) {
							result[rowIdx] = matched.getUUID();
							claimed.add(matched.getUUID());
							String pName = matched.getGameProfile().name();
							if (pName != null) {
								UUID_BY_PLAYER_NAME.put(pName.toLowerCase(Locale.ROOT), matched.getUUID());
							}
							unclaimedPartyPlayersInWorld.remove(matched);
							it.remove();
							if (!isSharedNickname(rows, rows.get(rowIdx).nickname())) {
								PlayerNameResolver.recordAlias(rows.get(rowIdx).nickname(), matched.getGameProfile().name());
							}
						} else {
							UUID target = claimOfflineUuid(mc, expected, claimed);
							if (target != null) {
								result[rowIdx] = target;
								it.remove();
							}
						}
					}
				}
			}

			// Step 3b: Unique prefix match for remaining unmatched non-shared nicknames
			if (unclaimedPartyPlayersInWorld.size() > 1) {
				for (Iterator<Integer> it = unmatchedRowIndices.iterator(); it.hasNext();) {
					int rowIdx = it.next();
					if (isSharedNickname(rows, rows.get(rowIdx).nickname())) {
						continue;
					}
					String nickLower = rows.get(rowIdx).nickname().toLowerCase(Locale.ROOT);
					String cleanNick = nickLower.replaceAll("[^a-z0-9_]", "");
					Player matchedPlayer = null;
					for (Player p : unclaimedPartyPlayersInWorld) {
						String pName = p.getGameProfile() != null ? p.getGameProfile().name() : null;
						if (pName == null) {
							continue;
						}
						String cleanP = pName.toLowerCase(Locale.ROOT);
						if (!cleanNick.isEmpty() && (cleanP.startsWith(cleanNick) || cleanNick.startsWith(cleanP))) {
							matchedPlayer = p;
							break;
						}
					}
					if (matchedPlayer != null) {
						result[rowIdx] = matchedPlayer.getUUID();
						claimed.add(matchedPlayer.getUUID());
						String pName = matchedPlayer.getGameProfile().name();
						if (pName != null) {
							UUID_BY_PLAYER_NAME.put(pName.toLowerCase(Locale.ROOT), matchedPlayer.getUUID());
						}
						unclaimedPartyPlayersInWorld.remove(matchedPlayer);
						it.remove();
						PlayerNameResolver.recordAlias(rows.get(rowIdx).nickname(), pName);
					}
				}
			}

			// Step 3c: Single remaining party player match
			if (unmatchedRowIndices.size() == 1 && unclaimedPartyPlayersInWorld.size() == 1) {
				int rowIdx = unmatchedRowIndices.get(0);
				Player partyPlayer = unclaimedPartyPlayersInWorld.get(0);
				result[rowIdx] = partyPlayer.getUUID();
				claimed.add(partyPlayer.getUUID());
				String pName = partyPlayer.getGameProfile() != null ? partyPlayer.getGameProfile().name() : null;
				if (pName != null) {
					UUID_BY_PLAYER_NAME.put(pName.toLowerCase(Locale.ROOT), partyPlayer.getUUID());
					if (!isSharedNickname(rows, rows.get(rowIdx).nickname())) {
						PlayerNameResolver.recordAlias(rows.get(rowIdx).nickname(), pName);
					}
				}
				unmatchedRowIndices.clear();
				unclaimedPartyPlayersInWorld.clear();
			}

			// Step 3d: Positional alignment fallback for any remaining rows and party members
			if (!unmatchedRowIndices.isEmpty() && unmatchedRowIndices.size() == unclaimedPartyPlayersInWorld.size()) {
				unclaimedPartyPlayersInWorld.sort(Comparator.comparingInt(p -> {
					String pName = p.getGameProfile() != null ? p.getGameProfile().name() : "";
					for (int k = 0; k < wynnPartyMembers.size(); k++) {
						if (wynnPartyMembers.get(k).equalsIgnoreCase(pName)) {
							return k;
						}
					}
					return 999;
				}));
				for (int k = 0; k < unmatchedRowIndices.size(); k++) {
					int rowIdx = unmatchedRowIndices.get(k);
					Player p = unclaimedPartyPlayersInWorld.get(k);
					result[rowIdx] = p.getUUID();
					claimed.add(p.getUUID());
				}
			}
		}

		return result;
	}

	private static boolean isPlayerClaimed(Minecraft mc, Set<UUID> claimed, String username) {
		if (mc == null || mc.level == null || claimed == null || username == null) {
			return false;
		}
		for (Player p : mc.level.players()) {
			if (p.getGameProfile() != null && p.getGameProfile().name().equalsIgnoreCase(username)) {
				return claimed.contains(p.getUUID());
			}
		}
		return false;
	}

	static boolean isSharedNickname(List<ParsedPartyLine> rows, String nickname) {
		if (nickname == null || nickname.isBlank() || rows == null || rows.size() <= 1) {
			return false;
		}
		String clean = nickname.replaceAll("[^a-zA-Z0-9_]", "").toLowerCase(Locale.ROOT);
		int count = 0;
		for (ParsedPartyLine r : rows) {
			if (r == null || r.nickname() == null) {
				continue;
			}
			String rClean = r.nickname().replaceAll("[^a-zA-Z0-9_]", "").toLowerCase(Locale.ROOT);
			if (r.nickname().equalsIgnoreCase(nickname) || (!clean.isEmpty() && clean.equals(rClean))) {
				count++;
			} else if (clean.length() >= 4 && (clean.startsWith(rClean) || rClean.startsWith(clean))) {
				count++;
			}
		}
		return count > 1;
	}

	static List<String> deduplicatePreservingOrder(List<String> list) {
		if (list == null || list.isEmpty()) {
			return List.of();
		}
		List<String> result = new ArrayList<>(list.size());
		Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (String item : list) {
			if (item != null && !item.isBlank()) {
				String trimmed = item.trim();
				if (seen.add(trimmed)) {
					result.add(trimmed);
				}
			}
		}
		return result;
	}

	static List<String> matchCandidateRoster(List<ParsedPartyLine> rows, List<String> wynnPartyMembers, String localUsername) {
		if (wynnPartyMembers == null || wynnPartyMembers.isEmpty() || rows == null || rows.isEmpty()) {
			return List.of();
		}
		wynnPartyMembers = deduplicatePreservingOrder(wynnPartyMembers);

		int localRowIdx = -1;
		if (localUsername != null) {
			for (int i = 0; i < rows.size(); i++) {
				if (isLocalRow(rows.get(i), localUsername)) {
					localRowIdx = i;
					break;
				}
			}
		}
		boolean scoreboardHasLocal = (localRowIdx >= 0);

		List<List<String>> candidates = new ArrayList<>();

		// 1. Equal size: party list and scoreboard have same count
		if (wynnPartyMembers.size() == rows.size()) {
			candidates.add(wynnPartyMembers);
			if (scoreboardHasLocal && containsIgnoreCase(wynnPartyMembers, localUsername)) {
				List<String> aligned = new ArrayList<>(rows.size());
				List<String> others = new ArrayList<>(wynnPartyMembers.size());
				for (String m : wynnPartyMembers) {
					if (!m.equalsIgnoreCase(localUsername)) {
						others.add(m);
					}
				}
				int oIdx = 0;
				for (int i = 0; i < rows.size(); i++) {
					if (i == localRowIdx) {
						aligned.add(localUsername);
					} else if (oIdx < others.size()) {
						aligned.add(others.get(oIdx++));
					}
				}
				if (aligned.size() == rows.size() && !candidates.contains(aligned)) {
					candidates.add(aligned);
				}
			}
		}

		// 2. Scoreboard omitted local player: wynnPartyMembers.size() - 1 == rows.size()
		if (!scoreboardHasLocal && localUsername != null && containsIgnoreCase(wynnPartyMembers, localUsername)) {
			if (wynnPartyMembers.size() - 1 == rows.size()) {
				List<String> withoutLocal = new ArrayList<>(rows.size());
				for (String m : wynnPartyMembers) {
					if (!m.equalsIgnoreCase(localUsername)) {
						withoutLocal.add(m);
					}
				}
				candidates.add(withoutLocal);
			}
		}

		// 3. Wynntils omitted local player: wynnPartyMembers.size() + 1 == rows.size()
		if (localUsername != null && !containsIgnoreCase(wynnPartyMembers, localUsername)) {
			if (wynnPartyMembers.size() + 1 == rows.size()) {
				if (scoreboardHasLocal) {
					List<String> aligned = new ArrayList<>(rows.size());
					int wIdx = 0;
					for (int i = 0; i < rows.size(); i++) {
						if (i == localRowIdx) {
							aligned.add(localUsername);
						} else if (wIdx < wynnPartyMembers.size()) {
							aligned.add(wynnPartyMembers.get(wIdx++));
						}
					}
					candidates.add(aligned);
				}
				List<String> localFirst = new ArrayList<>(rows.size());
				localFirst.add(localUsername);
				localFirst.addAll(wynnPartyMembers);
				if (!candidates.contains(localFirst)) {
					candidates.add(localFirst);
				}

				List<String> localLast = new ArrayList<>(rows.size());
				localLast.addAll(wynnPartyMembers);
				localLast.add(localUsername);
				if (!candidates.contains(localLast)) {
					candidates.add(localLast);
				}
			}
		}

		List<String> bestCandidate = null;
		int bestScore = -1;

		for (List<String> candidate : candidates) {
			int score = scoreCandidateRoster(rows, candidate, localUsername);
			if (score > bestScore) {
				bestScore = score;
				bestCandidate = candidate;
			}
		}

		if (bestCandidate != null && bestScore >= 0) {
			return bestCandidate;
		}

		return candidates.isEmpty() ? List.of() : candidates.get(0);
	}

	static int scoreCandidateRoster(List<ParsedPartyLine> rows, List<String> candidate, String localUsername) {
		if (candidate == null || rows == null || candidate.size() != rows.size()) {
			return -1;
		}
		int score = 0;
		for (int i = 0; i < rows.size(); i++) {
			ParsedPartyLine row = rows.get(i);
			String expectedUser = candidate.get(i);
			boolean isLocal = isLocalRow(row, localUsername);

			if (localUsername != null && expectedUser.equalsIgnoreCase(localUsername)) {
				if (isLocal) {
					score += 10;
				} else {
					return -1;
				}
				continue;
			} else if (isLocal) {
				return -1;
			}

			String known = PlayerNameResolver.canonicalize(row.nickname());
			if (known != null && known.equalsIgnoreCase(expectedUser)) {
				score += 5;
				continue;
			}

			String expectedLower = expectedUser.toLowerCase(Locale.ROOT);
			String nickLower = row.nickname().toLowerCase(Locale.ROOT);
			String cleanExpected = expectedLower.replaceAll("[^a-z0-9_]", "");
			String cleanNick = nickLower.replaceAll("[^a-z0-9_]", "");

			if (expectedLower.equals(nickLower) || cleanExpected.equals(cleanNick)) {
				score += 5;
			} else if (cleanExpected.startsWith(cleanNick) && cleanNick.length() >= 3) {
				score += 3;
			} else if (cleanNick.startsWith(cleanExpected) && cleanExpected.length() >= 3) {
				score += 3;
			}
		}
		return score;
	}

	static boolean isLocalRow(ParsedPartyLine row, String localUsername) {
		if (localUsername == null || localUsername.isBlank() || row == null || row.nickname() == null) {
			return false;
		}
		if (row.nickname().equalsIgnoreCase(localUsername)) {
			return true;
		}
		String known = PlayerNameResolver.canonicalize(row.nickname());
		if (known != null && known.equalsIgnoreCase(localUsername)) {
			return true;
		}
		String cleanLocal = localUsername.replaceAll("[^a-zA-Z0-9_]", "").toLowerCase(Locale.ROOT);
		String cleanNick = row.nickname().replaceAll("[^a-zA-Z0-9_]", "").toLowerCase(Locale.ROOT);
		if (!cleanNick.isEmpty() && cleanNick.length() >= 3 && cleanLocal.startsWith(cleanNick)) {
			return true;
		}
		return false;
	}

	public static List<String> debugState() {
		List<String> lines = new ArrayList<>();
		lines.add("--- Party Health Tracker ---");

		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.level == null) {
			lines.add("Level: null");
			return lines;
		}

		Scoreboard scoreboard = ScoreboardCapture.hasSidebar() ? ScoreboardCapture.scoreboard() : mc.level.getScoreboard();
		Objective sidebar = (scoreboard != null) ? scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) : null;
		lines.add("Sidebar: " + (sidebar != null ? sidebar.getName() : "none"));

		List<String> wynnMembers = WynntilsPartyBridge.getPartyMembers();
		lines.add("Wynntils party (" + wynnMembers.size() + "): " + String.join(", ", wynnMembers));

		WynntilsPartyBridge.LiveHealth localLive = WynntilsPartyBridge.getLocalPlayerHealth();
		lines.add("Local LiveHealth: " + (localLive != null ? (localLive.current() + "/" + localLive.max() + " (" + (int) (localLive.percent() * 100) + "%)") : "null"));

		lines.add("Tracked Health (" + HEALTH_BY_UUID.size() + "):");
		for (Map.Entry<UUID, PlayerHealthData> entry : HEALTH_BY_UUID.entrySet()) {
			PlayerHealthData data = entry.getValue();
			Player worldPlayer = mc.level.getPlayerByUUID(entry.getKey());
			String worldInfo = (worldPlayer != null) ? "in-world (" + worldPlayer.getGameProfile().name() + ")" : "not-loaded";
			lines.add(String.format(Locale.ROOT, "  [Slot %d] %s (%s): %d/%d (%.1f%%) overMax=%b %s", data.partySlot(), data.name(), entry.getKey().toString().substring(0, 8), data.hp(), data.maxHp(), data.percent() * 100f, data.overMax(), worldInfo));
		}

		lines.add("Loaded real players (" + mc.level.players().size() + "):");
		for (Player p : mc.level.players()) {
			if (isRealPlayer(mc, p)) {
				lines.add("  " + p.getGameProfile().name() + " (" + p.getUUID().toString().substring(0, 8) + ") custom=" + (p.getCustomName() != null ? p.getCustomName().getString() : "none"));
			}
		}

		return lines;
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

	private static UUID findTabUuid(Minecraft mc, String name, Set<UUID> claimed) {
		if (mc == null || mc.getConnection() == null || name == null) {
			return null;
		}
		for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
			if (info.getProfile() != null && info.getProfile().name() != null && info.getProfile().name().equalsIgnoreCase(name)) {
				UUID tabUuid = info.getProfile().id();
				if (tabUuid != null && (claimed == null || !claimed.contains(tabUuid))) {
					return tabUuid;
				}
			}
		}
		return null;
	}

	private static UUID claimOfflineUuid(Minecraft mc, String name, Set<UUID> claimed) {
		if (name == null || name.isBlank()) {
			return null;
		}
		UUID target = UUID_BY_PLAYER_NAME.get(name.toLowerCase(Locale.ROOT));
		if (target == null || claimed.contains(target)) {
			target = findTabUuid(mc, name, claimed);
		}
		if (target != null && !claimed.contains(target)) {
			claimed.add(target);
			UUID_BY_PLAYER_NAME.put(name.toLowerCase(Locale.ROOT), target);
			return target;
		}
		return null;
	}

	static String resolvePlayerRealName(Minecraft mc, UUID uuid, List<String> candidateRoster, int rowIndex) {
		if (mc != null && uuid != null) {
			if (mc.level != null) {
				Player player = mc.level.getPlayerByUUID(uuid);
				if (player != null && player.getGameProfile() != null && player.getGameProfile().name() != null) {
					String name = player.getGameProfile().name();
					UUID_BY_PLAYER_NAME.put(name.toLowerCase(Locale.ROOT), uuid);
					return name;
				}
			}
			if (mc.getConnection() != null) {
				PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
				if (info != null && info.getProfile() != null && info.getProfile().name() != null) {
					String name = info.getProfile().name();
					UUID_BY_PLAYER_NAME.put(name.toLowerCase(Locale.ROOT), uuid);
					return name;
				}
			}
		}
		if (candidateRoster != null && rowIndex >= 0 && rowIndex < candidateRoster.size()) {
			String cand = candidateRoster.get(rowIndex);
			if (cand != null && !cand.isBlank()) {
				if (uuid != null) {
					UUID_BY_PLAYER_NAME.put(cand.toLowerCase(Locale.ROOT), uuid);
				}
				return cand;
			}
		}
		return null;
	}

	static int resolvePartySlot(String realName, String nickname, List<String> partyMembers, int fallbackSlot, boolean sharedNick) {
		if (partyMembers != null && !partyMembers.isEmpty()) {
			// 1. Direct match with real account name (authoritative)
			if (realName != null && !realName.isBlank()) {
				for (int i = 0; i < partyMembers.size(); i++) {
					String member = partyMembers.get(i);
					if (member != null && member.equalsIgnoreCase(realName)) {
						return i;
					}
				}
			}
			// 2. Nickname match ONLY when nickname is not shared across party members
			if (!sharedNick && nickname != null && !nickname.isBlank()) {
				for (int i = 0; i < partyMembers.size(); i++) {
					String member = partyMembers.get(i);
					if (member != null && member.equalsIgnoreCase(nickname)) {
						return i;
					}
				}
				String canonical = PlayerNameResolver.canonicalize(nickname);
				if (canonical != null && !canonical.equalsIgnoreCase(nickname)) {
					for (int i = 0; i < partyMembers.size(); i++) {
						String member = partyMembers.get(i);
						if (member != null && member.equalsIgnoreCase(canonical)) {
							return i;
						}
					}
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
