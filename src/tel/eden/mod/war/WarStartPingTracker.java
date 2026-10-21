package tel.eden.mod.war;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import tel.eden.mod.config.BridgeConfig;

/**
 * Sounds a loud bell ping before a guild war battle starts, based on Wynncraft's
 * countdown announcement ("The war battle will start in 25 seconds.") or the
 * "5 seconds..." fallback when entering a war late.
 */
public final class WarStartPingTracker {
	private WarStartPingTracker() {
	}

	private static final Pattern COUNTDOWN_25S_PATTERN = Pattern.compile("The war battle will start in 25 seconds?[\\.!]*", Pattern.CASE_INSENSITIVE);
	private static final Pattern COUNTDOWN_5S_FALLBACK = Pattern.compile("\\b5 seconds\\.{2,}", Pattern.CASE_INSENSITIVE);
	private static final Pattern WAR_BEGUN_PATTERN = Pattern.compile("The battle has begun!", Pattern.CASE_INSENSITIVE);

	private static volatile long battleStartTimeMs = 0L;
	private static volatile boolean pendingAlert = false;
	private static volatile boolean alertPlayed = false;
	private static volatile long timerSeq = 0L;

	public static int calculateDelay(int messageSeconds, BridgeConfig config) {
		if (config == null || !config.warStartPing) {
			return -1;
		}
		int alertSeconds = Math.max(1, Math.min(25, config.warStartPingSeconds));
		return Math.max(0, messageSeconds - alertSeconds);
	}

	public static int processChat(String rawLine, long nowMs, BridgeConfig config) {
		if (rawLine == null || rawLine.isBlank()) {
			return 0;
		}
		String clean = WarDPS.clean(rawLine);
		if (isGuildOrNonWarChat(clean)) {
			return 0;
		}

		Matcher m25 = COUNTDOWN_25S_PATTERN.matcher(clean);
		if (m25.find()) {
			battleStartTimeMs = nowMs + 25_000L;
			int delaySeconds = calculateDelay(25, config);
			startTimer(delaySeconds, config);
			return 25;
		}

		Matcher m5 = COUNTDOWN_5S_FALLBACK.matcher(clean);
		if (m5.find() && !pendingAlert && !alertPlayed) {
			if (config != null && config.warStartPing) {
				int alertSeconds = Math.max(1, Math.min(25, config.warStartPingSeconds));
				if (alertSeconds < 5) {
					battleStartTimeMs = nowMs + 5_000L;
					int delaySeconds = 5 - alertSeconds;
					startTimer(delaySeconds, config);
					return 5;
				}
			}
		}

		if (WAR_BEGUN_PATTERN.matcher(clean).find() || clean.contains("canceled and refunded")) {
			reset();
			return -1;
		}

		return 0;
	}

	public static void startTimer(int delaySeconds, BridgeConfig config) {
		long seq = ++timerSeq;
		if (delaySeconds < 0 || config == null || !config.warStartPing) {
			pendingAlert = false;
			alertPlayed = false;
			return;
		}

		pendingAlert = true;
		alertPlayed = false;

		if (delaySeconds == 0) {
			alertPlayed = true;
			pendingAlert = false;
			playPing(Minecraft.getInstance());
			return;
		}

		CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> {
			Minecraft client = Minecraft.getInstance();
			if (client != null) {
				client.execute(() -> {
					if (timerSeq == seq && config != null && config.warStartPing) {
						alertPlayed = true;
						pendingAlert = false;
						playPing(client);
					}
				});
			}
		});
	}

	public static boolean shouldTriggerPing(long nowMs, BridgeConfig config) {
		if (!pendingAlert || alertPlayed) {
			return false;
		}
		if (config == null || !config.warStartPing) {
			return false;
		}
		if (battleStartTimeMs <= 0L) {
			return false;
		}

		int alertSeconds = Math.max(1, Math.min(25, config.warStartPingSeconds));
		long targetAlertTimeMs = battleStartTimeMs - (alertSeconds * 1000L);

		if (nowMs >= targetAlertTimeMs) {
			if (nowMs <= battleStartTimeMs + 1000L) {
				alertPlayed = true;
				pendingAlert = false;
				return true;
			}
			// Stale/expired past the battle start; cancel without playing late ping
			alertPlayed = true;
			pendingAlert = false;
			return false;
		}
		return false;
	}

	public static void onChat(String rawLine, BridgeConfig config) {
		processChat(rawLine, System.currentTimeMillis(), config);
	}

	public static void onClientTick(BridgeConfig config, Minecraft client) {
		if (shouldTriggerPing(System.currentTimeMillis(), config)) {
			playPing(client);
		}
	}

	public static void playPing(Minecraft client) {
		if (client == null) {
			return;
		}
		try {
			if (client.getSoundManager() != null) {
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 1.0f, 2.0f));
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f, 2.0f));
			}
		} catch (Throwable ignored) {
		}
		try {
			if (client.player != null) {
				client.player.playSound(SoundEvents.BELL_BLOCK, 2.0f, 1.0f);
				client.player.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 2.0f, 1.0f);
			}
		} catch (Throwable ignored) {
		}
	}

	public static void reset() {
		timerSeq++;
		battleStartTimeMs = 0L;
		pendingAlert = false;
		alertPlayed = false;
	}

	public static boolean isPendingAlert() {
		return pendingAlert;
	}

	public static boolean isAlertPlayed() {
		return alertPlayed;
	}

	public static long getBattleStartTimeMs() {
		return battleStartTimeMs;
	}

	private static boolean isGuildOrNonWarChat(String clean) {
		return clean.contains("[Guild") || clean.contains("[GUILD") || clean.contains("[Allies") || clean.contains("[ALLIES") || clean.contains("[Party") || clean.contains("[PARTY") || clean.contains(": ");
	}
}
