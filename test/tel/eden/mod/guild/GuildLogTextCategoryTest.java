package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GuildLogTextCategoryTest {
	@Test
	void warsAndEconomyAreStoredAsText() {
		assertTrue(GuildLogSync.isTextCategory("Wars"));
		assertTrue(GuildLogSync.isTextCategory("economy"));
		assertFalse(GuildLogSync.isTextCategory("General"));
		assertFalse(GuildLogSync.isTextCategory("Objectives"));
	}

	@Test
	void aTextRowKeepsOnlyTheCleanedSentenceAndItsTime() {
		var entry = new GuildLogEntry("6 hours ago (10/25/26 09:17 AM EDT)", "§7󏿼 snail111860 changed 2 upgrades on Gylia\n§7󏿼 Research Cabin");

		var event = (GuildLogEvent.Text) GuildLogSync.textEvent(entry);

		assertEquals("snail111860 changed 2 upgrades on Gylia Research Cabin", event.text());
		assertEquals("6 hours ago (10/25/26 09:17 AM EDT)", event.occurredAt());
	}

	@Test
	void aVeryLongRowIsTrimmedToWhatTheBackendAccepts() {
		var event = (GuildLogEvent.Text) GuildLogSync.textEvent(new GuildLogEntry("1 minute ago (10/25/26 09:17 AM EDT)", "x".repeat(1000)));

		assertEquals(300, event.text().length());
	}
}
