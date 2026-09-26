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
}
