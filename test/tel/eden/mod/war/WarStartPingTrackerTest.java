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
		WarStartPingTracker.reset();
	}

	@Test
	void defaultsAreOff() {
		assertEquals(0, config.warBattleStartBellSeconds);
		assertEquals(0, config.warTerritoryWarpBellSeconds);
		assertFalse(config.warTerritoryWarpBellQueuedOnly);

		assertEquals(-1, WarStartPingTracker.calculateDelay(25, config.warBattleStartBellSeconds));
		assertEquals(-1, WarStartPingTracker.calculateDelay(30, config.warTerritoryWarpBellSeconds));

		assertFalse(WarStartPingTracker.processChat("The war battle will start in 25 seconds.", config, t -> true).triggered());
		assertFalse(WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds.", config, t -> true).triggered());
	}

	@Test
	void warBattleStartBellTimingCalculation() {
		// Selecting 10s means alert triggers 10s before war starts, i.e. 15s delay after the 25s message
		config.warBattleStartBellSeconds = 10;
		assertEquals(15, WarStartPingTracker.calculateDelay(25, 10));

		var action = WarStartPingTracker.processChat("The war battle will start in 25 seconds.", config, null);
		assertTrue(action.triggered());
		assertEquals(15, action.delaySeconds());

		// Selecting 25s plays immediately (0s delay)
		config.warBattleStartBellSeconds = 25;
		var immediateAction = WarStartPingTracker.processChat("The war battle will start in 25 seconds!", config, null);
		assertTrue(immediateAction.triggered());
		assertEquals(0, immediateAction.delaySeconds());

		// Selecting 1s delays by 24s
		config.warBattleStartBellSeconds = 1;
		assertEquals(24, WarStartPingTracker.calculateDelay(25, 1));

		// Punctuation and color code handling
		var formattedAction = WarStartPingTracker.processChat("§6The war battle will start in §e25 seconds§6.", config, null);
		assertTrue(formattedAction.triggered());
		assertEquals(24, formattedAction.delaySeconds());

		// Wynncraft font tags
		String wynncraftMsg = "\u00a7c\u00a7{fr:cp}\uDAFF\uDFFC\uE006\uDAFF\uDFFF\uE002\uDAFF\uDFFE\u00a7{fr:d} The war battle will start in 25 seconds.";
		assertTrue(WarStartPingTracker.processChat(wynncraftMsg, config, null).triggered());

		// Suppressed on player and channel chat
		assertFalse(WarStartPingTracker.processChat("[Guild] Player: The war battle will start in 25 seconds.", config, null).triggered());
		assertFalse(WarStartPingTracker.processChat("[Party] Player: The war battle will start in 25 seconds.", config, null).triggered());
		assertFalse(WarStartPingTracker.processChat("[Allies] Player: The war battle will start in 25 seconds.", config, null).triggered());
		assertFalse(WarStartPingTracker.processChat("Player: The war battle will start in 25 seconds.", config, null).triggered());

		// 30s territory message does not trigger war battle setting
		assertFalse(WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds.", config, null).triggered());
	}

	@Test
	void territoryWarpBellTimingCalculation() {
		// Selecting 10s means alert triggers 10s before war, i.e. 20s delay after the 30s message
		config.warTerritoryWarpBellSeconds = 10;
		assertEquals(20, WarStartPingTracker.calculateDelay(30, 10));

		var action = WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds.", config, null);
		assertTrue(action.triggered());
		assertEquals(20, action.delaySeconds());

		// Selecting 30s plays immediately (0s delay)
		config.warTerritoryWarpBellSeconds = 30;
		var immediateAction = WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds!", config, null);
		assertTrue(immediateAction.triggered());
		assertEquals(0, immediateAction.delaySeconds());

		// Formatted text
		var formatted = WarStartPingTracker.processChat("§6The war for §eBig Mushroom Cave§6 will start in 30 seconds.", config, null);
		assertTrue(formatted.triggered());

		// 25s battle message does not trigger territory warp setting
		assertFalse(WarStartPingTracker.processChat("The war battle will start in 25 seconds.", config, null).triggered());

		// Player chat suppressed
		assertFalse(WarStartPingTracker.processChat("[Guild] Alice: The war for Big Mushroom Cave will start in 30 seconds.", config, null).triggered());
	}

	@Test
	void territoryWarpBellQueuedOnlyFilter() {
		// When warp bell is 0 (off), queued-only toggle does not override it
		config.warTerritoryWarpBellSeconds = 0;
		config.warTerritoryWarpBellQueuedOnly = true;
		assertFalse(WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds.", config, t -> true).triggered());

		// When warp bell is 10s and queued-only is on:
		config.warTerritoryWarpBellSeconds = 10;
		// 1) Queued by player -> triggers with 20s delay
		var queuedAction = WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds.", config, "Big Mushroom Cave"::equalsIgnoreCase);
		assertTrue(queuedAction.triggered());
		assertEquals(20, queuedAction.delaySeconds());

		// 2) Queued by someone else -> suppressed
		assertFalse(WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds.", config, "Detlas"::equalsIgnoreCase).triggered());

		// 3) Null check -> suppressed
		assertFalse(WarStartPingTracker.processChat("The war for Big Mushroom Cave will start in 30 seconds.", config, null).triggered());

		// 4) Queued-only setting does not affect territory war start bell
		config.warBattleStartBellSeconds = 10;
		var battleAction = WarStartPingTracker.processChat("The war battle will start in 25 seconds.", config, null);
		assertTrue(battleAction.triggered());
		assertEquals(15, battleAction.delaySeconds());
	}

	@Test
	void warBeginAndCancelReset() {
		long genBefore = WarStartPingTracker.getGeneration();
		WarStartPingTracker.processChat("The battle has begun!", config, null);
		assertTrue(WarStartPingTracker.getGeneration() > genBefore);

		long genAfterBegun = WarStartPingTracker.getGeneration();
		WarStartPingTracker.processChat("The war has been canceled and refunded.", config, null);
		assertTrue(WarStartPingTracker.getGeneration() > genAfterBegun);
	}
}
