package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link GuildLogSync#payoutBoundary} decides how many post-payout log rows are new by
 * aligning a run of consecutive keys against the pre-payout baseline, rather than
 * single-key set membership — Wynncraft's log timestamp has no seconds field, so two
 * distinct rows (e.g. the same reward self-gifted twice within one minute, which is
 * exactly what repeated manual testing does) can otherwise produce an identical key and
 * be mistaken for each other.
 */
class GuildLogSyncPayoutBoundaryTest {
	@Test
	void findsNoNewRowsWhenTheTopRowAlreadyMatchesTheBaseline() {
		List<String> baseline = List.of("a", "b", "c");
		List<String> combined = List.of("a", "b", "c");

		assertEquals(0, GuildLogSync.payoutBoundary(combined, baseline, 3));
	}

	@Test
	void countsOneGenuinelyNewRowAheadOfTheBaseline() {
		List<String> baseline = List.of("a", "b", "c");
		List<String> combined = List.of("new", "a", "b", "c");

		assertEquals(1, GuildLogSync.payoutBoundary(combined, baseline, 3));
	}

	@Test
	void aDuplicateTopRowIsNotMistakenForTheBaselineItLooksIdenticalTo() {
		// Same key ("reward") shows up twice: once as a genuinely new row from this
		// payout, once as the pre-existing baseline row it happens to match exactly
		// (same player, same reward type, same rounded-to-the-minute timestamp). A
		// single-key boundary check would stop at index 0 and report zero new rows;
		// the 3-row window instead requires the *next two* rows to also line up with
		// the baseline before accepting a match, so it correctly walks past the
		// duplicate.
		List<String> baseline = List.of("reward", "b", "c");
		List<String> combined = List.of("reward", "reward", "b", "c");

		assertEquals(1, GuildLogSync.payoutBoundary(combined, baseline, 3));
	}

	@Test
	void multipleDuplicateTopRowsAreAllCountedAsNew() {
		List<String> baseline = List.of("reward", "b", "c");
		List<String> combined = List.of("reward", "reward", "reward", "b", "c");

		assertEquals(2, GuildLogSync.payoutBoundary(combined, baseline, 3));
	}

	@Test
	void returnsMinusOneWhenNoAlignmentHasBeenFoundYet() {
		List<String> baseline = List.of("a", "b", "c");
		List<String> combined = List.of("x", "y");

		assertEquals(-1, GuildLogSync.payoutBoundary(combined, baseline, 3));
	}

	@Test
	void everythingReadSoFarIsNewWhenThereIsNoBaselineAtAll() {
		assertEquals(0, GuildLogSync.payoutBoundary(List.of("a", "b"), List.of(), 0));
		assertEquals(-1, GuildLogSync.payoutBoundary(List.of(), List.of(), 0));
	}

	@Test
	void aShorterBaselineStillUsesTheSmallerWindowCorrectly() {
		// A young guild with only one prior log row: window collapses to 1, so a
		// single-key match is the best available signal (the ambiguity this test
		// class is otherwise guarding against needs at least 2 baseline rows to show
		// up, since the window can never exceed the baseline size).
		List<String> baseline = List.of("only");
		List<String> combined = List.of("new", "only");

		assertEquals(1, GuildLogSync.payoutBoundary(combined, baseline, 1));
	}
}
