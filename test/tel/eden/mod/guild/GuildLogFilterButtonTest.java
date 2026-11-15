package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuildLogFilterButtonTest {
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
}
