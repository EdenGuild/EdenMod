package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

class RankChangeParserTest {
	@Test
	void parsesAPromotion() {
		RankChange change = RankChangeParser.parse(Component.literal("OfficerName has set MemberName guild rank from Recruit to Strategist")).orElseThrow();

		assertEquals("MemberName", change.target());
		assertEquals("Recruit", change.oldRank());
		assertEquals("Strategist", change.newRank());
		assertEquals("OfficerName", change.setter());
	}

	@Test
	void resolvesTheTargetsRealNameFromHoverIndependentlyOfTheSetters() {
		Component setter = Component.literal("catboy").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("catboy's real username is FadeDave"))));
		Component target = Component.literal("nicked").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("nicked's real username is Asthae"))));
		Component message = Component.empty().append(setter).append(" has set ").append(target).append(" guild rank from Recruit to Strategist");

		RankChange change = RankChangeParser.parse(message).orElseThrow();

		assertEquals("Asthae", change.target());
		assertEquals("FadeDave", change.setter());
	}

	@Test
	void rejectsUnrelatedMessagesAndNull() {
		assertTrue(RankChangeParser.parse(Component.literal("Just a regular guild chat line")).isEmpty());
		assertTrue(RankChangeParser.parse(null).isEmpty());
	}
}
