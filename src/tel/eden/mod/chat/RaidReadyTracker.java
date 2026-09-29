package tel.eden.mod.chat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;

/**
 * Tracks raid ready-up messages in chat, plays a ping sound when someone readies,
 * and sends an alert if 3 party members have been waiting for the player for 1 minute.
 */
public final class RaidReadyTracker {
	private static final Pattern READY_PATTERN = Pattern.compile("^(?:[\\u27A4\\u25C6\\u25B6\\[!\\]\\s*]+)?([a-zA-Z0-9_/ ()-]+?)\\s+is ready!\\s*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern UNREADY_PATTERN = Pattern.compile("^(?:[\\u27A4\\u25C6\\u25B6\\[!\\]\\s*]+)?([a-zA-Z0-9_/ ()-]+?)\\s+is no longer ready!\\s*$", Pattern.CASE_INSENSITIVE);
	private static final Pattern RAID_START_PATTERN = Pattern.compile("would like to start\\s+(.+?)!", Pattern.CASE_INSENSITIVE);

	private static final Set<String> readyPlayers = new HashSet<>();
	private static long waitingSince = 0L;
	private static boolean alertSent = false;

	private RaidReadyTracker() {
	}

	public static void onSystemChat(Component message) {
		String text = ChatText.normalizeWhitespace(message.getString());
		if (text.isEmpty()) {
			return;
		}

		if (RAID_START_PATTERN.matcher(text).find()) {
			reset();
			return;
		}

		Matcher readyMatcher = READY_PATTERN.matcher(text);
		if (readyMatcher.matches()) {
			String rawName = readyMatcher.group(1).trim();
			String realName = extractRealName(message, rawName);
			if (realName != null) {
				handlePlayerReady(realName);
			}
			return;
		}

		Matcher unreadyMatcher = UNREADY_PATTERN.matcher(text);
		if (unreadyMatcher.matches()) {
			String rawName = unreadyMatcher.group(1).trim();
			String realName = extractRealName(message, rawName);
			if (realName != null) {
				handlePlayerUnready(realName);
			}
		}
	}

	public static void onClientTick() {
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
		EdenModClient client = EdenModClient.instance();
		String self = client != null ? client.playerName() : null;
		if (self == null) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.player != null) {
				self = mc.player.getGameProfile().name();
			}
		}

		if (self == null) {
			return;
		}

		String selfLower = self.toLowerCase(Locale.ROOT);
		boolean selfReady = readyPlayers.contains(selfLower);
		int othersReady = readyPlayers.size() - (selfReady ? 1 : 0);

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

	private static void triggerOneMinuteAlert() {
		EdenModClient client = EdenModClient.instance();
		BridgeConfig config = client != null ? client.config() : null;
		if (config == null || config.raidReadyPing) {
			playPingSound(0.9f);
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.player != null) {
			Component alert = Component.empty().append(Component.literal("[EdenMod] ").withStyle(ChatFormatting.DARK_GREEN)).append(Component.literal("Ready up! ").withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true))).append(Component.literal("The other 3 party members have been ready for 1 minute.").withStyle(ChatFormatting.YELLOW));
			mc.player.displayClientMessage(alert, false);
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
			String cand1 = s.substring(0, slash).trim();
			String cand2 = s.substring(slash + 1).trim();
			String known = PlayerNameResolver.resolveKnown(cand2).orElse(null);
			if (known != null) {
				return known;
			}
			if (ChatText.IGN.matcher(cand1).matches()) {
				return cand1;
			}
			if (ChatText.IGN.matcher(cand2).matches()) {
				return cand2;
			}
		}

		// 3. "IGN(nickname)" or "nickname(IGN)" format
		int paren = s.indexOf('(');
		if (paren > 0 && s.endsWith(")")) {
			String cand1 = s.substring(0, paren).trim();
			String cand2 = s.substring(paren + 1, s.length() - 1).trim();
			String known1 = PlayerNameResolver.resolveKnown(cand1).orElse(null);
			if (known1 != null) {
				return known1;
			}
			String known2 = PlayerNameResolver.resolveKnown(cand2).orElse(null);
			if (known2 != null) {
				return known2;
			}
			if (ChatText.IGN.matcher(cand1).matches()) {
				return cand1;
			}
			if (ChatText.IGN.matcher(cand2).matches()) {
				return cand2;
			}
		}

		// 4. Fall back to PlayerNameResolver
		String resolved = PlayerNameResolver.resolve(message, s);
		if (resolved != null && ChatText.IGN.matcher(resolved).matches()) {
			return resolved;
		}
		return s;
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
