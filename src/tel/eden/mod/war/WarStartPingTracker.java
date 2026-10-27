package tel.eden.mod.war;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

	// "The war battle will start in 25 seconds." opens the in-war countdown; Wynncraft
	// ends it with "5 seconds..." to "1 second...", the same lines the Radiance tracker reads.
	private static final Pattern BATTLE_COUNTDOWN = Pattern.compile("\\bThe war battle will start in \\d+ seconds?", Pattern.CASE_INSENSITIVE);
	private static final Pattern FINAL_COUNTDOWN = Pattern.compile("\\b[1-5]\\s+seconds?\\.\\.\\.", Pattern.CASE_INSENSITIVE);

	// Whether this war's start bell already rang; cleared when the battle begins, is
	// cancelled, or on a reset (world change, disconnect).
	private static volatile boolean battleBellRang;
	// Territories whose attack timer bell already rang for the current attack, so each
	// attack rings once. Cleared when the attack ends (not when its timer briefly drops
	// off the sidebar, which would ring it again). Client thread only.
	private static final Set<String> timerBellRung = new HashSet<>();

	/**
	 * Whether this war chat line should ring the war start bell: the first countdown line
	 * of a war, normally the 25s one. If lag drops that line, the next countdown line
	 * (down to the final "5 seconds..." ones) rings instead, still only once per war.
	 */
	public static boolean processChat(String rawLine, BridgeConfig config) {
		if (rawLine == null || rawLine.isBlank() || config == null) {
			return false;
		}
		String clean = WarDPS.clean(rawLine);
		if (isNonWarChat(clean)) {
			return false;
		}
		if (clean.contains("The battle has begun!") || clean.contains("canceled and refunded")) {
			reset();
			return false;
		}
		if (!config.warBattleStartBell || battleBellRang) {
			return false;
		}
		if (BATTLE_COUNTDOWN.matcher(clean).find() || FINAL_COUNTDOWN.matcher(clean).find()) {
			battleBellRang = true;
			return true;
		}
		return false;
	}

	public static void onChat(String rawLine, BridgeConfig config) {
		if (processChat(rawLine, config)) {
			playBell(Minecraft.getInstance());
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
		battleBellRang = false;
	}

	/** The attack on {@code territory} is over, so its next one can ring (client thread). */
	static void forgetTimerBell(String territory) {
		timerBellRung.remove(territory);
	}

	/** Forget every rung attack timer bell (client thread; full reset). */
	static void clearTimerBells() {
		timerBellRung.clear();
	}

	private static boolean isNonWarChat(String clean) {
		return clean.contains("[Guild") || clean.contains("[GUILD") || clean.contains("[Allies") || clean.contains("[ALLIES") || clean.contains("[Party") || clean.contains("[PARTY") || clean.contains(": ");
	}
}
