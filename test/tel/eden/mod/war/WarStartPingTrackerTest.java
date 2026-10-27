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
		assertEquals(0, config.warBattleStartBellSeconds);
		assertEquals(0, config.warAttackTimerBellSeconds);

		assertEquals(-1, WarStartPingTracker.calculateDelay(25, config.warBattleStartBellSeconds));
		assertFalse(WarStartPingTracker.processChat("The war battle will start in 25 seconds.", config).triggered());
		assertFalse(WarStartPingTracker.shouldRingTimerBell(Map.of("Detlas", 5), () -> "Detlas", config.warAttackTimerBellSeconds));
	}

	@Test
	void warBattleStartBellTimingCalculation() {
		// Selecting 10s means alert triggers 10s before war starts, i.e. 15s delay after the 25s message
		config.warBattleStartBellSeconds = 10;
		assertEquals(15, WarStartPingTracker.calculateDelay(25, 10));

		var action = WarStartPingTracker.processChat("The war battle will start in 25 seconds.", config);
		assertTrue(action.triggered());
		assertEquals(15, action.delaySeconds());

		// Selecting 25s plays immediately (0s delay)
		config.warBattleStartBellSeconds = 25;
		var immediateAction = WarStartPingTracker.processChat("The war battle will start in 25 seconds!", config);
		assertTrue(immediateAction.triggered());
		assertEquals(0, immediateAction.delaySeconds());

		// Selecting 1s delays by 24s
		config.warBattleStartBellSeconds = 1;
		assertEquals(24, WarStartPingTracker.calculateDelay(25, 1));

		// Punctuation and color code handling
		var formattedAction = WarStartPingTracker.processChat("§6The war battle will start in §e25 seconds§6.", config);
		assertTrue(formattedAction.triggered());
		assertEquals(24, formattedAction.delaySeconds());

		// Wynncraft font tags
		String wynncraftMsg = "§c§{fr:cp}󏿼󏿿󏿾§{fr:d} The war battle will start in 25 seconds.";
		assertTrue(WarStartPingTracker.processChat(wynncraftMsg, config).triggered());

		// Suppressed on player and channel chat
		assertFalse(WarStartPingTracker.processChat("[Guild] Player: The war battle will start in 25 seconds.", config).triggered());
		assertFalse(WarStartPingTracker.processChat("[Party] Player: The war battle will start in 25 seconds.", config).triggered());
		assertFalse(WarStartPingTracker.processChat("[Allies] Player: The war battle will start in 25 seconds.", config).triggered());
		assertFalse(WarStartPingTracker.processChat("Player: The war battle will start in 25 seconds.", config).triggered());

		// The 30s territory message is the attack timer's job now, not a chat bell
		assertFalse(WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds.", config).triggered());
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

	@Test
	void warBeginAndCancelReset() {
		long genBefore = WarStartPingTracker.getGeneration();
		WarStartPingTracker.processChat("The battle has begun!", config);
		assertTrue(WarStartPingTracker.getGeneration() > genBefore);

		long genAfterBegun = WarStartPingTracker.getGeneration();
		WarStartPingTracker.processChat("The war has been canceled and refunded.", config);
		assertTrue(WarStartPingTracker.getGeneration() > genAfterBegun);
	}
}
