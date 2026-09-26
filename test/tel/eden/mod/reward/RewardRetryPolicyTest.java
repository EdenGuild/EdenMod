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
}
