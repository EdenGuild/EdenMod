package tel.eden.mod.war;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import tel.eden.mod.config.BridgeConfig;

/**
 * Sounds a loud bell ping for upcoming territory wars and in-war battle countdowns.
 */
public final class WarStartPingTracker {
	private WarStartPingTracker() {
	}

	private static final Pattern COUNTDOWN_25S = Pattern.compile("\\bThe war battle will start in 25 seconds?[\\.!]*", Pattern.CASE_INSENSITIVE);
	private static final Pattern COUNTDOWN_30S = Pattern.compile("\\bThe war for (.+?) will start in 30 seconds?[\\.!]*", Pattern.CASE_INSENSITIVE);

	private static volatile long generation = 0L;

	public record BellAction(int delaySeconds, boolean triggered) {
		public static final BellAction NONE = new BellAction(-1, false);
	}

	public static int calculateDelay(int messageSeconds, int alertSeconds) {
		if (alertSeconds <= 0) {
			return -1;
		}
		int clamped = Math.max(1, Math.min(messageSeconds, alertSeconds));
		return Math.max(0, messageSeconds - clamped);
	}

	public static BellAction processChat(String rawLine, BridgeConfig config, Predicate<String> queuedCheck) {
		if (rawLine == null || rawLine.isBlank() || config == null) {
			return BellAction.NONE;
		}
		String clean = WarDPS.clean(rawLine);
		if (isNonWarChat(clean)) {
			return BellAction.NONE;
		}

		if (clean.contains("The battle has begun!") || clean.contains("canceled and refunded")) {
			reset();
			return BellAction.NONE;
		}

		if (config.warBattleStartBellSeconds > 0 && COUNTDOWN_25S.matcher(clean).find()) {
			int delay = calculateDelay(25, config.warBattleStartBellSeconds);
			return new BellAction(delay, true);
		}

		Matcher m30 = COUNTDOWN_30S.matcher(clean);
		if (m30.find() && config.warTerritoryWarpBellSeconds > 0) {
			String territory = m30.group(1).trim();
			if (config.warTerritoryWarpBellQueuedOnly && (queuedCheck == null || !queuedCheck.test(territory))) {
				return BellAction.NONE;
			}
			int delay = calculateDelay(30, config.warTerritoryWarpBellSeconds);
			return new BellAction(delay, true);
		}

		return BellAction.NONE;
	}

	public static void onChat(String rawLine, BridgeConfig config) {
		BellAction action = processChat(rawLine, config, AttackTimerMenu::isQueuedByPlayer);
		if (action.triggered()) {
			scheduleBell(action.delaySeconds());
		}
	}

	public static void scheduleBell(int delaySeconds) {
		if (delaySeconds < 0) {
			return;
		}
		if (delaySeconds == 0) {
			playBell(Minecraft.getInstance());
			return;
		}
		long currentGen = ++generation;
		CompletableFuture.delayedExecutor(delaySeconds, TimeUnit.SECONDS).execute(() -> {
			Minecraft client = Minecraft.getInstance();
			if (client != null) {
				client.execute(() -> {
					if (currentGen == generation && client.level != null && client.player != null) {
						playBell(client);
					}
				});
			}
		});
	}

	public static void playBell(Minecraft client) {
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
		generation++;
	}

	public static long getGeneration() {
		return generation;
	}

	private static boolean isNonWarChat(String clean) {
		return clean.contains("[Guild") || clean.contains("[GUILD") || clean.contains("[Allies") || clean.contains("[ALLIES") || clean.contains("[Party") || clean.contains("[PARTY") || clean.contains(": ");
	}
}
