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
		assertEquals("Super: once every 3 days", LongEventState.rerollRuleText("special"));
		assertEquals("Super: once every 3 days", LongEventState.rerollRuleText("weekly"));
		assertEquals("Super: once every 3 days", LongEventState.rerollRuleText("super"));
		assertEquals("Repeatable: every 3 hours", LongEventState.rerollRuleText("repeatable"));
		assertEquals("Repeatable: every 3 hours", LongEventState.rerollRuleText("repeatable2"));

		LongEventState.Slot daily = new LongEventState.Slot("daily", 100L, 1, "WTP", "WTP 1", 0, 3, 10, false, true, false, 0L, "", "", 1, 3);
		LongEventState.Slot special = new LongEventState.Slot("special", 200L, 2, "NOTG", "NOTG 1", 0, 3, 15, false, true, 0L, "", "");
		LongEventState.SpecialSlot spec1 = new LongEventState.SpecialSlot(1, "scavenger_hunt", "Scavenger", true, false, 0, 0L, 0, 0, 0L, 50, "note", "", List.of(), List.of(), 0, 0, false, 0L, true, false, "");
		LongEventState state = new LongEventState(true, 1000L, 0, 0, 0, true, "", List.of(daily, special), List.of(), List.of(spec1));

		assertTrue(state.repeatSlot2Active());
		assertEquals(1, daily.completions());
		assertEquals(3, daily.maxCompletions());
		assertNotNull(state.findSlot("daily"));
		assertEquals("WTP", state.findSlot("daily").tag());
		assertNotNull(state.findSlot("special"));
		assertEquals("NOTG", state.findSlot("special").tag());
		assertNotNull(state.findSlot("super"));
		assertEquals("NOTG", state.findSlot("super").tag());
		assertNotNull(state.findSlot("weekly")); // fallback alias
		assertEquals("NOTG", state.findSlot("weekly").tag());
		assertNull(state.findSlot("repeatable"));
		assertNotNull(state.findSpecialSlot(1));
		assertEquals("scavenger_hunt", state.findSpecialSlot(1).kind());
		assertNull(state.findSpecialSlot(2));

		// Slot named "super" in snapshot matches lookups for special, super, and weekly
		LongEventState.Slot superSlot = new LongEventState.Slot("super", 200L, 2, "NOTG", "NOTG 1", 0, 3, 15, false, true, 0L, "", "");
		LongEventState stateSuper = new LongEventState(true, 1000L, 0, 0, 0, true, "", List.of(daily, superSlot), List.of(), List.of(spec1));
		assertNotNull(stateSuper.findSlot("special"));
		assertEquals("NOTG", stateSuper.findSlot("special").tag());
		assertNotNull(stateSuper.findSlot("super"));
		assertEquals("NOTG", stateSuper.findSlot("super").tag());
		assertNotNull(stateSuper.findSlot("weekly"));
		assertEquals("NOTG", stateSuper.findSlot("weekly").tag());
	}

	@Test
	void errorFallbackIsNotASnapshot() {
		LongEventState.Slot daily = new LongEventState.Slot("daily", 100L, 1, "WTP", "WTP 1", 0, 3, 10, false, true, 0L, "", "");
		// The bot's fallback after an internal error: inactive, no slots, an error.
		assertFalse(new LongEventState(false, 0L, 0, "Something went wrong - try again.", List.of(), List.of()).isSnapshot());
		// A refused reroll still carries the real slots, and a quiet "no event" reply has no error.
		assertTrue(new LongEventState(true, 1000L, 0, "Daily reroll already used today.", List.of(daily), List.of()).isSnapshot());
		assertTrue(new LongEventState(false, 0L, 0, "", List.of(), List.of()).isSnapshot());
	}

	@Test
	void parsedFallbackKeepsTheLastGoodState() {
		com.google.gson.JsonObject fallback = com.google.gson.JsonParser.parseString("{\"type\":\"longEventState\",\"active\":false,\"endsAtMs\":0,\"pendingLe\":0,\"error\":\"Something went wrong - try again.\",\"slots\":[],\"masters\":[]}").getAsJsonObject();
		assertFalse(BridgeWebSocketClient.parseLongEventState(fallback).isSnapshot());
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

	@Test
	void parseSpecialSlotsAndCompletions() {
		String json = """
					{
						"type": "longEventState",
						"active": true,
						"endsAtMs": 999999L,
						"pendingLe": 10,
						"slots": [
							{
								"slot": "daily",
								"resetAtMs": 100,
								"missionId": 1,
								"tag": "WTP",
								"title": "WTP 1",
								"progress": 2,
								"target": 3,
								"rewardLe": 10,
								"completed": false,
								"canReroll": true,
								"canBuyReroll": false,
								"rerollAtMs": 0,
								"note": "test note",
								"imageUrl": "https://example.com/img.png",
								"completions": 2,
								"maxCompletions": 5
							}
						],
						"masters": [],
						"specialSlots": [
							{
								"slotNum": 1,
								"kind": "scavenger_hunt",
								"title": "Scavenger",
								"opened": true,
								"completed": false,
								"completions": 0,
								"unlockAtMs": 0,
								"raidProgress": 3,
								"raidRequirement": 5,
								"expiresAtMs": 500000,
								"rewardLe": 64,
								"note": "Find items",
								"imageUrl": "",
								"items": [
									{
										"difficulty": "Easy",
										"unlocked": true,
										"unlockAtMs": 0,
										"note": "Item 1",
										"imageUrl": "https://example.com/i1.png"
									}
								],
								"clues": [
									{
										"note": "Clue 1",
										"imageUrl": ""
									}
								],
								"clueRaidProgress": 2,
								"clueRaidTarget": 4,
								"clueUnlockedToday": true,
								"nextGuessAtMs": 12345,
								"canGuessFree": true,
								"canGuessTrivia": false,
								"lastGuessStatus": "Correct!"
							}
						]
					}
					""";
		com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
		LongEventState state = BridgeWebSocketClient.parseLongEventState(obj);

		assertTrue(state.active());
		assertEquals(1, state.slots().size());
		LongEventState.Slot daily = state.findSlot("daily");
		assertNotNull(daily);
		assertEquals(2, daily.completions());
		assertEquals(5, daily.maxCompletions());

		assertEquals(1, state.specialSlots().size());
		LongEventState.SpecialSlot spec = state.findSpecialSlot(1);
		assertNotNull(spec);
		assertEquals("scavenger_hunt", spec.kind());
		assertTrue(spec.opened());
		assertEquals(64, spec.rewardLe());
		assertEquals(1, spec.items().size());
		assertEquals("Easy", spec.items().get(0).difficulty());
		assertEquals(1, spec.clues().size());
		assertEquals("Clue 1", spec.clues().get(0).note());
		assertEquals(2, spec.clueRaidProgress());
		assertEquals(4, spec.clueRaidTarget());
		assertTrue(spec.clueUnlockedToday());
		assertTrue(spec.canGuessFree());
		assertEquals("Correct!", spec.lastGuessStatus());
	}
}
