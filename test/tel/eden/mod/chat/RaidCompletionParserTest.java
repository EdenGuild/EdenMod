package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

class RaidCompletionParserTest {
	private static Component named(String displayed, String realUsername) {
		return Component.literal(displayed).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal(displayed + "'s real username is " + realUsername))));
	}

	@Test
	void parsesASoloRaidWithFullLoot() {
		Component message = Component.literal("Koaci finished The Wartorn Palace and claimed 2048x Emeralds, +974m Guild Experience, and +80 Seasonal Rating");

		RaidCompletion completion = RaidCompletionParser.parse(message).orElseThrow();

		assertEquals(List.of("Koaci"), completion.party());
		assertEquals("The Wartorn Palace", completion.raidName());
		assertEquals(2048, completion.emeralds());
		assertEquals(0, completion.aspects());
		assertEquals("974", completion.guildExp());
	}

	@Test
	void parsesAFourPlayerPartyWithAnOxfordCommaAndAllLootFields() {
		Component message = Component.literal("Player1, Player2, Player3, and Player4 finished Nest of the Grootslangs and claimed 2x Aspects, 2048x Emeralds, +633m Guild Experience, and +440 Seasonal Rating");

		RaidCompletion completion = RaidCompletionParser.parse(message).orElseThrow();

		assertEquals(List.of("Player1", "Player2", "Player3", "Player4"), completion.party());
		assertEquals("Nest of the Grootslangs", completion.raidName());
		assertEquals(2, completion.aspects());
		assertEquals(2048, completion.emeralds());
	}

	@Test
	void parsesATwoPlayerPartyJoinedWithJustAnd() {
		Component message = Component.literal("Player1 and Player2 finished The Canyon Colossus and claimed 1x Aspects, 1024x Emeralds, +200m Guild Experience, and +50 Seasonal Rating");

		RaidCompletion completion = RaidCompletionParser.parse(message).orElseThrow();

		assertEquals(List.of("Player1", "Player2"), completion.party());
		assertEquals("The Canyon Colossus", completion.raidName());
	}

	@Test
	void resolvesEachPartyMembersRealUsernameFromTheirOwnHover() {
		Component p1 = named("catboy", "FadeDave");
		Component p2 = named("nicked2", "Asthae");
		Component message = Component.empty().append(p1).append(", ").append(p2).append(" finished Orphion's Nexus of Light and claimed 2x Aspects, 2048x Emeralds, +633m Guild Experience, and +440 Seasonal Rating");

		RaidCompletion completion = RaidCompletionParser.parse(message).orElseThrow();

		assertEquals(List.of("FadeDave", "Asthae"), completion.party());
		assertEquals("Orphion's Nexus of Light", completion.raidName());
	}

	@Test
	void defaultsAbsentLootFieldsToZeroWhenRenderedAsUnreadableGlyphs() {
		// Loot is frequently rendered in a custom-font glyph run that our capture hook
		// can't read as literal text — the parser must still succeed on party/raid name.
		Component message = Component.literal("Koaci finished The Nameless Anomaly and claimed some unreadable glyph loot");

		RaidCompletion completion = RaidCompletionParser.parse(message).orElseThrow();

		assertEquals("The Nameless Anomaly", completion.raidName());
		assertEquals(0, completion.aspects());
		assertEquals(0, completion.emeralds());
	}

	@Test
	void rejectsAMessageThatDoesNotNameAKnownRaid() {
		assertTrue(RaidCompletionParser.parse(Component.literal("Koaci finished some unrelated task and claimed a prize")).isEmpty());
	}

	@Test
	void rejectsUnrelatedMessagesAndNull() {
		assertTrue(RaidCompletionParser.parse(Component.literal("Just a regular guild chat line")).isEmpty());
		assertTrue(RaidCompletionParser.parse(null).isEmpty());
	}

	@Test
	void rejectsShouts() {
		Component shout = Component.literal("[WC1] Player shouts: Player1 finished The Wartorn Palace and claimed 2048x Emeralds");
		assertTrue(RaidCompletionParser.parse(shout).isEmpty());
	}

	@Test
	void rejectsPartyChat() {
		Component party = Component.literal(" Cartiye/pusheen: Player1 finished The Wartorn Palace and claimed 2048x Emeralds");
		assertTrue(RaidCompletionParser.parse(party).isEmpty());
	}

	@Test
	void rejectsPlayerAuthoredChatSpoofsWithColons() {
		Component guildChat = Component.literal("Troll: Player1 finished The Wartorn Palace and claimed 2048x Emeralds");
		assertTrue(RaidCompletionParser.parse(guildChat).isEmpty());

		Component dmChat = Component.literal("Troll whispers: Player1 finished The Wartorn Palace and claimed 2048x Emeralds");
		assertTrue(RaidCompletionParser.parse(dmChat).isEmpty());
	}

	@Test
	void rejectsPartiesWithMoreThanFourPlayers() {
		Component fivePlayer = Component.literal("P1, P2, P3, P4, and P5 finished The Wartorn Palace and claimed 2048x Emeralds");
		assertTrue(RaidCompletionParser.parse(fivePlayer).isEmpty());
	}

	@Test
	void isRaidCandidateGatesOnTheFinishedKeyword() {
		assertTrue(RaidCompletionParser.isRaidCandidate(Component.literal("X finished Y")));
		assertFalse(RaidCompletionParser.isRaidCandidate(Component.literal("no keyword here")));
		assertFalse(RaidCompletionParser.isRaidCandidate(null));
	}
}
