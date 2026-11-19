package tel.eden.mod.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import tel.eden.mod.chat.DiscordChatFormatter;

class CycleLeaderboardTest {
	@Test
	void standardCompetitionRankingAndZeros() {
		List<CycleLeaderboardData.LeaderboardEntry> ranked = CycleLeaderboardData.rankEntries(List.of(new CycleLeaderboardData.LeaderboardEntry("u2", "Bob", 10, 0, 1), new CycleLeaderboardData.LeaderboardEntry("u3", "Carol", 0, 0, null), new CycleLeaderboardData.LeaderboardEntry("u1", "Alice", 10, 0, 0), new CycleLeaderboardData.LeaderboardEntry("u4", "Dave", 5, 0, -2)));
		assertEquals(4, ranked.size());
		assertEquals("Alice", ranked.get(0).name());
		assertEquals(1, ranked.get(0).rank());
		assertEquals("Bob", ranked.get(1).name());
		assertEquals(1, ranked.get(1).rank());
		assertEquals("Dave", ranked.get(2).name());
		assertEquals(3, ranked.get(2).rank());
		assertEquals("Carol", ranked.get(3).name());
		assertEquals(4, ranked.get(3).rank());
		assertEquals(0, ranked.get(3).count());

		assertEquals(1, CycleLeaderboardData.medalForEntry(ranked.get(0), true));
		assertEquals(1, CycleLeaderboardData.medalForEntry(ranked.get(1), true));
		assertEquals(3, CycleLeaderboardData.medalForEntry(ranked.get(2), true));
		assertEquals(0, CycleLeaderboardData.medalForEntry(ranked.get(3), true));
		assertEquals(0, CycleLeaderboardData.medalForEntry(ranked.get(0), false));
	}

	@Test
	void intervalValidation() {
		CycleLeaderboardData.IntervalDays openEnded = CycleLeaderboardData.parseIntervalDays("14.5", "");
		assertNotNull(openEnded);
		assertEquals(14.5, openEnded.fromDays(), 1e-9);
		assertEquals(0.0, openEnded.toDays(), 1e-9);

		CycleLeaderboardData.IntervalDays bounded = CycleLeaderboardData.parseIntervalDays("30", "7.25");
		assertNotNull(bounded);
		assertEquals(30.0, bounded.fromDays(), 1e-9);
		assertEquals(7.25, bounded.toDays(), 1e-9);

		assertNull(CycleLeaderboardData.parseIntervalDays("", ""));
		assertNull(CycleLeaderboardData.parseIntervalDays("-5", ""));
		assertNull(CycleLeaderboardData.parseIntervalDays("0", ""));
		assertNull(CycleLeaderboardData.parseIntervalDays("5", "-1"));
		assertNull(CycleLeaderboardData.parseIntervalDays("5", "5"));
		assertNull(CycleLeaderboardData.parseIntervalDays("3", "7"));
		assertNull(CycleLeaderboardData.parseIntervalDays("abc", ""));
	}

	@Test
	void cycleConcludedAndPrintFormattingCapRaffleToThree() {
		Component announcement = DiscordChatFormatter.cycleConcluded("2604A");
		String text = announcement.getString();
		assertTrue(text.contains("The promotion cycle has concluded!"));
		assertTrue(text.contains("[Print]"));
		assertTrue(text.contains("[View]"));

		CycleLeaderboardData.ConcludedCycleSummary summary = new CycleLeaderboardData.ConcludedCycleSummary("2604A", List.of(new CycleLeaderboardData.LeaderboardEntry("u1", "Alice", 40, 1, null), new CycleLeaderboardData.LeaderboardEntry("u2", "Bob", 35, 2, null), new CycleLeaderboardData.LeaderboardEntry("u3", "Carol", 30, 3, null), new CycleLeaderboardData.LeaderboardEntry("u4", "Dave", 20, 4, null)), List.of(new CycleLeaderboardData.LeaderboardEntry("u2", "Bob", 55, 1, null)), List.of(new CycleLeaderboardData.RaffleWinner("Alice", 8, 40), new CycleLeaderboardData.RaffleWinner("Bob", 7, 35), new CycleLeaderboardData.RaffleWinner("Carol", 6, 30), new CycleLeaderboardData.RaffleWinner("Dave", 4, 20)), 125, 55, 25);
		String printed = DiscordChatFormatter.formatCyclePrint(summary).getString();
		assertTrue(printed.contains("Top 3 Raids:"));
		assertTrue(printed.contains("Alice"));
		assertTrue(printed.contains("Carol"));
		assertTrue(printed.contains("Top 1 Wars:"));
		assertTrue(printed.contains("Top 3 Raffle:"));
		assertFalse(printed.contains("Dave"));
		assertTrue(printed.contains("125 raids | 55 wars | 25 tickets"));
	}

	@Test
	void outboundBlocksObserversAndTravelersFromCycleRequests() {
		for (String type : List.of("cycleLeaderboardRequest", "lifetimeLeaderboardRequest")) {
			assertFalse(OutboundPolicy.allowed(OutboundPolicy.Standing.UNKNOWN, type));
			assertFalse(OutboundPolicy.allowed(OutboundPolicy.Standing.NOT_LINKED, type));
			assertFalse(OutboundPolicy.allowed(OutboundPolicy.Standing.NOT_MEMBER, type));
			assertFalse(OutboundPolicy.allowed(OutboundPolicy.Standing.TRAVELER, type));
			assertTrue(OutboundPolicy.allowed(OutboundPolicy.Standing.FULL, type));
		}
		for (String reply : List.of("cycleLeaderboardReply", "lifetimeLeaderboardReply", "cycleConcluded")) {
			assertFalse(OutboundPolicy.inboundAllowed(OutboundPolicy.Standing.TRAVELER, reply));
			assertTrue(OutboundPolicy.inboundAllowed(OutboundPolicy.Standing.FULL, reply));
		}
	}

	@Test
	void jsonParsersPreserveErrorStrings() {
		var cycleErr = BridgeWebSocketClient.parseCycleSnapshot(JsonParser.parseString("{\"type\":\"cycleLeaderboardReply\",\"error\":\"Failed to fetch cycle leaderboard\"}").getAsJsonObject());
		assertEquals("Failed to fetch cycle leaderboard", cycleErr.error());

		var lifeErr = BridgeWebSocketClient.parseLifetimeSnapshot(JsonParser.parseString("{\"type\":\"lifetimeLeaderboardReply\",\"error\":\"Failed to fetch leaderboard stats\"}").getAsJsonObject());
		assertEquals("Failed to fetch leaderboard stats", lifeErr.error());
	}

	@Test
	void requestMatchingAndHistoricalCycleDetection() {
		CycleLeaderboardData.CycleSnapshot archive = new CycleLeaderboardData.CycleSnapshot("2603B", List.of("2603A", "2603B", "2604A"), List.of(), List.of(), List.of(), 10, 5, 1, "");
		CycleLeaderboardData.CycleSnapshot current = new CycleLeaderboardData.CycleSnapshot("2604A", List.of("2603A", "2603B", "2604A"), List.of(), List.of(), List.of(), 20, 8, 2, "");

		assertTrue(CycleLeaderboardData.matchesRequestedCycle(archive, "2603B"));
		assertFalse(CycleLeaderboardData.matchesRequestedCycle(archive, ""));
		assertFalse(CycleLeaderboardData.matchesRequestedCycle(archive, "2604A"));
		assertTrue(CycleLeaderboardData.matchesRequestedCycle(current, ""));
		assertTrue(CycleLeaderboardData.matchesRequestedCycle(current, "2604A"));

		assertTrue(CycleLeaderboardData.isHistoricalCycle(archive, "2603B"));
		assertFalse(CycleLeaderboardData.isHistoricalCycle(archive, ""));
		assertFalse(CycleLeaderboardData.isHistoricalCycle(current, "2604A"));
		assertFalse(CycleLeaderboardData.isHistoricalCycle(CycleLeaderboardData.CycleSnapshot.error("err"), "2603B"));

		CycleLeaderboardData.LifetimeSnapshot interval = new CycleLeaderboardData.LifetimeSnapshot(true, 14.0, 0.0, List.of(), List.of(), List.of(), List.of(), "");
		CycleLeaderboardData.LifetimeSnapshot allTime = new CycleLeaderboardData.LifetimeSnapshot(false, null, null, List.of(), List.of(), List.of(), List.of(), "");
		assertTrue(CycleLeaderboardData.matchesRequestedInterval(interval, 14.0, 0.0));
		assertTrue(CycleLeaderboardData.matchesRequestedInterval(interval, 14.0, null));
		assertFalse(CycleLeaderboardData.matchesRequestedInterval(interval, null, null));
		assertTrue(CycleLeaderboardData.matchesRequestedInterval(allTime, null, null));
		assertFalse(CycleLeaderboardData.matchesRequestedInterval(allTime, 14.0, 0.0));
	}

	@Test
	void countsPartyFinderRaidsFor2503AAndBelow() {
		assertTrue(CycleLeaderboardData.countsPartyFinderRaids("2503A"));
		assertTrue(CycleLeaderboardData.countsPartyFinderRaids("2501B"));
		assertTrue(CycleLeaderboardData.countsPartyFinderRaids("2412B"));
		assertFalse(CycleLeaderboardData.countsPartyFinderRaids("2503B"));
		assertFalse(CycleLeaderboardData.countsPartyFinderRaids("2604A"));
		assertFalse(CycleLeaderboardData.countsPartyFinderRaids(""));
		assertFalse(CycleLeaderboardData.countsPartyFinderRaids(null));
	}
}
