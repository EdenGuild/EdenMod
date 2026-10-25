package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tel.eden.mod.EdenModClient;

class EventAnnouncementFormattingTest {

	@Test
	void testOwnEventAnnouncementDetection() {
		String self = "Good627";
		assertTrue(EdenModClient.isOwnEventAnnouncement("🔁 Good627 completed Repeatable Mission (Complete any single raid 3 times) — +3 LE!", self));
		assertTrue(EdenModClient.isOwnEventAnnouncement("⭐ good627 completed Daily Mission (NOL 1) — +8 LE!", self));
		assertTrue(EdenModClient.isOwnEventAnnouncement("🎯 Good627 completed Weekly Mission (WTP 2) — +16 LE!", self));
		assertTrue(EdenModClient.isOwnEventAnnouncement("🏆 Good627 unlocked All Raids Master Tier 1 (10 raids) — +8 LE!", self));

		assertFalse(EdenModClient.isOwnEventAnnouncement("🔁 SomeoneElse completed Repeatable Mission (Complete any single raid 3 times) — +3 LE!", self));
		assertFalse(EdenModClient.isOwnEventAnnouncement("🏆 SomeoneElse unlocked All Raids Master Tier 1 (10 raids) — +8 LE!", self));
		assertFalse(EdenModClient.isOwnEventAnnouncement("Some random chat mentioning Good627 completed", self));
		assertFalse(EdenModClient.isOwnEventAnnouncement(null, self));
		assertFalse(EdenModClient.isOwnEventAnnouncement("🔁 Good627 completed Repeatable", null));
	}

	@Test
	void testOwnEventAnnouncementFormatting() {
		String self = "Good627";
		assertEquals("🔁 You've completed Repeatable Mission (Complete any single raid 3 times) — +3 LE!", EdenModClient.formatOwnEventAnnouncement("🔁 Good627 completed Repeatable Mission (Complete any single raid 3 times) — +3 LE!", self));
		assertEquals("⭐ You've completed Daily Mission (NOL 1) — +8 LE!", EdenModClient.formatOwnEventAnnouncement("⭐ good627 completed Daily Mission (NOL 1) — +8 LE!", self));
		assertEquals("🎯 You've completed Weekly Mission (WTP 2) — +16 LE!", EdenModClient.formatOwnEventAnnouncement("🎯 Good627 completed Weekly Mission (WTP 2) — +16 LE!", self));
		assertEquals("🏆 You've unlocked All Raids Master Tier 1 (10 raids) — +8 LE!", EdenModClient.formatOwnEventAnnouncement("🏆 Good627 unlocked All Raids Master Tier 1 (10 raids) — +8 LE!", self));
	}
}
