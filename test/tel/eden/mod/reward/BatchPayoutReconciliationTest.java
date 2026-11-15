package tel.eden.mod.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BatchPayoutReconciliationTest {
	@Test
	void onlyResendsWhenBothIndependentSourcesMissTheSameUnit() {
		Map<String, Integer> attempted = Map.of("Asthae", 3, "Koaci", 3, "Tawnyy", 3);
		Map<String, Integer> ticker = Map.of("Asthae", 3, "Koaci", 0, "Tawnyy", 2);
		Map<String, Integer> log = Map.of("Asthae", 0, "Koaci", 3, "Tawnyy", 1);

		// Asthae is proven by the ticker, Koaci by the independently read Guild Log;
		// neither may be resent. Only Tawnyy's one missing unit is eligible.
		assertEquals(Map.of("Tawnyy", 1), GuildRewards.missingBatchUnits(attempted, ticker, log));
	}

	@Test
	void aMemberCutShortMidSendIsReportedAsNotFullyPaid() {
		// MicrosRevenge was planned 71 aspects but the menu closed after 28; attempted is capped to what was sent.
		Map<String, Integer> attempted = Map.of("MicrosRevenge", 28);
		Map<String, Integer> unsent = Map.of("MicrosRevenge", 43);

		assertEquals("Not everything was sent, the menu closed or the run was cut short: MicrosRevenge (43 of 71 aspects not sent). The rest stays pending.", GuildRewards.describeUnsent(attempted, unsent, GuildRewards.RewardType.ASPECT, true));
		// A giveaway has no pending balance to leave the rest in.
		assertEquals("Not everything was sent, the menu closed or the run was cut short: MicrosRevenge (43 of 71 aspects not sent). The rest was not given.", GuildRewards.describeUnsent(attempted, unsent, GuildRewards.RewardType.ASPECT, false));
	}
}
