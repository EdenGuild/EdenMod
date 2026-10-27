package tel.eden.mod.war;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;

/**
 * Sounds a loud bell ping when an attack timer you are standing in runs low, and for the
 * in-war battle countdown.
 */
public final class WarStartPingTracker {
	private WarStartPingTracker() {
	}

	private static final Pattern COUNTDOWN_25S = Pattern.compile("\\bThe war battle will start in 25 seconds?[\\.!]*", Pattern.CASE_INSENSITIVE);

	private static volatile long generation = 0L;
	// Territories whose attack timer bell already rang for the current attack, so each
	// attack rings once. Cleared when the attack ends (not when its timer briefly drops
	// off the sidebar, which would ring it again). Client thread only.
	private static final Set<String> timerBellRung = new HashSet<>();

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

	public static BellAction processChat(String rawLine, BridgeConfig config) {
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

		return BellAction.NONE;
	}

	public static void onChat(String rawLine, BridgeConfig config) {
		BellAction action = processChat(rawLine, config);
		if (action.triggered()) {
			scheduleBell(action.delaySeconds());
		}
	}

	/**
	 * Ring the attack timer bell if it's due. Called every tick (client thread) from
	 * {@link AttackTimerMenu#onTick} with the seconds left on each attack timer.
	 */
	static void onTimers(Map<String, Integer> secondsLeft, Supplier<String> standingIn) {
		BridgeConfig config = EdenModClient.instance().config();
		if (shouldRingTimerBell(secondsLeft, standingIn, config != null ? config.warAttackTimerBellSeconds : 0)) {
			playBell(Minecraft.getInstance());
		}
	}

	/**
	 * Whether an attack timer is at {@code alertSeconds} or less while you stand in its
	 * territory. Each attack rings once, so walking in late still rings but staying doesn't
	 * repeat it.
	 */
	static boolean shouldRingTimerBell(Map<String, Integer> secondsLeft, Supplier<String> standingIn, int alertSeconds) {
		if (alertSeconds <= 0) {
			return false;
		}
		String here = null;
		boolean looked = false;
		boolean ring = false;
		for (Map.Entry<String, Integer> entry : secondsLeft.entrySet()) {
			if (entry.getValue() > alertSeconds || timerBellRung.contains(entry.getKey())) {
				continue;
			}
			if (!looked) {
				here = standingIn.get(); // only looked up once some timer is due
				looked = true;
			}
			if (entry.getKey().equals(here)) {
				timerBellRung.add(entry.getKey());
				ring = true;
			}
		}
		return ring;
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

	/** The attack on {@code territory} is over, so its next one can ring (client thread). */
	static void forgetTimerBell(String territory) {
		timerBellRung.remove(territory);
	}

	/** Forget every rung attack timer bell (client thread; full reset). */
	static void clearTimerBells() {
		timerBellRung.clear();
	}

	public static long getGeneration() {
		return generation;
	}

	private static boolean isNonWarChat(String clean) {
		return clean.contains("[Guild") || clean.contains("[GUILD") || clean.contains("[Allies") || clean.contains("[ALLIES") || clean.contains("[Party") || clean.contains("[PARTY") || clean.contains(": ");
	}
}
