package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RaidPartyTrackerTest {

	@BeforeEach
	void setUp() {
		RaidPartyTracker.reset();
		PlayerNameResolver.reset();
	}

	@Test
	void ignoresPartyListWhenNotExpecting() {
		Component msg = Component.literal("Party members: Alice, Bob, Charlie, and Dave");
		boolean handled = RaidPartyTracker.handlePartyListChat(msg);
		assertFalse(handled);
		assertTrue(RaidPartyTracker.partyMembersForTesting().isEmpty());
	}

	@Test
	void parsesPartyListWhenExpecting() {
		RaidPartyTracker.setExpectingPartyListForTesting(true);
		Component msg = Component.literal("Party members: Alice, Bob, Charlie, and Dave");
		boolean handled = RaidPartyTracker.handlePartyListChat(msg);

		assertTrue(handled);
		assertFalse(RaidPartyTracker.isExpectingPartyList());
		assertEquals(List.of("Alice", "Bob", "Charlie", "Dave"), List.copyOf(RaidPartyTracker.partyMembersForTesting()));
	}

	@Test
	void parsesPartyListWithLeaderStarAndNicknames() {
		RaidPartyTracker.setExpectingPartyListForTesting(true);

		Component nickComponent = Component.literal("catboy").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("catboy's real username is FadeDave"))));
		Component msg = Component.empty().append("Party members: [★] Alice, ").append(nickComponent).append(", Charlie, and Dave");

		boolean handled = RaidPartyTracker.handlePartyListChat(msg);

		assertTrue(handled);
		assertFalse(RaidPartyTracker.isExpectingPartyList());
		assertEquals(List.of("Alice", "FadeDave", "Charlie", "Dave"), List.copyOf(RaidPartyTracker.partyMembersForTesting()));
	}

	@Test
	void handlesNotInPartyMessage() {
		RaidPartyTracker.setExpectingPartyListForTesting(true);
		Component msg = Component.literal("You are not currently in a party.");
		boolean handled = RaidPartyTracker.handlePartyListChat(msg);

		assertTrue(handled);
		assertFalse(RaidPartyTracker.isExpectingPartyList());
		assertTrue(RaidPartyTracker.partyMembersForTesting().isEmpty());
	}

	@Test
	void leavesUnrelatedChatAloneWhenExpecting() {
		RaidPartyTracker.setExpectingPartyListForTesting(true);
		Component msg = Component.literal("[Guild] Welcome back everyone!");
		boolean handled = RaidPartyTracker.handlePartyListChat(msg);

		assertFalse(handled);
		assertTrue(RaidPartyTracker.isExpectingPartyList());
		assertTrue(RaidPartyTracker.partyMembersForTesting().isEmpty());
	}

	@Test
	void doesNotSwallowChatMentioningNotAndParty() {
		RaidPartyTracker.setExpectingPartyListForTesting(true);
		Component msg = Component.literal("Lumeniia: party is not ready yet, wait");
		boolean handled = RaidPartyTracker.handlePartyListChat(msg);

		assertFalse(handled);
		assertTrue(RaidPartyTracker.isExpectingPartyList());
	}

	@Test
	void handlesMustBeInPartyMessage() {
		RaidPartyTracker.setExpectingPartyListForTesting(true);
		Component msg = Component.literal("You must be in a party to use this.");

		assertTrue(RaidPartyTracker.handlePartyListChat(msg));
		assertFalse(RaidPartyTracker.isExpectingPartyList());
	}

	@Test
	void extraPlayersDeductsAnnouncedGuildMembersFromPartyRoster() {
		RaidPartyTracker.setRaidingForTesting(true);
		RaidPartyTracker.addPartyMemberForTesting("Alice");
		RaidPartyTracker.addPartyMemberForTesting("Bob");
		RaidPartyTracker.addPartyMemberForTesting("Charlie");
		RaidPartyTracker.addPartyMemberForTesting("Dave");

		// 2 guild members announced, 2 allies missing
		List<String> allies = RaidPartyTracker.extraPlayers(List.of("Alice", "Bob"));
		assertEquals(List.of("Charlie", "Dave"), allies);

		// Case-insensitive matching
		List<String> alliesCase = RaidPartyTracker.extraPlayers(List.of("alice", "BOB"));
		assertEquals(List.of("Charlie", "Dave"), alliesCase);

		// Full party announced -> 0 extras
		List<String> none = RaidPartyTracker.extraPlayers(List.of("Alice", "Bob", "Charlie", "Dave"));
		assertTrue(none.isEmpty());
	}

	@Test
	void extraPlayersFallsBackToProximitySightingsWhenPartyEmpty() {
		RaidPartyTracker.setRaidingForTesting(true);
		// Need >= 10 sightings
		RaidPartyTracker.recordSightingForTesting("NearbyAlly1", 15);
		RaidPartyTracker.recordSightingForTesting("NearbyAlly2", 20);
		RaidPartyTracker.recordSightingForTesting("RandomNPC", 5); // Below threshold

		List<String> extras = RaidPartyTracker.extraPlayers(List.of("GuildPlayer1", "GuildPlayer2"));
		assertEquals(List.of("NearbyAlly2", "NearbyAlly1"), extras);
	}

	@Test
	void extraPlayersSupplementsIncompletePartyWithSightings() {
		RaidPartyTracker.setRaidingForTesting(true);
		// Party list only had 3 members
		RaidPartyTracker.addPartyMemberForTesting("GuildMember1");
		RaidPartyTracker.addPartyMemberForTesting("Ally1");

		// Proximity sightings has another ally
		RaidPartyTracker.recordSightingForTesting("Ally2", 25);

		// Announced has GuildMember1 (party of 4 is missing 3)
		List<String> extras = RaidPartyTracker.extraPlayers(List.of("GuildMember1"));
		assertEquals(List.of("Ally1", "Ally2"), extras);
	}

	@Test
	void resetClearsAllTrackingState() {
		RaidPartyTracker.setRaidingForTesting(true);
		RaidPartyTracker.setExpectingPartyListForTesting(true);
		RaidPartyTracker.addPartyMemberForTesting("Alice");
		RaidPartyTracker.recordSightingForTesting("Bob", 20);
		RaidPartyTracker.setLivePartyForTesting(List.of("Alice", "Carol"));

		RaidPartyTracker.reset();

		assertFalse(RaidPartyTracker.isExpectingPartyList());
		assertTrue(RaidPartyTracker.partyMembersForTesting().isEmpty());
		assertTrue(RaidPartyTracker.extraPlayers(List.of("Alice")).isEmpty());
	}

	@Test
	void tallyOutlivesTheRaidSidebarUntilTheCompletion() {
		// The sidebar vanishes in the reward room, a minute or more before the completion line.
		RaidPartyTracker.setRaidingForTesting(true);
		RaidPartyTracker.addPartyMemberForTesting("Alice");
		RaidPartyTracker.addPartyMemberForTesting("Ally");
		RaidPartyTracker.endRaidForTesting();

		assertEquals(List.of("Ally"), RaidPartyTracker.extraPlayers(List.of("Alice", "Bob", "Carol")));
	}

	@Test
	void fallsBackToLiveWynntilsPartyWhenTallyIsEmpty() {
		// No raid seen (e.g. relogged mid-raid), but Wynntils still knows the party.
		RaidPartyTracker.setLivePartyForTesting(List.of("Alice", "Bob", "Carol", "Ally"));

		assertEquals(List.of("Ally"), RaidPartyTracker.extraPlayers(List.of("alice", "Bob", "Carol")));
	}

	@Test
	void livePartyFillsAPartialTallyAheadOfSightings() {
		// The tally only caught one of the two allies; Wynntils knows the whole party.
		RaidPartyTracker.recordSightingForTesting("HerrPopcorn", 30);
		RaidPartyTracker.recordSightingForTesting("StandingNpc", 40);
		RaidPartyTracker.setLivePartyForTesting(List.of("night03", "Fisik", "HerrPopcorn", "OtherAlly"));

		assertEquals(List.of("HerrPopcorn", "OtherAlly"), RaidPartyTracker.extraPlayers(List.of("night03", "Fisik")));
	}
}
