package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class GuildLevelUpParserTest {
	@Test
	void relaysTheLevelUpLineVerbatimIncludingItsVaryingRewardTail() {
		String relayed = GuildLevelUpParser.parse(Component.literal("Guild Level Up! Eden is now level 111  +4 Member Slots")).orElseThrow();

		assertEquals("Guild Level Up! Eden is now level 111 +4 Member Slots", relayed);
	}

	@Test
	void rejectsTheHeaderWithoutTheLevelDataLine() {
		// Both the header and the "is now level N" data must be present.
		assertTrue(GuildLevelUpParser.parse(Component.literal("Guild Level Up!")).isEmpty());
	}

	@Test
	void rejectsUnrelatedMessagesAndNull() {
		assertTrue(GuildLevelUpParser.parse(Component.literal("Just a regular guild chat line")).isEmpty());
		assertTrue(GuildLevelUpParser.parse(null).isEmpty());
	}
}
