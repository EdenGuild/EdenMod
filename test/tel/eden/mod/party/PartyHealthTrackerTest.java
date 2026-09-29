package tel.eden.mod.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PartyHealthTrackerTest {
	@BeforeEach
	void setUp() {
		PartyHealthTracker.reset();
	}

	@Test
	void rejectsLinesOutsidePartySection() {
		Component comp = Component.literal("12450 PlayerOne [106]");
		assertNull(PartyHealthTracker.parsePartyLine(comp, false));
	}

	@Test
	void parsesOnlineMemberLine() {
		Component comp = Component.literal("12450 PlayerOne [106]");
		PartyHealthTracker.ParsedPartyLine parsed = PartyHealthTracker.parsePartyLine(comp, true);

		assertNotNull(parsed);
		assertEquals(12450, parsed.hp());
		assertEquals("PlayerOne", parsed.nickname());
		assertEquals(106, parsed.level());
		assertTrue(parsed.online());
	}

	@Test
	void parsesOfflineMemberLine() {
		Component comp = Component.literal("- PlayerTwo");
		PartyHealthTracker.ParsedPartyLine parsed = PartyHealthTracker.parsePartyLine(comp, true);

		assertNotNull(parsed);
		assertEquals(0, parsed.hp());
		assertEquals("PlayerTwo", parsed.nickname());
		assertFalse(parsed.online());
	}

	@Test
	void parsesMemberWithSpacesAndFormatting() {
		Component comp = Component.literal("  9800  PlayerThree  [105]  ");
		PartyHealthTracker.ParsedPartyLine parsed = PartyHealthTracker.parsePartyLine(comp, true);

		assertNotNull(parsed);
		assertEquals(9800, parsed.hp());
		assertEquals("PlayerThree", parsed.nickname());
		assertEquals(105, parsed.level());
		assertTrue(parsed.online());
	}

	@Test
	void healthPercentClampsBetweenZeroAndOne() {
		UUID uuid = UUID.randomUUID();
		PartyHealthTracker.PlayerHealthData health = new PartyHealthTracker.PlayerHealthData(uuid, "Player", 5000, 10000, 0.5f, false, 0);

		assertEquals(0.5f, health.percent(), 0.001f);
		assertFalse(health.overMax());
	}

	@Test
	void parsesWynncraftScoreboardLinesFromLiveDump() {
		Component memberA = Component.literal("- [||17189||] PlayerA [120]");
		PartyHealthTracker.ParsedPartyLine parsedA = PartyHealthTracker.parsePartyLine(memberA, true);
		assertNotNull(parsedA);
		assertEquals(17189, parsedA.hp());
		assertEquals("PlayerA", parsedA.nickname());
		assertEquals(120, parsedA.level());
		assertTrue(parsedA.online());

		Component memberB = Component.literal("- [||8725||] PlayerB [120]");
		PartyHealthTracker.ParsedPartyLine parsedB = PartyHealthTracker.parsePartyLine(memberB, true);
		assertNotNull(parsedB);
		assertEquals(8725, parsedB.hp());
		assertEquals("PlayerB", parsedB.nickname());
		assertEquals(120, parsedB.level());
		assertTrue(parsedB.online());
	}

	@Test
	void rejectsNonPartyScoreboardLines() {
		// Lines from dump that are not party members
		assertNull(PartyHealthTracker.parsePartyLine(Component.literal("ÀÀ"), true));
		assertNull(PartyHealthTracker.parsePartyLine(Component.literal("Objectives:"), true));
		assertNull(PartyHealthTracker.parsePartyLine(Component.literal("- Finish Quests: 0/2"), true));
		assertNull(PartyHealthTracker.parsePartyLine(Component.literal("- Find Discoveries: 1/5"), true));
		assertNull(PartyHealthTracker.parsePartyLine(Component.literal("- Win Dungeons: 0/1"), true));
		assertNull(PartyHealthTracker.parsePartyLine(Component.literal("Guild Obj: 22/30"), true));
		assertNull(PartyHealthTracker.parsePartyLine(Component.literal("- Gather Fish: 0/200"), true));
	}

	@Test
	void learnsAliasesFromSlashChatFormat() {
		tel.eden.mod.chat.PlayerNameResolver.learnFromText("PlayerOne/NickOne is ready!");
		tel.eden.mod.chat.PlayerNameResolver.learnFromText("You need to wait 1 minute before joining the queue because OtherPlayer/PlayerA denied too many matches recently");
		tel.eden.mod.chat.PlayerNameResolver.learnFromText("LeaderPlayer/LeaderNick is visiting this island. Say hi!");

		assertEquals("PlayerOne", tel.eden.mod.chat.PlayerNameResolver.resolveKnown("NickOne").orElse(null));
		assertEquals("OtherPlayer", tel.eden.mod.chat.PlayerNameResolver.resolveKnown("PlayerA").orElse(null));
		assertEquals("LeaderPlayer", tel.eden.mod.chat.PlayerNameResolver.resolveKnown("LeaderNick").orElse(null));
	}

	@Test
	void ignoresUrlsAndDomainPathsInLearnFromText() {
		tel.eden.mod.chat.PlayerNameResolver.learnFromText("Check out https://wynncraft.com/stats for info!");
		tel.eden.mod.chat.PlayerNameResolver.learnFromText("Visit github.com/repo or discord.gg/eden");

		assertFalse(tel.eden.mod.chat.PlayerNameResolver.resolveKnown("stats").isPresent());
		assertFalse(tel.eden.mod.chat.PlayerNameResolver.resolveKnown("repo").isPresent());
		assertFalse(tel.eden.mod.chat.PlayerNameResolver.resolveKnown("eden").isPresent());
	}

	@Test
	void matchesCandidateRosterWithLocalPlayerLast() {
		// Scoreboard rows:
		// Row 0: LeaderNick (LeaderPlayer)
		// Row 1: LocalNick (LocalPlayer, local)
		PartyHealthTracker.ParsedPartyLine leaderRow = new PartyHealthTracker.ParsedPartyLine(18630, "LeaderNick", 120, true, false);
		PartyHealthTracker.ParsedPartyLine localRow = new PartyHealthTracker.ParsedPartyLine(1991, "LocalNick", 121, true, false);
		java.util.List<PartyHealthTracker.ParsedPartyLine> rows = java.util.List.of(leaderRow, localRow);

		// Record local player alias
		tel.eden.mod.chat.PlayerNameResolver.recordAlias("LocalNick", "LocalPlayer");

		// Wynntils returns only the other party members: ["LeaderPlayer"]
		java.util.List<String> wynnMembers = java.util.List.of("LeaderPlayer");
		java.util.List<String> matched = PartyHealthTracker.matchCandidateRoster(rows, wynnMembers, "LocalPlayer");

		assertEquals(2, matched.size());
		assertEquals("LeaderPlayer", matched.get(0));
		assertEquals("LocalPlayer", matched.get(1));
	}
}
