package tel.eden.mod.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tel.eden.mod.config.BridgeConfig;

class WarStartPingTrackerTest {
	private BridgeConfig config;

	@BeforeEach
	void setUp() {
		config = new BridgeConfig();
		WarStartPingTracker.reset();
		WarStartPingTracker.clearTimerBells();
	}

	@Test
	void defaultsAreOff() {
		assertFalse(config.warBattleStartBell);
		assertEquals(0, config.warAttackTimerBellSeconds);

		assertFalse(WarStartPingTracker.processChat("The war battle will start in 25 seconds.", config));
		assertFalse(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 5), () -> "Detlas", config.warAttackTimerBellSeconds));
	}

	@Test
	void warStartBellRingsOnceAtTheCountdown() {
		config.warBattleStartBell = true;
		assertTrue(WarStartPingTracker.processChat("The war battle will start in 25 seconds.", config));
		// The rest of the countdown doesn't ring again
		assertFalse(WarStartPingTracker.processChat("5 seconds...", config));
		assertFalse(WarStartPingTracker.processChat("1 second...", config));

		// The next war rings again
		WarStartPingTracker.processChat("... The battle has begun!", config);
		assertTrue(WarStartPingTracker.processChat("§6The war battle will start in §e25 seconds§6.", config));
	}

	@Test
	void warStartBellFallsBackToTheFinalCountdownWhenTheFirstLineIsMissed() {
		config.warBattleStartBell = true;
		// Lag dropped the 25s line: "5 seconds..." rings instead, once
		assertTrue(WarStartPingTracker.processChat("5 seconds...", config));
		assertFalse(WarStartPingTracker.processChat("4 seconds...", config));

		// A cancelled war clears it too
		WarStartPingTracker.processChat("Your active attack was canceled and refunded to your headquarter.", config);
		assertTrue(WarStartPingTracker.processChat("3 seconds...", config));
	}

	@Test
	void warStartBellIgnoresChatAndOtherLines() {
		config.warBattleStartBell = true;
		// Wynncraft font tags still match
		String wynncraftMsg = "§c§{fr:cp}󏿼󏿿󏿾§{fr:d} The war battle will start in 25 seconds.";
		assertTrue(WarStartPingTracker.processChat(wynncraftMsg, config));
		WarStartPingTracker.reset();

		// Player and channel chat
		assertFalse(WarStartPingTracker.processChat("[Guild] Player: The war battle will start in 25 seconds.", config));
		assertFalse(WarStartPingTracker.processChat("[Party] Player: The war battle will start in 25 seconds.", config));
		assertFalse(WarStartPingTracker.processChat("[Allies] Player: The war battle will start in 25 seconds.", config));
		assertFalse(WarStartPingTracker.processChat("Player: 5 seconds...", config));
		// Not the battle countdown: the 30s territory line (the attack timer's job) and other numbers
		assertFalse(WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds.", config));
		assertFalse(WarStartPingTracker.processChat("15 seconds...", config));
	}

	@Test
	void attackTimerBellRingsOnceWhenDueWhileStandingInTheTerritory() {
		// Not due yet, then due: rings once, and stays quiet while the timer keeps counting down
		assertFalse(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 31), () -> "Detlas", 30));
		assertTrue(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 30), () -> "Detlas", 30));
		assertFalse(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 29), () -> "Detlas", 30));

		// The attack ends, then a new one on the same territory rings again
		WarStartPingTracker.forgetTimerBell("Detlas");
		assertTrue(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 12), () -> "Detlas", 30));
	}

	@Test
	void attackTimerBellNeedsYouStandingInIt() {
		// Due, but you're elsewhere: no ring, and it isn't used up
		assertFalse(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 20), () -> "Ragni", 30));
		assertFalse(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 20), () -> null, 30));
		// Walking in late still rings
		assertTrue(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 8), () -> "Detlas", 30));
		// Only the territory you're in counts
		assertFalse(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 5, "Ragni", 5), () -> "Detlas", 30));
		assertTrue(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 5, "Ragni", 5), () -> "Ragni", 30));
	}

	@Test
	void attackTimerBellOnlyLooksUpWhereYouAreOnceATimerIsDue() {
		AtomicInteger lookups = new AtomicInteger();
		assertFalse(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 300, "Ragni", Integer.MAX_VALUE), () -> {
			lookups.incrementAndGet();
			return "Detlas";
		}, 30));
		assertEquals(0, lookups.get());
	}
}
