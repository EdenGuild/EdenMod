package tel.eden.mod.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RewardRetryPolicyTest {
	@Test
	void oneUnavailableSignalRepairsTheObservedTwentyOneOfTwentyTwoGift() {
		RewardRetryPolicy.Correction correction = RewardRetryPolicy.correction(22, 22, 21, 1);

		assertEquals(1, correction.unavailableUnits());
		assertEquals(0, correction.silentUnits());
	}

	@Test
	void unavailableResponseOwnsOneOutcomeBeforeDuplicateTicker() {
		RewardRetryPolicy.Correction correction = RewardRetryPolicy.correction(22, 22, 22, 1);

		assertEquals(1, correction.unavailableUnits());
		assertEquals(0, correction.silentUnits());
	}

	@Test
	void stoppedBurstSeparatesRejectedUnitFromUnsentRemainder() {
		RewardRetryPolicy.Correction correction = RewardRetryPolicy.correction(22, 8, 7, 1);

		assertEquals(1, correction.unavailableUnits());
		assertEquals(14, correction.silentUnits());
	}

	@Test
	void backoffRetriesOnceImmediatelyThenGrowsAndCaps() {
		assertEquals(0L, RewardRetryPolicy.backoffMs(1));
		assertEquals(500L, RewardRetryPolicy.backoffMs(2));
		assertEquals(1000L, RewardRetryPolicy.backoffMs(3));
		assertEquals(2000L, RewardRetryPolicy.backoffMs(4));
		assertEquals(8000L, RewardRetryPolicy.backoffMs(20));
	}

	@Test
	void allThirtyThreeConfirmationsPreventTheFalseThirtyFourthAttempt() {
		RewardRetryPolicy.Correction correction = RewardRetryPolicy.correction(33, 33, 33, 0);

		assertEquals(0, correction.unavailableUnits());
		assertEquals(0, correction.silentUnits());
	}

	@Test
	void aCancelledGiftCountsOnlyTheClicksThatWereSent() {
		// 48 planned, cancelled after 5 clicks, 4 of them confirmed: one click is unaccounted
		// for, not forty-four.
		int attempted = RewardRetryPolicy.attempted(true, 48, 5);
		assertEquals(5, attempted);
		RewardRetryPolicy.Correction correction = RewardRetryPolicy.correction(attempted, 5, 4, 0);
		assertEquals(1, correction.silentUnits());
	}

	@Test
	void anUncancelledGiftStillOwesItsUnsentRemainder() {
		// A burst stopped by a rejection leaves clicks unsent; those stay counted so reconciliation looks for them.
		assertEquals(48, RewardRetryPolicy.attempted(false, 48, 20));
		assertEquals(28, RewardRetryPolicy.correction(48, 20, 20, 0).silentUnits());
	}

	@Test
	void attemptedNeverExceedsTheAmountOrGoesNegative() {
		assertEquals(48, RewardRetryPolicy.attempted(true, 48, 60));
		assertEquals(0, RewardRetryPolicy.attempted(true, 48, -3));
	}
}
