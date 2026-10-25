package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
	void aWholeLogWalkIsSixFiltersOfFivePages() {
		assertEquals(30, GuildLogSync.FULL_SCAN_TOTAL_PAGES);
	}
}
