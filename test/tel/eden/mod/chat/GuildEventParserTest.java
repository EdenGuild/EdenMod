package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

class GuildEventParserTest {
	@Test
	void parsesAJoin() {
		GuildEvent event = GuildEventParser.parse(Component.literal("NewMember has joined the guild, say hello!")).orElseThrow();

		assertEquals("join", event.kind());
		assertEquals("", event.actor());
		assertEquals("NewMember", event.subject());
	}

	@Test
	void parsesALeave() {
		GuildEvent event = GuildEventParser.parse(Component.literal("OldMember has left the guild.")).orElseThrow();

		assertEquals("leave", event.kind());
		assertEquals("OldMember", event.subject());
	}

	@Test
	void parsesAnInvite() {
		GuildEvent event = GuildEventParser.parse(Component.literal("Officer has invited Recruit to the guild")).orElseThrow();

		assertEquals("invite", event.kind());
		assertEquals("Officer", event.actor());
		assertEquals("Recruit", event.subject());
	}

	@Test
	void parsesAnUninvite() {
		GuildEvent event = GuildEventParser.parse(Component.literal("Officer has uninvited Recruit from the guild")).orElseThrow();

		assertEquals("uninvite", event.kind());
		assertEquals("Officer", event.actor());
		assertEquals("Recruit", event.subject());
	}

	@Test
	void parsesAKickAndResolvesTheKickersRealName() {
		Component kicker = Component.literal("catboy").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("catboy's real username is FadeDave"))));
		Component message = Component.empty().append(kicker).append(" has kicked Slacker from the guild");

		GuildEvent event = GuildEventParser.parse(message).orElseThrow();

		assertEquals("kick", event.kind());
		assertEquals("FadeDave", event.actor());
		assertEquals("Slacker", event.subject());
	}

	@Test
	void parsesAnAllianceRequestKeepingTheOtherGuildAsShown() {
		GuildEvent event = GuildEventParser.parse(Component.literal("AllyLeader from Another Guild is requesting to be allied")).orElseThrow();

		assertEquals("alliance_request", event.kind());
		assertEquals("AllyLeader", event.actor());
		assertEquals("Another Guild", event.subject());
	}

	@Test
	void parsesAnOutgoingAllianceRequestSent() {
		GuildEvent event = GuildEventParser.parse(Component.literal("Officer sent Another Guild a request to be allied")).orElseThrow();

		assertEquals("alliance_sent", event.kind());
		assertEquals("Officer", event.actor());
		assertEquals("Another Guild", event.subject());
	}

	@Test
	void parsesAnAllianceRequestRejected() {
		GuildEvent event = GuildEventParser.parse(Component.literal("Officer rejected Another Guild alliance request")).orElseThrow();

		assertEquals("alliance_rejected", event.kind());
		assertEquals("Another Guild", event.subject());
	}

	@Test
	void parsesAnAllianceRequestWithdrawn() {
		GuildEvent event = GuildEventParser.parse(Component.literal("Officer withdrew Another Guild alliance request")).orElseThrow();

		assertEquals("alliance_withdrew", event.kind());
		assertEquals("Another Guild", event.subject());
	}

	@Test
	void parsesAnAllianceRevoked() {
		GuildEvent event = GuildEventParser.parse(Component.literal("Officer revoked the alliance with Some Other Guild")).orElseThrow();

		assertEquals("alliance_revoked", event.kind());
		assertEquals("Officer", event.actor());
		assertEquals("Some Other Guild", event.subject());
	}

	@Test
	void parsesAnAllianceFormedKeepingTheFormingGuildAsTheActorEvenWhenItIsNotEden() {
		// The forming guild isn't always us — must not assume "Eden" is always the actor.
		GuildEvent event = GuildEventParser.parse(Component.literal("Other Guild formed an alliance with Eden")).orElseThrow();

		assertEquals("alliance_formed", event.kind());
		assertEquals("Other Guild", event.actor());
		assertEquals("Eden", event.subject());
	}

	@Test
	void rejectsAGuildChatLineThatMerelyMentionsTheseKeywords() {
		// ':' marks a chat line ("Sender: message"); must never be misread as an event.
		assertTrue(GuildEventParser.parse(Component.literal("FadeDave: did anyone see who left the guild")).isEmpty());
		assertTrue(GuildEventParser.parse(Component.literal("FadeDave: we should form an alliance with them")).isEmpty());
	}

	@Test
	void rejectsUnrelatedMessagesAndNull() {
		assertTrue(GuildEventParser.parse(Component.literal("Just a regular guild chat line")).isEmpty());
		assertTrue(GuildEventParser.parse(null).isEmpty());
	}

	@Test
	void isCandidateGatesOnKeywordsBeforeAnyRegexRuns() {
		assertTrue(GuildEventParser.isCandidate(Component.literal("X has joined the guild")));
		assertTrue(GuildEventParser.isCandidate(Component.literal("requesting to be allied")));
		assertFalse(GuildEventParser.isCandidate(Component.literal("")));
		assertFalse(GuildEventParser.isCandidate(null));
	}
}
