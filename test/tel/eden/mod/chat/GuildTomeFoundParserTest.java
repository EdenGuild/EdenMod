package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class GuildTomeFoundParserTest {
	@Test
	void matchesTheAnnouncement() {
		assertTrue(GuildTomeFoundParser.matches(Component.literal("A Guild Tome has been found and added to the Guild Rewards")));
		assertTrue(GuildTomeFoundParser.matches(Component.literal("A Guild Tome has been found and added to the Guild Rewards.")));
	}

	@Test
	void ignoresAPlayerQuotingIt() {
		assertFalse(GuildTomeFoundParser.matches(Component.literal("Bob: A Guild Tome has been found and added to the Guild Rewards")));
	}

	@Test
	void ignoresAHandoutAndOtherLines() {
		assertFalse(GuildTomeFoundParser.matches(Component.literal("Chief1 rewarded a Guild Tome to Asthae")));
		assertFalse(GuildTomeFoundParser.matches(Component.literal("hello")));
		assertFalse(GuildTomeFoundParser.matches(null));
	}
}
