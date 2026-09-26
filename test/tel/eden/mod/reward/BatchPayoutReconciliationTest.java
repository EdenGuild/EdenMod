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
}
