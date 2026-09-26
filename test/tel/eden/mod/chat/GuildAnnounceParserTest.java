package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class GuildAnnounceParserTest {
	@Test
	void relaysAWeeklyObjectiveCompletionVerbatim() {
		String relayed = GuildAnnounceParser.parse(Component.literal("KingVonGaming has finished their weekly objective.")).orElseThrow();

		assertEquals("KingVonGaming has finished their weekly objective.", relayed);
	}

	@Test
	void relaysABoostAnnouncementVerbatim() {
		String relayed = GuildAnnounceParser.parse(Component.literal("m1ngx210 has started boosting the guild")).orElseThrow();

		assertEquals("m1ngx210 has started boosting the guild", relayed);
	}

	@Test
	void rejectsUnrelatedMessagesAndNull() {
		assertTrue(GuildAnnounceParser.parse(Component.literal("Just a regular guild chat line")).isEmpty());
		assertTrue(GuildAnnounceParser.parse(null).isEmpty());
	}
}
