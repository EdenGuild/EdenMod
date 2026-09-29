package tel.eden.mod.chat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.war.ScoreboardCapture;

/**
 * Tracks raid ready-up messages in chat and live scoreboard pawns,
 * plays a ping sound when someone readies, and sends an alert if 3 party members
 * have been waiting for the player for 1 minute.
 */
public final class RaidReadyTracker {
	private static final Pattern READY_PATTERN = Pattern.compile("^(?:[\\u27A4\\u25C6\\u25B6\\u00BB\\u2192>\\[!\\]\\s*]+)?([a-zA-Z0-9_/ ()-]+?)\\s+is ready!\\s*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern UNREADY_PATTERN = Pattern.compile("^(?:[\\u27A4\\u25C6\\u25B6\\u00BB\\u2192>\\[!\\]\\s*]+)?([a-zA-Z0-9_/ ()-]+?)\\s+is no longer ready!\\s*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern RAID_START_PATTERN = Pattern.compile("^(?:[\\u27A4\\u25C6\\u25B6\\u00BB\\u2192>\\[!\\]\\s*]+)?([a-zA-Z0-9_/ ()-]+?)\\s+would like to start\\s+(.+?)!", Pattern.CASE_INSENSITIVE);

	private static final Set<String> readyPlayers = new HashSet<>();
	private static long waitingSince = 0L;
	private static boolean alertSent = false;
	private static int tickCounter = 0;
	private static PawnStatus lastPawnStatus = null;
	private static String lastRaidStartText = "";
	private static long lastRaidStartTime = 0L;

	public record PawnStatus(int ready, int unready, int empty, List<String> details, String rawLine) {
		public int partySize() {
			return ready + unready;
		}

		public int totalPieces() {
			return ready + unready + empty;
		}
	}

	private enum PawnColor {
		GREEN, GRAY, DARK_GRAY, UNKNOWN
	}

	private RaidReadyTracker() {
	}

	public static void onSystemChat(Component message) {
		String text = ChatText.normalizeWhitespace(message.getString());
		if (text.isEmpty()) {
			return;
		}

		Matcher startMatcher = RAID_START_PATTERN.matcher(text);
		if (startMatcher.find()) {
			long now = System.currentTimeMillis();
			if (now - lastRaidStartTime > 2000L || !text.equalsIgnoreCase(lastRaidStartText)) {
				lastRaidStartTime = now;
				lastRaidStartText = text;
				reset();
				String rawName = startMatcher.group(1).trim();
				String realName = extractRealName(message, rawName);
				if (realName != null) {
					handlePlayerReady(realName);
				}
			}
			return;
		}

		if (text.toLowerCase(Locale.ROOT).contains("would like to start")) {
			long now = System.currentTimeMillis();
			if (now - lastRaidStartTime > 2000L || !text.equalsIgnoreCase(lastRaidStartText)) {
				lastRaidStartTime = now;
				lastRaidStartText = text;
				reset();
			}
			return;
		}

		Matcher readyMatcher = READY_PATTERN.matcher(text);
		if (readyMatcher.matches()) {
			if (!isRaidPromptActive()) {
				return;
			}
			String rawName = readyMatcher.group(1).trim();
			String realName = extractRealName(message, rawName);
			if (realName != null) {
				handlePlayerReady(realName);
			}
			return;
		}

		Matcher unreadyMatcher = UNREADY_PATTERN.matcher(text);
		if (unreadyMatcher.matches()) {
			if (!isRaidPromptActive()) {
				return;
			}
			String rawName = unreadyMatcher.group(1).trim();
			String realName = extractRealName(message, rawName);
			if (realName != null) {
				handlePlayerUnready(realName);
			}
		}
	}

	public static void onClientTick() {
		tickCounter++;
		if (tickCounter % 10 == 0) {
			checkWaitingState();
		}

		if (waitingSince > 0L && !alertSent) {
			if (System.currentTimeMillis() - waitingSince >= 60_000L) {
				alertSent = true;
				triggerOneMinuteAlert();
			}
		}
	}

	private static void handlePlayerReady(String realName) {
		readyPlayers.add(realName.toLowerCase(Locale.ROOT));

		EdenModClient client = EdenModClient.instance();
		BridgeConfig config = client != null ? client.config() : null;
		if (config == null || config.raidReadyPing) {
			playPingSound(1.2f);
		}

		checkWaitingState();
	}

	private static void handlePlayerUnready(String realName) {
		readyPlayers.remove(realName.toLowerCase(Locale.ROOT));
		checkWaitingState();
	}

	private static void checkWaitingState() {
		// Scan live scoreboard pawns for authoritative party readiness
		lastPawnStatus = scanScoreboardPawns();
		if (lastPawnStatus == null) {
			Scoreboard scoreboard = sidebarScoreboard();
			if (scoreboard != null && System.currentTimeMillis() - lastRaidStartTime > 3000L) {
				if (!readyPlayers.isEmpty() || waitingSince > 0L) {
					reset();
				}
			}
			return;
		}

		EdenModClient client = EdenModClient.instance();
		String self = client != null ? client.playerName() : null;
		if (self == null) {
			try {
				Minecraft mc = Minecraft.getInstance();
				if (mc != null && mc.player != null) {
					self = mc.player.getGameProfile().name();
				}
			} catch (Throwable ignored) {
			}
		}

		if (self == null) {
			return;
		}

		String selfLower = self.toLowerCase(Locale.ROOT);
		boolean selfReady = readyPlayers.contains(selfLower);
		int chatOthersReady = readyPlayers.size() - (selfReady ? 1 : 0);

		int scoreboardOthersReady = 0;
		if (lastPawnStatus.totalPieces() > 0) {
			int readyPieces = lastPawnStatus.ready();
			// If all 4 slots are ready, raid is starting / all ready -> cancel waiting
			if (readyPieces >= 4) {
				waitingSince = 0L;
				alertSent = false;
				return;
			}
			if (selfReady) {
				scoreboardOthersReady = Math.max(0, readyPieces - 1);
			} else {
				scoreboardOthersReady = readyPieces;
			}
		}

		int othersReady = Math.max(chatOthersReady, scoreboardOthersReady);

		if (!selfReady && othersReady >= 3) {
			if (waitingSince == 0L) {
				waitingSince = System.currentTimeMillis();
				alertSent = false;
			}
		} else {
			waitingSince = 0L;
			alertSent = false;
		}
	}

	public static boolean isPawn(char c) {
		return c == '\uE085' || (c >= '\u2654' && c <= '\u265F');
	}

	public static PawnStatus scanScoreboardPawns() {
		Scoreboard scoreboard = sidebarScoreboard();
		if (scoreboard == null) {
			return null;
		}
		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (sidebar != null) {
			PawnStatus status = scanObjectivePawns(scoreboard, sidebar);
			if (status != null) {
				return status;
			}
		}
		for (Objective obj : scoreboard.getObjectives()) {
			if (obj != sidebar) {
				PawnStatus status = scanObjectivePawns(scoreboard, obj);
				if (status != null) {
					return status;
				}
			}
		}
		return null;
	}

	private static PawnStatus scanObjectivePawns(Scoreboard scoreboard, Objective obj) {
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(obj)) {
			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			Component component = team != null ? PlayerTeam.formatNameForTeam(team, entry.ownerName()) : entry.ownerName();
			String plain = component.getString();
			if (plain.toLowerCase(Locale.ROOT).contains("players") || plain.contains("\uE085")) {
				PawnStatus status = parsePawnComponent(component);
				if (status != null && status.totalPieces() > 0) {
					return status;
				}
			}
		}
		return null;
	}

	public static PawnStatus parsePawnComponent(Component component) {
		int[] ready = new int[1];
		int[] unready = new int[1];
		int[] empty = new int[1];
		List<String> details = new ArrayList<>();
		String rawLine = component.getString();

		component.visit((style, text) -> {
			if (text == null || text.isEmpty()) {
				return Optional.empty();
			}

			TextColor styleColor = style.getColor();
			TextColor currentColor = styleColor;

			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);

				// Handle section sign legacy formatting if embedded in string
				if (c == '\u00A7' && i + 1 < text.length()) {
					char code = Character.toLowerCase(text.charAt(i + 1));
					ChatFormatting format = ChatFormatting.getByCode(code);
					if (format != null) {
						if (format == ChatFormatting.RESET) {
							currentColor = styleColor;
						} else if (format.getColor() != null) {
							currentColor = TextColor.fromRgb(format.getColor());
						}
					}
					i++;
					continue;
				}

				if (isPawn(c)) {
					PawnColor colorType = classifyPawnColor(currentColor);
					String hex = colorHex(currentColor);
					int idx = ready[0] + unready[0] + empty[0] + 1;
					switch (colorType) {
						case GREEN -> {
							ready[0]++;
							details.add("piece " + idx + ": green [READY] (" + hex + ")");
						}
						case GRAY -> {
							unready[0]++;
							details.add("piece " + idx + ": gray [UNREADY] (" + hex + ")");
						}
						case DARK_GRAY -> {
							empty[0]++;
							details.add("piece " + idx + ": dark gray [EMPTY] (" + hex + ")");
						}
						case UNKNOWN -> {
							unready[0]++;
							details.add("piece " + idx + ": unknown [treated as UNREADY] (" + hex + ")");
						}
					}
				}
			}
			return Optional.empty();
		}, Style.EMPTY);

		return new PawnStatus(ready[0], unready[0], empty[0], details, rawLine);
	}

	private static PawnColor classifyPawnColor(TextColor color) {
		if (color == null) {
			return PawnColor.GRAY;
		}
		int rgb = color.getValue();
		if (rgb == 0x55FF55 || rgb == 0x00AA00) {
			return PawnColor.GREEN;
		}
		if (rgb == 0x555555) {
			return PawnColor.DARK_GRAY;
		}
		if (rgb == 0xAAAAAA || rgb == 0xFFFFFF) {
			return PawnColor.GRAY;
		}
		return PawnColor.UNKNOWN;
	}

	private static String colorHex(TextColor color) {
		if (color == null) {
			return "none";
		}
		return String.format("#%06X", color.getValue());
	}

	private static Scoreboard sidebarScoreboard() {
		if (ScoreboardCapture.hasSidebar()) {
			return ScoreboardCapture.scoreboard();
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			return mc != null && mc.level != null ? mc.level.getScoreboard() : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static void triggerOneMinuteAlert() {
		EdenModClient client = EdenModClient.instance();
		BridgeConfig config = client != null ? client.config() : null;
		if (config == null || config.raidReadyPing) {
			playPingSound(0.9f);
		}

		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.player != null) {
				Component alert = Component.empty().append(Component.literal("[EdenMod] ").withStyle(ChatFormatting.DARK_GREEN)).append(Component.literal("Ready up! ").withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true))).append(Component.literal("The other 3 party members have been ready for 1 minute.").withStyle(ChatFormatting.YELLOW));
				mc.player.displayClientMessage(alert, false);
			}
		} catch (Throwable ignored) {
		}
	}

	private static void playPingSound(float pitch) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.getSoundManager() != null) {
				mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch));
			}
		} catch (Throwable ignored) {
		}
	}

	public static void reset() {
		readyPlayers.clear();
		waitingSince = 0L;
		alertSent = false;
		lastPawnStatus = null;
	}

	public static boolean isRaidPromptActive() {
		Scoreboard scoreboard = sidebarScoreboard();
		if (scoreboard == null) {
			// In offline test environment without a world, allow testing chat patterns
			return true;
		}
		return scanScoreboardPawns() != null || (System.currentTimeMillis() - lastRaidStartTime <= 3000L);
	}

	public static List<String> debugState() {
		List<String> lines = new ArrayList<>();
		lines.add("--- Raid Ready Tracker ---");
		lines.add("chat ready players: " + (readyPlayers.isEmpty() ? "(none)" : String.join(", ", readyPlayers)));

		EdenModClient client = EdenModClient.instance();
		String self = client != null ? client.playerName() : null;
		if (self == null) {
			try {
				Minecraft mc = Minecraft.getInstance();
				if (mc != null && mc.player != null) {
					self = mc.player.getGameProfile().name();
				}
			} catch (Throwable ignored) {
			}
		}
		boolean selfReady = self != null && readyPlayers.contains(self.toLowerCase(Locale.ROOT));
		lines.add("local player: " + (self != null ? self : "unknown") + " (selfReady=" + selfReady + ")");

		PawnStatus pawns = scanScoreboardPawns();
		if (pawns == null) {
			lines.add("scoreboard pawns: (none active - ready check inactive)");
		} else {
			lines.add("scoreboard line: " + pawns.rawLine());
			lines.add("scoreboard breakdown: " + pawns.ready() + " ready, " + pawns.unready() + " unready, " + pawns.empty() + " empty (party: " + pawns.partySize() + "/4)");
			for (String detail : pawns.details()) {
				lines.add("  " + detail);
			}
		}

		int chatOthersReady = readyPlayers.size() - (selfReady ? 1 : 0);
		int sbOthersReady = 0;
		if (pawns != null && pawns.totalPieces() > 0) {
			sbOthersReady = selfReady ? Math.max(0, pawns.ready() - 1) : pawns.ready();
		}
		int othersReady = pawns != null ? Math.max(chatOthersReady, sbOthersReady) : 0;
		lines.add("effective others ready: " + othersReady + " (chat=" + chatOthersReady + ", scoreboard=" + sbOthersReady + ")");

		if (pawns == null) {
			lines.add("1-min waiting alert: inactive (no raid ready prompt on scoreboard)");
		} else if (waitingSince > 0L) {
			long elapsedSec = (System.currentTimeMillis() - waitingSince) / 1000L;
			lines.add("1-min waiting alert: ACTIVE (" + elapsedSec + "s elapsed, alertSent=" + alertSent + ")");
		} else {
			lines.add("1-min waiting alert: inactive" + (othersReady >= 3 && selfReady ? " (you are already ready)" : (othersReady < 3 ? " (need 3 others ready)" : "")));
		}
		return lines;
	}

	/**
	 * De-nicknames a displayed name using IGN/nickname, IGN(nickname), nickname(IGN),
	 * or hover/insertion metadata.
	 */
	public static String extractRealName(Component message, String displayed) {
		if (displayed == null || displayed.isBlank()) {
			return displayed;
		}
		String s = displayed.trim();

		// 1. Hover/insertion resolution first (most authoritative)
		String hoverResolved = ChatText.resolveRealNameAnywhere(message, s);
		if (hoverResolved != null && ChatText.IGN.matcher(hoverResolved).matches()) {
			return hoverResolved;
		}

		// 2. "realIGN/nickname" format
		int slash = s.indexOf('/');
		if (slash > 0) {
			String resolved = resolveCandidatePair(s.substring(0, slash).trim(), s.substring(slash + 1).trim());
			if (resolved != null) {
				return resolved;
			}
		}

		// 3. "IGN(nickname)" or "nickname(IGN)" format
		int paren = s.indexOf('(');
		if (paren > 0 && s.endsWith(")")) {
			String resolved = resolveCandidatePair(s.substring(0, paren).trim(), s.substring(paren + 1, s.length() - 1).trim());
			if (resolved != null) {
				return resolved;
			}
		}

		// 4. Fall back to PlayerNameResolver (checks alias cache and tab list)
		String resolved = PlayerNameResolver.resolve(message, s);
		if (resolved != null && ChatText.IGN.matcher(resolved).matches()) {
			return resolved;
		}
		return s;
	}

	private static String resolveCandidatePair(String first, String second) {
		String known1 = PlayerNameResolver.resolveKnown(first).orElse(null);
		if (known1 != null) {
			return known1;
		}
		String known2 = PlayerNameResolver.resolveKnown(second).orElse(null);
		if (known2 != null) {
			return known2;
		}
		if (ChatText.IGN.matcher(first).matches()) {
			return first;
		}
		if (ChatText.IGN.matcher(second).matches()) {
			return second;
		}
		return null;
	}

	// Package-private testing methods
	static Set<String> readyPlayersForTesting() {
		return readyPlayers;
	}

	static long waitingSinceForTesting() {
		return waitingSince;
	}

	static boolean alertSentForTesting() {
		return alertSent;
	}

	static void setWaitingSinceForTesting(long time) {
		waitingSince = time;
	}
}
