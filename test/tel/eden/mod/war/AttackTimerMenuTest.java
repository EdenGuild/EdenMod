package tel.eden.mod.war;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
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
