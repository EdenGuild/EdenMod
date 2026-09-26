package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

class GuildRewardParserTest {
	@Test
	void resolvesEachNameFromItsOwnSpanInsteadOfReusingTheGiversHover() {
		Component giver = Component.literal("catboy").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("catboy's real username is FadeDave"))));
		Component message = Component.empty().append(giver).append(" rewarded an Aspect to AllIWantForXmas");

		GuildReward reward = GuildRewardParser.parse(message).orElseThrow();

		assertEquals("FadeDave", reward.giver());
		assertEquals("AllIWantForXmas", reward.receiver());
	}

	@Test
	void parsesAnAspectHandout() {
		GuildReward reward = GuildRewardParser.parse(Component.literal("Chief1 rewarded an Aspect to Asthae")).orElseThrow();

		assertEquals("Chief1", reward.giver());
		assertEquals("an Aspect", reward.reward());
		assertEquals("Asthae", reward.receiver());
	}

	@Test
	void parsesAGuildTomeHandout() {
		GuildReward reward = GuildRewardParser.parse(Component.literal("Chief1 rewarded a Guild Tome to Asthae")).orElseThrow();

		assertEquals("a Guild Tome", reward.reward());
	}

	@Test
	void parsesAnEmeraldHandoutKeepingTheAmountVerbatim() {
		GuildReward reward = GuildRewardParser.parse(Component.literal("Chief1 rewarded 1024 Emeralds to Asthae")).orElseThrow();

		assertEquals("1024 Emeralds", reward.reward());
	}

	@Test
	void fallsBackToTheDisplayedNameWhenThereIsNoHoverMetadata() {
		GuildReward reward = GuildRewardParser.parse(Component.literal("Chief1 rewarded an Aspect to Asthae")).orElseThrow();

		assertEquals("Chief1", reward.giver());
		assertEquals("Asthae", reward.receiver());
	}

	@Test
	void rejectsAGuildChatLineThatMerelyMentionsRewarded() {
		// ':' marks a chat line ("Sender: message"); must never be misread as a handout.
		assertTrue(GuildRewardParser.parse(Component.literal("FadeDave: I rewarded myself with a nap")).isEmpty());
	}

	@Test
	void rejectsUnrelatedMessagesAndNull() {
		assertTrue(GuildRewardParser.parse(Component.literal("Just a regular guild chat line")).isEmpty());
		assertTrue(GuildRewardParser.parse(null).isEmpty());
	}

	@Test
	void isCandidateGatesOnTheRewardedKeyword() {
		assertTrue(GuildRewardParser.isCandidate(Component.literal("X rewarded Y to Z")));
		assertFalse(GuildRewardParser.isCandidate(Component.literal("no keyword here")));
		assertFalse(GuildRewardParser.isCandidate(null));
	}
}
