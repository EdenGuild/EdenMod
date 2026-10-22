package tel.eden.mod.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tel.eden.mod.net.WarBoardEntry;
import tel.eden.mod.net.WarGoer;
import tel.eden.mod.net.WarQueuer;

class AttackTimerMenuTest {
	private static final WarQueuer ALICE = new WarQueuer("Alice", "00000000-0000-0000-0000-00000000000a", false);
	private static final WarQueuer BOB = new WarQueuer("Bob", "00000000-0000-0000-0000-00000000000b", false);

	@BeforeEach
	void setUp() {
		AttackTimerMenu.reset();
	}

	@Test
	void boardOwnedQueuerDropsWhenTheBoardStopsListingIt() {
		AttackTimerMenu.updateBoard(List.of(new WarBoardEntry("Detlas", "", false, ALICE, List.of())));
		assertEquals(ALICE, AttackTimerMenu.queuerForTesting("Detlas"));

		AttackTimerMenu.updateBoard(List.of());
		assertNull(AttackTimerMenu.queuerForTesting("Detlas"));
	}

	@Test
	void isQueuedByMatchesUuidAndName() {
		AttackTimerMenu.updateBoard(List.of(new WarBoardEntry("Big Mushroom Cave", "", false, ALICE, List.of())));
		assertTrue(AttackTimerMenu.isQueuedBy("Big Mushroom Cave", ALICE.uuid(), "Alice"));
		assertTrue(AttackTimerMenu.isQueuedBy("big mushroom cave", "different-uuid", "Alice"));
		assertTrue(AttackTimerMenu.isQueuedBy("Big Mushroom Cave", ALICE.uuid(), "DifferentName"));
		assertFalse(AttackTimerMenu.isQueuedBy("Big Mushroom Cave", BOB.uuid(), "Bob"));
		assertFalse(AttackTimerMenu.isQueuedBy("Detlas", ALICE.uuid(), "Alice"));
	}

	@Test
	void localQueuerSurvivesBoardUpdatesUntilTheBoardOwnsIt() {
		AttackTimerMenu.putLocalQueuerForTesting("Detlas", ALICE);
		AttackTimerMenu.updateBoard(List.of());
		assertEquals(ALICE, AttackTimerMenu.queuerForTesting("Detlas"));

		// The board's (first-come) queuer wins, and from then on the board owns the entry.
		AttackTimerMenu.updateBoard(List.of(new WarBoardEntry("Detlas", "", false, BOB, List.of())));
		assertEquals(BOB, AttackTimerMenu.queuerForTesting("Detlas"));
		AttackTimerMenu.updateBoard(List.of());
		assertNull(AttackTimerMenu.queuerForTesting("Detlas"));
	}

	@Test
	void localQueuerWithNoTimerIsDroppedAfterTheGracePeriod() {
		AttackTimerMenu.putLocalQueuerForTesting("Detlas", ALICE);
		AttackTimerMenu.dropStaleLocalQueuersForTesting(Set.of(), 1_000L);
		AttackTimerMenu.dropStaleLocalQueuersForTesting(Set.of(), 30_999L);
		assertNotNull(AttackTimerMenu.queuerForTesting("Detlas"));

		AttackTimerMenu.dropStaleLocalQueuersForTesting(Set.of(), 31_000L);
		assertNull(AttackTimerMenu.queuerForTesting("Detlas"));
	}

	@Test
	void localQueuerWithATimerOnTheSidebarIsKept() {
		AttackTimerMenu.putLocalQueuerForTesting("Detlas", ALICE);
		AttackTimerMenu.dropStaleLocalQueuersForTesting(Set.of(), 1_000L);
		// The timer shows up: the no-timer clock restarts.
		AttackTimerMenu.dropStaleLocalQueuersForTesting(Set.of("Detlas"), 20_000L);
		AttackTimerMenu.dropStaleLocalQueuersForTesting(Set.of(), 40_000L);
		assertNotNull(AttackTimerMenu.queuerForTesting("Detlas"));
	}

	@Test
	void timerThatRunsOutEndsOnceItLeavesTheSidebar() {
		assertEquals(List.of("Detlas"), AttackTimerMenu.advanceTimers(Map.of("Detlas", 10), 0L).appeared());
		assertEquals(List.of(), AttackTimerMenu.advanceTimers(Map.of(), 1_000L).ended()); // a re-render blip
		assertEquals(List.of("Detlas"), AttackTimerMenu.advanceTimers(Map.of(), 4_000L).ended());
	}

	@Test
	void timerHiddenWithTimeLeftIsHeldUntilItsWarIsDue() {
		// Five minutes left when the sidebar stops showing it (another sidebar took over).
		AttackTimerMenu.advanceTimers(Map.of("Detlas", 300), 0L);
		AttackTimerMenu.advanceTimers(Map.of(), 1_000L);
		assertEquals(List.of(), AttackTimerMenu.advanceTimers(Map.of(), 60_000L).ended());
		assertEquals(List.of(), AttackTimerMenu.advanceTimers(Map.of(), 309_999L).ended());
		// Due at 300s, ended once the grace after that has passed.
		assertEquals(List.of("Detlas"), AttackTimerMenu.advanceTimers(Map.of(), 310_000L).ended());
	}

	@Test
	void hiddenTimerComingBackIsNotANewTimer() {
		AttackTimerMenu.advanceTimers(Map.of("Detlas", 300), 0L);
		AttackTimerMenu.advanceTimers(Map.of(), 1_000L);
		AttackTimerMenu.advanceTimers(Map.of(), 30_000L);
		AttackTimerMenu.TimerChanges back = AttackTimerMenu.advanceTimers(Map.of("Detlas", 270), 31_000L);
		assertEquals(List.of(), back.appeared());
		assertEquals(List.of(), back.ended());
	}

	@Test
	void newTimerIsThisClientsQueueOnlyIfItsAttackSlotWasJustClickedHere() {
		long now = System.currentTimeMillis();
		assertEquals(List.of(), AttackTimerMenu.matchQueues(List.of("Detlas"), now)); // never clicked

		AttackTimerMenu.noteAttackClick("Detlas");
		assertEquals(List.of(), AttackTimerMenu.matchQueues(List.of("Ragni"), now)); // a different territory
		assertEquals(List.of("Detlas"), AttackTimerMenu.matchQueues(List.of("Detlas"), now));
		assertEquals(List.of(), AttackTimerMenu.matchQueues(List.of("Detlas"), now + 6_000L)); // too long ago
	}

	@Test
	void newTimerSomeoneElseQueuedIsNotClaimed() {
		AttackTimerMenu.putLocalQueuerForTesting("Detlas", BOB);
		AttackTimerMenu.noteAttackClick("Detlas");
		assertEquals(List.of(), AttackTimerMenu.matchQueues(List.of("Detlas"), System.currentTimeMillis()));
	}

	@Test
	void worldJoinKeepsTheBoardButResetClearsIt() {
		WarGoer goer = new WarGoer("Carol", "00000000-0000-0000-0000-00000000000c", false);
		AttackTimerMenu.updateBoard(List.of(new WarBoardEntry("Detlas", "High", false, ALICE, List.of(goer))));

		AttackTimerMenu.onWorldJoin();
		assertEquals(ALICE, AttackTimerMenu.queuerForTesting("Detlas"));
		assertEquals(List.of(goer), AttackTimerMenu.goingForTesting("Detlas"));

		AttackTimerMenu.reset();
		assertNull(AttackTimerMenu.queuerForTesting("Detlas"));
		assertNull(AttackTimerMenu.goingForTesting("Detlas"));
	}
}
