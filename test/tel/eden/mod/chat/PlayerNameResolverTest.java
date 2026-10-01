package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Only the cache-backed path is covered here: the live tab-list fallback needs a
 * running Minecraft client (Minecraft.getInstance()), which this project's test suite
 * deliberately doesn't stand up — consistent with every other client-dependent class
 * (e.g. BackgroundContainerSession) having no unit tests of its own.
 *
 * <p>USERNAME_BY_DISPLAY is a static, process-wide cache, so each test below uses its
 * own distinct names to stay independent of test execution order.
 */
class PlayerNameResolverTest {
	@Test
	void resolveKnownReturnsEmptyForNull() {
		assertEquals(Optional.empty(), PlayerNameResolver.resolveKnown(null));
	}

	@Test
	void resolveKnownReturnsAPreviouslyRecordedAlias() {
		// recordAlias() is how chat parsers (guild chat, rewards, bank) already teach the
		// resolver a nickname -> real username mapping; resolveKnown() must honour that
		// cache before ever falling back to a live (client-dependent) tab-list lookup.
		PlayerNameResolver.recordAlias("catboy1", "FadeDave1");

		assertEquals(Optional.of("FadeDave1"), PlayerNameResolver.resolveKnown("catboy1"));
		// Case/whitespace-insensitive, matching canonicalize()'s existing key() normalisation.
		assertEquals(Optional.of("FadeDave1"), PlayerNameResolver.resolveKnown(" CatBoy1 "));
	}

	@Test
	void resolveKnownAlsoResolvesTheRealUsernameItself() {
		PlayerNameResolver.recordAlias("nick2", "Asthae2");

		assertEquals(Optional.of("Asthae2"), PlayerNameResolver.resolveKnown("Asthae2"));
	}

	@Test
	void observeMessageLearnsFromHoverAndInsertion() {
		net.minecraft.network.chat.Component hoverMsg = net.minecraft.network.chat.Component.literal("CustomNick").withStyle(s -> s.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(net.minecraft.network.chat.Component.literal("CustomNick's real username is CustomUser"))));
		PlayerNameResolver.observeMessage(hoverMsg);
		assertEquals(Optional.of("CustomUser"), PlayerNameResolver.resolveKnown("CustomNick"));

		net.minecraft.network.chat.Component insertMsg = net.minecraft.network.chat.Component.literal("TestNick").withStyle(s -> s.withInsertion("TestReal"));
		PlayerNameResolver.observeMessage(insertMsg);
		assertEquals(Optional.of("TestReal"), PlayerNameResolver.resolveKnown("TestNick"));
	}

	@Test
	void observeMessageLearnsFromPartyPatterns() {
		// Party join message
		net.minecraft.network.chat.Component joinMsg = net.minecraft.network.chat.Component.empty().append(net.minecraft.network.chat.Component.literal("SoloNick").withStyle(s -> s.withInsertion("SoloReal"))).append(net.minecraft.network.chat.Component.literal(" has joined your party."));
		PlayerNameResolver.observeMessage(joinMsg);
		assertEquals(Optional.of("SoloReal"), PlayerNameResolver.resolveKnown("SoloNick"));

		// Parenthesized nickname (real)
		net.minecraft.network.chat.Component parenMsg = net.minecraft.network.chat.Component.literal("ParenNick (ParenReal): Hello party!");
		PlayerNameResolver.observeMessage(parenMsg);
		assertEquals(Optional.of("ParenReal"), PlayerNameResolver.resolveKnown("ParenNick"));
	}

	@Test
	void resolveKnownMatchesTruncatedNicknames() {
		PlayerNameResolver.recordAlias("get it twisted", "TruncatedUser");
		// Scoreboard truncation cases
		assertEquals(Optional.of("TruncatedUser"), PlayerNameResolver.resolveKnown("get it t"));
		assertEquals(Optional.of("TruncatedUser"), PlayerNameResolver.resolveKnown("get it"));
		assertEquals(Optional.of("TruncatedUser"), PlayerNameResolver.resolveKnown("getitt"));
		assertEquals("TruncatedUser", PlayerNameResolver.canonicalize("get it t"));
		assertEquals("TruncatedUser", PlayerNameResolver.canonicalize("get it"));
	}
}
