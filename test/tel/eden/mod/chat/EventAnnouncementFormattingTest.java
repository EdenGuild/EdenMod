package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import tel.eden.mod.EdenModClient;

class EventAnnouncementFormattingTest {

	@Test
	void testOwnEventAnnouncementFormatting() {
		assertEquals("🔁 You've completed Repeatable Mission (Complete any single raid 3 times) — +3 LE!", EdenModClient.formatOwnEventAnnouncement("🔁 Good627 completed Repeatable Mission (Complete any single raid 3 times) — +3 LE!"));
		assertEquals("⭐ You've completed Daily Mission (NOL 1) — +8 LE!", EdenModClient.formatOwnEventAnnouncement("⭐ good627 completed Daily Mission (NOL 1) — +8 LE!"));
		assertEquals("🎯 You've completed Special Mission (WTP 2) — +16 LE!", EdenModClient.formatOwnEventAnnouncement("🎯 Good627 completed Special Mission (WTP 2) — +16 LE!"));
		assertEquals("🏆 You've unlocked All Raids Objective Tier 1 (10 raids)— +8 LE!", EdenModClient.formatOwnEventAnnouncement("🏆 Good627 unlocked All Raids Objective Tier 1 (10 raids)— +8 LE!"));
	}

	@Test
	void ownLineIsRewordedEvenWhenTheStoredNameIsStale() {
		// The bot's name can lag an IGN change; the self flag still marks the line, and the
		// rewording doesn't need the name to match.
		assertEquals("🎯 You've completed Special Mission (WTP 2) — +16 LE!", EdenModClient.formatOwnEventAnnouncement("🎯 OldName completed Special Mission (WTP 2) — +16 LE!"));
		assertEquals("not an event line", EdenModClient.formatOwnEventAnnouncement("not an event line"));
		assertNull(EdenModClient.formatOwnEventAnnouncement(null));
	}
}
