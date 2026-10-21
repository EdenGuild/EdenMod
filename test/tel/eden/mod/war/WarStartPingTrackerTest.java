package tel.eden.mod.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tel.eden.mod.config.BridgeConfig;

class WarStartPingTrackerTest {
	private BridgeConfig config;

	@BeforeEach
	void setUp() {
		config = new BridgeConfig();
		config.warStartPing = true;
		config.warStartPingSeconds = 5;
		WarStartPingTracker.reset();
	}

	@Test
	void testStandardCountdownMessage() {
		long now = 1_000_000L;
		int seconds = WarStartPingTracker.processChat("The war battle will start in 25 seconds.", now, config);
		assertEquals(25, seconds);
		assertEquals(now + 25_000L, WarStartPingTracker.getBattleStartTimeMs());
		assertTrue(WarStartPingTracker.isPendingAlert());
		assertFalse(WarStartPingTracker.isAlertPlayed());
	}

	@Test
	void testWynncraftActualLogMessage() {
		long now = 1_000_000L;
		// Actual raw message format observed on Wynncraft live war server
		String wynncraftMsg = "\u00a7c\u00a7{fr:cp}\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE\u00a7{fr:d} The war battle will start in 25 seconds.";
		int seconds = WarStartPingTracker.processChat(wynncraftMsg, now, config);
		assertEquals(25, seconds);
		assertEquals(now + 25_000L, WarStartPingTracker.getBattleStartTimeMs());
		assertTrue(WarStartPingTracker.isPendingAlert());

		// Actual battle begun line with font prefix
		String begunMsg = "\u00a7c\u00a7{fr:cp}\uDAFF\uDFFC\u00a7{fr:d} The battle has begun!";
		int begunResult = WarStartPingTracker.processChat(begunMsg, now + 25_000L, config);
		assertEquals(-1, begunResult);
		assertFalse(WarStartPingTracker.isPendingAlert());
	}

	@Test
	void testOptionalPunctuationCountdownMessages() {
		long now = 1_000_000L;
		// Without trailing period
		int s1 = WarStartPingTracker.processChat("The war battle will start in 25 seconds", now, config);
		assertEquals(25, s1);

		// With exclamation point
		int s2 = WarStartPingTracker.processChat("The war battle will start in 25 seconds!", now, config);
		assertEquals(25, s2);

		// Territory variant without period
		int s3 = WarStartPingTracker.processChat("The war for Detlas will start in 25 seconds", now, config);
		assertEquals(25, s3);
	}

	@Test
	void testCalculateDelayTimerMath() {
		config.warStartPing = true;
		config.warStartPingSeconds = 3;
		assertEquals(22, WarStartPingTracker.calculateDelay(25, config));

		config.warStartPingSeconds = 25;
		assertEquals(0, WarStartPingTracker.calculateDelay(25, config));

		config.warStartPingSeconds = 1;
		assertEquals(24, WarStartPingTracker.calculateDelay(25, config));

		config.warStartPing = false;
		assertEquals(-1, WarStartPingTracker.calculateDelay(25, config));
	}

	@Test
	void testTerritoryVariant() {
		long now = 1_000_000L;
		int seconds = WarStartPingTracker.processChat("The war for Detlas will start in 25 seconds.", now, config);
		assertEquals(25, seconds);
		assertEquals(now + 25_000L, WarStartPingTracker.getBattleStartTimeMs());
	}

	@Test
	void testColorCodeStripping() {
		long now = 1_000_000L;
		int seconds = WarStartPingTracker.processChat("§6The war battle will start in §e25 seconds§6.", now, config);
		assertEquals(25, seconds);
		assertEquals(now + 25_000L, WarStartPingTracker.getBattleStartTimeMs());
	}

	@Test
	void testSingularSecond() {
		long now = 1_000_000L;
		int seconds = WarStartPingTracker.processChat("The war battle will start in 1 second.", now, config);
		assertEquals(1, seconds);
		assertEquals(now + 1_000L, WarStartPingTracker.getBattleStartTimeMs());
	}

	@Test
	void testIgnorePlayerChannels() {
		long now = 1_000_000L;
		assertEquals(0, WarStartPingTracker.processChat("[Guild] Player: The war battle will start in 25 seconds.", now, config));
		assertEquals(0, WarStartPingTracker.processChat("[Party] Player: The war battle will start in 25 seconds.", now, config));
		assertEquals(0, WarStartPingTracker.processChat("[Allies] Player: The war battle will start in 25 seconds.", now, config));
		assertEquals(0, WarStartPingTracker.processChat("Good627: The war battle will start in 25 seconds.", now, config));
		assertFalse(WarStartPingTracker.isPendingAlert());
	}

	@Test
	void testTimingTrigger() {
		long t0 = 100_000L;
		config.warStartPingSeconds = 10;
		WarStartPingTracker.processChat("The war battle will start in 25 seconds.", t0, config);

		// Battle starts at 125_000L; alert is scheduled for 10s before (115_000L)
		assertFalse(WarStartPingTracker.shouldTriggerPing(114_900L, config));
		assertTrue(WarStartPingTracker.shouldTriggerPing(115_000L, config));
		assertFalse(WarStartPingTracker.shouldTriggerPing(115_100L, config));
		assertTrue(WarStartPingTracker.isAlertPlayed());
	}

	@Test
	void testImmediateTriggerFor25Seconds() {
		long t0 = 100_000L;
		config.warStartPingSeconds = 25;
		WarStartPingTracker.processChat("The war battle will start in 25 seconds.", t0, config);

		assertTrue(WarStartPingTracker.isAlertPlayed());
		assertFalse(WarStartPingTracker.isPendingAlert());
		assertFalse(WarStartPingTracker.shouldTriggerPing(t0 + 50L, config));
	}

	@Test
	void testDisabledConfigSuppressesPing() {
		long t0 = 100_000L;
		config.warStartPing = false;
		config.warStartPingSeconds = 5;
		WarStartPingTracker.processChat("The war battle will start in 25 seconds.", t0, config);

		// Target time reached but config is disabled
		assertFalse(WarStartPingTracker.shouldTriggerPing(120_000L, config));
	}

	@Test
	void testBattleBegunCancelsPendingAlert() {
		long t0 = 100_000L;
		config.warStartPingSeconds = 5;
		WarStartPingTracker.processChat("The war battle will start in 25 seconds.", t0, config);
		assertTrue(WarStartPingTracker.isPendingAlert());

		WarStartPingTracker.processChat("The battle has begun!", t0 + 10_000L, config);
		assertFalse(WarStartPingTracker.isPendingAlert());
		assertFalse(WarStartPingTracker.shouldTriggerPing(120_000L, config));
	}

	@Test
	void testCancellationCancelsAlert() {
		long t0 = 100_000L;
		config.warStartPingSeconds = 5;
		WarStartPingTracker.processChat("The war battle will start in 25 seconds.", t0, config);
		assertTrue(WarStartPingTracker.isPendingAlert());

		WarStartPingTracker.processChat("War canceled and refunded.", t0 + 5_000L, config);
		assertFalse(WarStartPingTracker.isPendingAlert());
	}
}
