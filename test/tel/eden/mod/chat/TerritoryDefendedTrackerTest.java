package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TerritoryDefendedTrackerTest {
	private TerritoryDefendedTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new TerritoryDefendedTracker();
	}

	@Test
	void detectsDefendedTerritoryInNormalOrder() {
		var first = tracker.onChat(Component.literal(" Your guild has successfully defended Chasm Overlook."));
		assertTrue(first.isEmpty(), "First message alone should not complete event");

		var second = tracker.onChat(Component.literal(" [ANO] has lost the war!"));
		assertTrue(second.isPresent(), "Second message should complete defense event");

		var event = second.get();
		assertEquals("Chasm Overlook", event.territory());
		assertEquals("ANO", event.attacker());
	}

	@Test
	void detectsDefendedTerritoryWithoutBracketsOnTag() {
		tracker.onChat(Component.literal("Your guild has successfully defended Cinfras County."));
		var event = tracker.onChat(Component.literal("ANO has lost the war!"));

		assertTrue(event.isPresent());
		assertEquals("Cinfras County", event.get().territory());
		assertEquals("ANO", event.get().attacker());
	}

	@Test
	void detectsDefendedTerritoryInInvertedOrder() {
		var first = tracker.onChat(Component.literal(" [TIT] has lost the war!"));
		assertTrue(first.isEmpty());

		var second = tracker.onChat(Component.literal("Your guild has successfully defended Thesead Suburbs"));
		assertTrue(second.isPresent());
		assertEquals("Thesead Suburbs", second.get().territory());
		assertEquals("TIT", second.get().attacker());
	}

	@Test
	void ignoresEdenLostTheWar() {
		tracker.onChat(Component.literal("Eden has lost the war!"));
		var result = tracker.onChat(Component.literal("Your guild has successfully defended Detlas"));
		assertTrue(result.isEmpty(), "Should not pair with Eden's own loss");

		tracker.clear();
		tracker.onChat(Component.literal("[EDN] has lost the war!"));
		result = tracker.onChat(Component.literal("Your guild has successfully defended Detlas"));
		assertTrue(result.isEmpty(), "Should not pair with [EDN] tag loss");

		tracker.clear();
		tracker.onChat(Component.literal("[EDEN] has lost the war!"));
		result = tracker.onChat(Component.literal("Your guild has successfully defended Detlas"));
		assertTrue(result.isEmpty(), "Should not pair with [EDEN] tag loss");
	}

	@Test
	void ignoresUnrelatedMessages() {
		assertTrue(tracker.onChat(Component.literal("Just some normal chat")).isEmpty());
		assertTrue(tracker.onChat(Component.literal("You have taken control of Detlas from [TAG]!")).isEmpty());
		assertTrue(tracker.onChat(null).isEmpty());
	}
}
