package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuildLogFilterButtonTest {
	@Test
	void knowsTheSixFiltersInTheFirstRowFromSlotTwo() {
		assertEquals(List.of("General", "Objectives", "Wars", "Economy", "Public Bank", "High Ranked Bank"), GuildLogSync.KNOWN_FILTERS.stream().map(GuildLogSync.FilterButton::name).toList());
		assertEquals(List.of(2, 3, 4, 5, 6, 7), GuildLogSync.KNOWN_FILTERS.stream().map(GuildLogSync.FilterButton::slot).toList());
	}

	@Test
	void acceptsASlotHoldingTheExpectedFilterIgnoringCase() {
		assertTrue(GuildLogSync.isExpectedFilter("Public Bank", "Public Bank"));
		assertTrue(GuildLogSync.isExpectedFilter("public bank logs", "Public Bank"));
	}

	@Test
	void rejectsAnotherItemOrAnEmptySlot() {
		assertFalse(GuildLogSync.isExpectedFilter("Next Page", "Wars"));
		assertFalse(GuildLogSync.isExpectedFilter(null, "Economy"));
	}

	@Test
	void aWholeLogWalkScansGeneralOnlyAcrossFivePages() {
		assertEquals(5, GuildLogSync.FULL_SCAN_TOTAL_PAGES);
	}

	@Test
	void cancelFlagTogglesAndResetsCleanly() {
		GuildLogSync sync = new GuildLogSync(null);
		assertFalse(sync.isCancelRequested());
		sync.requestCancel();
		assertTrue(sync.isCancelRequested());
		sync.clearCancel();
		assertFalse(sync.isCancelRequested());
	}

	@Test
	void cancelledPostGiftRetryReturnsNullInsteadOfEmptyList() {
		GuildLogSync sync = new GuildLogSync(null);
		assertNull(sync.retryPostGiftLogRead(() -> {
			sync.requestCancel();
			return List.of();
		}, 5_000L, 100L));
		sync.clearCancel();
		assertEquals(List.of(), sync.retryPostGiftLogRead(List::of, 0L, 10L));
	}
}
