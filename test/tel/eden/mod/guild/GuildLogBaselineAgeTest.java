package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GuildLogBaselineAgeTest {
	private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);

	@Test
	void aBaselineFromJustBeforeAGiftIsKeptSoRapidGiftsStayDistinguishable() {
		assertFalse(GuildLogSync.baselineTooOld(1_000_000L, 1_000_000L + 2 * MINUTE));
		assertFalse(GuildLogSync.baselineTooOld(1_000_000L, 1_000_000L + 10 * MINUTE));
	}

	@Test
	void aBaselineOlderThanTenMinutesIsReplacedBeforeThePayout() {
		assertTrue(GuildLogSync.baselineTooOld(1_000_000L, 1_000_000L + 10 * MINUTE + 1));
		assertTrue(GuildLogSync.baselineTooOld(1_000_000L, 1_000_000L + 130 * MINUTE));
	}
}
