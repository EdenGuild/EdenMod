package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LongEventStateTest {
	@Test
	void formatCountdownFormatsCorrectly() {
		// Non-positive clamps to 00:00:00
		assertEquals("00:00:00", LongEventState.formatCountdown(-500L, false));
		assertEquals("0d 00:00:00", LongEventState.formatCountdown(0L, true));

		// 1 hour, 2 minutes, 3 seconds = 3723 seconds = 3,723,000 ms
		long ms = (3600 + 120 + 3) * 1000L;
		assertEquals("01:02:03", LongEventState.formatCountdown(ms, false));

		// 2 days, 3 hours, 4 minutes, 5 seconds
		long daysMs = (2 * 86400 + 3 * 3600 + 4 * 60 + 5) * 1000L;
		assertEquals("2d 03:04:05", LongEventState.formatCountdown(daysMs, true));
	}

	@Test
	void slotCountdownTextMatchesSlotRules() {
		assertEquals("Complete to unlock the next mission", LongEventState.slotCountdownText("repeatable", 12345L, 0L));
		assertEquals("Complete to unlock the next mission", LongEventState.slotCountdownText("repeatable2", 12345L, 0L));

		long now = 1000000L;
		long resetAt = now + 3600 * 1000L;
		assertEquals("Resets in 01:00:00", LongEventState.slotCountdownText("daily", resetAt, now));

		long specialReset = now + 86400 * 1000L * 2 + 1800 * 1000L;
		assertEquals("Resets in 2d 00:30:00", LongEventState.slotCountdownText("special", specialReset, now));
		assertEquals("Resets in 2d 00:30:00", LongEventState.slotCountdownText("weekly", specialReset, now));
	}

	@Test
	void slotLookupAndRerollRules() {
		assertEquals("Daily: once per day", LongEventState.rerollRuleText("daily"));
		assertEquals("Special: once every 3 days", LongEventState.rerollRuleText("special"));
		assertEquals("Special: once every 3 days", LongEventState.rerollRuleText("weekly"));
		assertEquals("Repeatable: every 3 hours", LongEventState.rerollRuleText("repeatable"));
		assertEquals("Repeatable: every 3 hours", LongEventState.rerollRuleText("repeatable2"));

		LongEventState.Slot daily = new LongEventState.Slot("daily", 100L, 1, "WTP", "WTP 1", 0, 3, 10, false, true, 0L, "", "");
		LongEventState.Slot special = new LongEventState.Slot("special", 200L, 2, "NOTG", "NOTG 1", 0, 3, 15, false, true, 0L, "", "");
		LongEventState state = new LongEventState(true, 1000L, 0, 0, 0, true, "", List.of(daily, special), List.of());

		assertTrue(state.repeatSlot2Active());
		assertNotNull(state.findSlot("daily"));
		assertEquals("WTP", state.findSlot("daily").tag());
		assertNotNull(state.findSlot("special"));
		assertEquals("NOTG", state.findSlot("special").tag());
		assertNotNull(state.findSlot("weekly")); // fallback alias
		assertEquals("NOTG", state.findSlot("weekly").tag());
		assertNull(state.findSlot("repeatable"));
	}

	@Test
	void slotPurchasedRerollState() {
		LongEventState.Slot daily = new LongEventState.Slot("daily", 100L, 1, "WTP", "WTP 1", 0, 3, 10, false, false, true, 5000L, "", "");
		LongEventState state = new LongEventState(true, 1000L, 0, 5, 8, "", List.of(daily), List.of());

		assertEquals(5, state.rerollCost());
		assertEquals(8, state.triviaPoints());
		assertFalse(daily.canReroll());
		assertTrue(daily.canBuyReroll());
		assertEquals(5000L, daily.rerollAtMs());
	}
}
