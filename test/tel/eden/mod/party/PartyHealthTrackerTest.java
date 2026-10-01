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
	void parsesLegacyColorCodesInScoreboardLine() {
		Component colored = Component.literal("- §c[§4||18630||] §fmeep §7[120]");
		PartyHealthTracker.ParsedPartyLine parsed = PartyHealthTracker.parsePartyLine(colored, true);
		assertNotNull(parsed);
		assertEquals(18630, parsed.hp());
		assertEquals("meep", parsed.nickname());
		assertEquals(120, parsed.level());
		assertTrue(parsed.online());
		assertTrue(parsed.fullHealthBar());
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
		PartyHealthTracker.ParsedPartyLine leaderRow = new PartyHealthTracker.ParsedPartyLine(18630, "LeaderNick", 120, true, false, 1.0f);
		PartyHealthTracker.ParsedPartyLine localRow = new PartyHealthTracker.ParsedPartyLine(1991, "LocalNick", 121, true, false, 0.15f);
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

	@Test
	void calculatesLowHealthWithoutLockingMaxHp() {
		PartyHealthTracker.MaxHpState state = new PartyHealthTracker.MaxHpState();
		PartyHealthTracker.HealthCalculation calc = PartyHealthTracker.calculateHealth(state, 1056, 120, false, 0.0f, 1000L);

		// Level 120 baseline is 50 + 120 * 115 = 13850
		assertEquals(13850, calc.maxHp());
		assertEquals(1056f / 13850f, calc.percent(), 0.001f);
		assertTrue(calc.percent() < 0.10f);
		assertFalse(calc.overMax());
		assertEquals(0, state.stableMaxHp);
	}

	@Test
	void detectsHealthDeficitFromStyledScoreboardRow() {
		net.minecraft.network.chat.MutableComponent comp = Component.empty().append(Component.literal("- [").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)).append(Component.literal("||").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)).append(Component.literal("1056").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)).append(Component.literal("||").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)).append(Component.literal("] SamplePlayer [120]").withStyle(net.minecraft.ChatFormatting.WHITE));

		PartyHealthTracker.ParsedPartyLine parsed = PartyHealthTracker.parsePartyLine(comp, true);
		assertNotNull(parsed);
		assertEquals(1056, parsed.hp());
		assertEquals("SamplePlayer", parsed.nickname());
		assertEquals(120, parsed.level());
		assertFalse(parsed.fullHealthBar());
		assertEquals(0.0f, parsed.visualFraction(), 0.001f);
	}

	@Test
	void calculatesOverPercentProperly() {
		// Normal HP (no overhealth)
		assertEquals(0.0f, PartyHealthTracker.PlayerHealthData.calculateOverPercent(8000, 8000, false), 0.001f);
		assertEquals(0.0f, PartyHealthTracker.PlayerHealthData.calculateOverPercent(4000, 8000, false), 0.001f);

		// 50% overhealth (12000 / 8000)
		assertEquals(0.50f, PartyHealthTracker.PlayerHealthData.calculateOverPercent(12000, 8000, true), 0.001f);

		// 100% overhealth (16000 / 8000)
		assertEquals(1.00f, PartyHealthTracker.PlayerHealthData.calculateOverPercent(16000, 8000, true), 0.001f);

		// Greater than 2x max HP (18000 / 8000 capped at 100%)
		assertEquals(1.00f, PartyHealthTracker.PlayerHealthData.calculateOverPercent(18000, 8000, true), 0.001f);
	}

	@Test
	void detectsTruncatedLocalPlayerRow() {
		PartyHealthTracker.ParsedPartyLine truncatedRow = new PartyHealthTracker.ParsedPartyLine(9222, "LocalPlayerN", 121, true, true, 1.0f);
		assertTrue(PartyHealthTracker.isLocalRow(truncatedRow, "LocalPlayerName"));
		assertFalse(PartyHealthTracker.isLocalRow(truncatedRow, "OtherPlayer"));
	}

	@Test
	void matchesCandidateRosterWithDuplicatePartyLeaderAndTruncatedNicknames() {
		// Scoreboard rows:
		// Row 0: LeaderNick
		// Row 1: SecondNick
		// Row 2: LocalNick
		// Row 3: get it t
		PartyHealthTracker.ParsedPartyLine r0 = new PartyHealthTracker.ParsedPartyLine(20921, "LeaderNick", 121, true, true, 1.0f);
		PartyHealthTracker.ParsedPartyLine r1 = new PartyHealthTracker.ParsedPartyLine(8127, "SecondNick", 120, true, true, 1.0f);
		PartyHealthTracker.ParsedPartyLine r2 = new PartyHealthTracker.ParsedPartyLine(10183, "LocalNick", 121, true, true, 1.0f);
		PartyHealthTracker.ParsedPartyLine r3 = new PartyHealthTracker.ParsedPartyLine(18454, "get it t", 120, true, true, 1.0f);
		java.util.List<PartyHealthTracker.ParsedPartyLine> rows = java.util.List.of(r0, r1, r2, r3);

		tel.eden.mod.chat.PlayerNameResolver.recordAlias("LeaderNick", "LeaderUser");
		tel.eden.mod.chat.PlayerNameResolver.recordAlias("SecondNick", "MemberTwo");
		tel.eden.mod.chat.PlayerNameResolver.recordAlias("LocalNick", "LocalUser");
		tel.eden.mod.chat.PlayerNameResolver.recordAlias("get it twisted", "MemberFour");

		// Wynntils returns 5 elements with duplicate leader at end
		java.util.List<String> wynnMembers = java.util.List.of("LeaderUser", "MemberTwo", "LocalUser", "MemberFour", "LeaderUser");
		java.util.List<String> matched = PartyHealthTracker.matchCandidateRoster(rows, wynnMembers, "LocalUser");

		assertEquals(4, matched.size());
		assertEquals("LeaderUser", matched.get(0));
		assertEquals("MemberTwo", matched.get(1));
		assertEquals("LocalUser", matched.get(2));
		assertEquals("MemberFour", matched.get(3));
	}

	@Test
	void detectsSharedNicknamesAcrossRows() {
		PartyHealthTracker.ParsedPartyLine r0 = new PartyHealthTracker.ParsedPartyLine(31479, "UniquePlayer", 120, true, true, 1.0f);
		PartyHealthTracker.ParsedPartyLine r1 = new PartyHealthTracker.ParsedPartyLine(8793, "get it t", 120, true, true, 1.0f);
		PartyHealthTracker.ParsedPartyLine r2 = new PartyHealthTracker.ParsedPartyLine(59600, "get it t", 120, true, true, 1.0f);
		PartyHealthTracker.ParsedPartyLine r3 = new PartyHealthTracker.ParsedPartyLine(12450, "get it t", 120, true, true, 1.0f);
		java.util.List<PartyHealthTracker.ParsedPartyLine> rows = java.util.List.of(r0, r1, r2, r3);

		assertFalse(PartyHealthTracker.isSharedNickname(rows, "UniquePlayer"));
		assertTrue(PartyHealthTracker.isSharedNickname(rows, "get it t"));
		assertTrue(PartyHealthTracker.isSharedNickname(rows, "get it"));
	}

	@Test
	void matchesCandidateRosterWithTripleSharedNicknameAndTrumpetOverhealth() {
		// Scoreboard rows:
		// Row 0: LocalUser (31479 HP)
		// Row 1: get it t (MemberTwo, 8793 HP)
		// Row 2: get it t (TrumpetUser, 59600 HP overhealth)
		// Row 3: get it t (MemberFour, 12450 HP)
		PartyHealthTracker.ParsedPartyLine r0 = new PartyHealthTracker.ParsedPartyLine(31479, "LocalUser", 120, true, true, 1.0f);
		PartyHealthTracker.ParsedPartyLine r1 = new PartyHealthTracker.ParsedPartyLine(8793, "get it t", 120, true, true, 1.0f);
		PartyHealthTracker.ParsedPartyLine r2 = new PartyHealthTracker.ParsedPartyLine(59600, "get it t", 120, true, true, 1.0f);
		PartyHealthTracker.ParsedPartyLine r3 = new PartyHealthTracker.ParsedPartyLine(12450, "get it t", 120, true, true, 1.0f);
		java.util.List<PartyHealthTracker.ParsedPartyLine> rows = java.util.List.of(r0, r1, r2, r3);

		java.util.List<String> wynnMembers = java.util.List.of("LocalUser", "MemberTwo", "TrumpetUser", "MemberFour");
		java.util.List<String> matched = PartyHealthTracker.matchCandidateRoster(rows, wynnMembers, "LocalUser");

		assertEquals(4, matched.size());
		assertEquals("LocalUser", matched.get(0));
		assertEquals("MemberTwo", matched.get(1));
		assertEquals("TrumpetUser", matched.get(2));
		assertEquals("MemberFour", matched.get(3));
	}

	@Test
	void resolvesDistinctPartySlotsForSharedNicknamesUsingRealAccountNames() {
		java.util.List<String> partyMembers = java.util.List.of("LeaderUser", "MemberTwo", "MemberThree", "MemberFour");

		// Suppose an alias exists that maps "get it t" to "LeaderUser"
		tel.eden.mod.chat.PlayerNameResolver.recordAlias("get it t", "LeaderUser");

		// When real account names are known:
		int slotLeader = PartyHealthTracker.resolvePartySlot("LeaderUser", "get it t", partyMembers, 0, true);
		int slotTwo = PartyHealthTracker.resolvePartySlot("MemberTwo", "get it t", partyMembers, 1, true);
		int slotThree = PartyHealthTracker.resolvePartySlot("MemberThree", "get it t", partyMembers, 2, true);
		int slotFour = PartyHealthTracker.resolvePartySlot("MemberFour", "get it t", partyMembers, 3, true);

		assertEquals(0, slotLeader);
		assertEquals(1, slotTwo);
		assertEquals(2, slotThree);
		assertEquals(3, slotFour);
	}

	@Test
	void neverCollapsesSharedNicknameToSlotZeroWhenRealNameUnknown() {
		java.util.List<String> partyMembers = java.util.List.of("LeaderUser", "MemberTwo", "MemberThree", "MemberFour");

		// Alias poisoned to leader
		tel.eden.mod.chat.PlayerNameResolver.recordAlias("20", "LeaderUser");

		// Non-leader members with shared nickname and null real name must fall back to their row index, not leader's slot 0
		int slot1 = PartyHealthTracker.resolvePartySlot(null, "20", partyMembers, 1, true);
		int slot2 = PartyHealthTracker.resolvePartySlot(null, "20", partyMembers, 2, true);
		int slot3 = PartyHealthTracker.resolvePartySlot(null, "20", partyMembers, 3, true);

		assertEquals(1, slot1);
		assertEquals(2, slot2);
		assertEquals(3, slot3);
	}

	@Test
	void resolvesUniqueNicknameUsingAliasWhenUnique() {
		java.util.List<String> partyMembers = java.util.List.of("LeaderUser", "MemberTwo", "MemberThree");
		tel.eden.mod.chat.PlayerNameResolver.recordAlias("CoolNick", "MemberTwo");

		// For unique nickname, alias lookup is allowed when real name is unknown
		int slot = PartyHealthTracker.resolvePartySlot(null, "CoolNick", partyMembers, 99, false);
		assertEquals(1, slot);
	}

	@Test
	void adaptsMaxHpUpwardAfterTwoSecondsOfFullBar() {
		PartyHealthTracker.MaxHpState state = new PartyHealthTracker.MaxHpState();
		// Initial full health at 10000
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 1000L);
		assertEquals(10000, state.stableMaxHp);

		// Swap to higher HP build: 15000 HP, full health
		PartyHealthTracker.calculateHealth(state, 15000, 120, true, 1.0f, 2000L);
		assertEquals(10000, state.stableMaxHp); // not yet 2 seconds

		PartyHealthTracker.calculateHealth(state, 15000, 120, true, 1.0f, 3500L);
		assertEquals(10000, state.stableMaxHp); // 1.5 seconds, still not 2s

		PartyHealthTracker.calculateHealth(state, 15000, 120, true, 1.0f, 4100L);
		assertEquals(15000, state.stableMaxHp); // >2 seconds, adapted to 15000!
	}

	@Test
	void adaptsMaxHpDownwardOnlyAfterTwentySecondsOfFullBar() {
		PartyHealthTracker.MaxHpState state = new PartyHealthTracker.MaxHpState();
		// Initial full health at 15000
		PartyHealthTracker.calculateHealth(state, 15000, 120, true, 1.0f, 1000L);
		assertEquals(15000, state.stableMaxHp);

		// Swap to lower HP build: 10000 HP, full health
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 2000L);
		assertEquals(15000, state.stableMaxHp);

		// At 5 seconds (t = 7000): should still be 15000
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 7000L);
		assertEquals(15000, state.stableMaxHp);

		// At 19 seconds (t = 21000): should still be 15000
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 21000L);
		assertEquals(15000, state.stableMaxHp);

		// At 20.5 seconds (t = 22500): exactly adapted to 10000!
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 22500L);
		assertEquals(10000, state.stableMaxHp);
	}

	@Test
	void resetsDownwardTimerIfDamageTakenBeforeTwentySeconds() {
		PartyHealthTracker.MaxHpState state = new PartyHealthTracker.MaxHpState();
		PartyHealthTracker.calculateHealth(state, 15000, 120, true, 1.0f, 1000L);
		assertEquals(15000, state.stableMaxHp);

		// Lower HP at t = 2000
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 2000L);
		assertEquals(15000, state.stableMaxHp);

		// Sustained for 15 seconds until t = 17000
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 17000L);
		assertEquals(15000, state.stableMaxHp);

		// Takes damage at t = 18000: bar not full!
		PartyHealthTracker.calculateHealth(state, 8000, 120, false, 0.8f, 18000L);
		assertEquals(15000, state.stableMaxHp);

		// Heals back to full at t = 19000: timer resets
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 19000L);
		assertEquals(15000, state.stableMaxHp);

		// At t = 25000 (only 6s since re-full): still 15000
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 25000L);
		assertEquals(15000, state.stableMaxHp);

		// At t = 39500 (20.5s since re-full): now adapts downward
		PartyHealthTracker.calculateHealth(state, 10000, 120, true, 1.0f, 39500L);
		assertEquals(10000, state.stableMaxHp);
	}

	@Test
	void fallsBackDeterministicallyWhenPartyMembersListEmpty() {
		int slot = PartyHealthTracker.resolvePartySlot("RealName", "Nick", java.util.Collections.emptyList(), 2, false);
		assertEquals(2, slot);

		int slotShared = PartyHealthTracker.resolvePartySlot("RealName", "20", java.util.Collections.emptyList(), 3, true);
		assertEquals(3, slotShared);
	}
}
