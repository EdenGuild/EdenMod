package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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

	@Test
	void parseForRelayResolvesNicknamesAndDropsUnverifiedActors() {
		Component hovered = Component.empty().append(Component.literal("ok").withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(Component.literal("ok's real username is PeanutsYeah"))))).append(" has started boosting the guild");
		assertEquals(Optional.of("PeanutsYeah has started boosting the guild"), GuildAnnounceParser.parseForRelay(hovered));

		Component cachedWeekly = Component.literal("ok has finished their weekly objective.");
		assertEquals(Optional.of("PeanutsYeah has finished their weekly objective."), GuildAnnounceParser.parseForRelay(cachedWeekly));

		Component unverified = Component.literal("mystery_nick_123 has started boosting the guild");
		assertTrue(GuildAnnounceParser.parse(unverified).isPresent());
		assertTrue(GuildAnnounceParser.parseForRelay(unverified).isEmpty());
	}
}
