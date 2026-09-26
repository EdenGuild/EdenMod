package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

class ShoutParserTest {
	@Test
	void resolvesBareNicknameFromRealUsernameHover() {
		Component shouter = Component.literal("MarketGoblin").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("MarketGoblin's real username is Actual_IGN"))));
		Component message = Component.empty().append(shouter).append(" shouts: selling mythics");

		assertEquals("Actual_IGN shouts: selling mythics", ShoutParser.parse(message).orElseThrow());
		assertEquals("Actual_IGN", ShoutParser.shouterRealName(message).orElseThrow());
	}

	@Test
	void remembersResolvedNicknameForNonChatIdentityBoundaries() {
		Component shouter = Component.literal("MarketGoblin").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("MarketGoblin's real username is Actual_IGN"))));
		ShoutParser.parse(Component.empty().append(shouter).append(" shouts: selling mythics")).orElseThrow();

		assertEquals("Actual_IGN", PlayerNameResolver.canonicalize("MarketGoblin"));
	}
}
