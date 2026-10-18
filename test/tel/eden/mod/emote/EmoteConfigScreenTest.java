package tel.eden.mod.emote;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class EmoteConfigScreenTest {

	@Test
	void previousOptionCyclesBackwardsCorrectly() {
		List<String> options = List.of("", "dance", "wave", "cheer");

		// wave -> dance
		assertEquals("dance", EmoteConfigScreen.previousOption(options, "wave"));

		// cheer -> wave
		assertEquals("wave", EmoteConfigScreen.previousOption(options, "cheer"));

		// dance -> ""
		assertEquals("", EmoteConfigScreen.previousOption(options, "dance"));

		// "" (first element / index 0) wraps to last element "cheer"
		assertEquals("cheer", EmoteConfigScreen.previousOption(options, ""));
	}

	@Test
	void previousOptionHandlesEdgeCases() {
		List<String> options = List.of("", "dance");

		// Unknown element (-1) wraps to last element
		assertEquals("dance", EmoteConfigScreen.previousOption(options, "unknown"));

		// Single element wraps to itself
		assertEquals("", EmoteConfigScreen.previousOption(List.of(""), ""));

		// Empty/null options
		assertEquals("", EmoteConfigScreen.previousOption(List.of(), "test"));
		assertEquals("", EmoteConfigScreen.previousOption(null, "test"));
	}

	@Test
	void randomizeEmotesPicksValidOptions() {
		List<String> pool = List.of("dance", "wave", "cheer", "cry", "laugh", "sit", "lay", "spin", "jump", "clap");
		List<String> picked = EmoteConfigScreen.randomizeEmotes(pool, 8);
		assertEquals(8, picked.size());
		// All 8 picked items must be in pool
		for (String item : picked) {
			org.junit.jupiter.api.Assertions.assertTrue(pool.contains(item));
		}
		// Since pool had 10 distinct items, picking 8 must result in 8 unique items
		long uniqueCount = picked.stream().distinct().count();
		assertEquals(8, uniqueCount);
	}

	@Test
	void randomizeEmotesHandlesSmallOrEmptyPool() {
		// Empty pool
		List<String> emptyPicked = EmoteConfigScreen.randomizeEmotes(List.of(), 8);
		assertEquals(8, emptyPicked.size());
		for (String item : emptyPicked) {
			assertEquals("", item);
		}

		// Null pool
		List<String> nullPicked = EmoteConfigScreen.randomizeEmotes(null, 8);
		assertEquals(8, nullPicked.size());
		for (String item : nullPicked) {
			assertEquals("", item);
		}

		// Small pool (2 emotes) wraps around to fill 8 slots
		List<String> smallPool = List.of("dance", "wave");
		List<String> smallPicked = EmoteConfigScreen.randomizeEmotes(smallPool, 8);
		assertEquals(8, smallPicked.size());
		for (String item : smallPicked) {
			org.junit.jupiter.api.Assertions.assertTrue(smallPool.contains(item));
		}
	}

	@Test
	void emoteWheelPageManagement() {
		tel.eden.mod.config.BridgeConfig config = new tel.eden.mod.config.BridgeConfig();
		config.emoteWheelFavorites = List.of("e1", "e2", "e3");
		config.emoteWheelPages = new java.util.ArrayList<>();

		EmoteWheel.ensurePages(config);
		assertEquals(1, config.emoteWheelPages.size());
		assertEquals("e1", config.emoteWheelPages.get(0).get(0));
		assertEquals(0, config.emoteWheelActivePage);

		// Add page 2
		config.emoteWheelPages.add(List.of("p2_1", "p2_2"));
		assertEquals(2, config.emoteWheelPages.size());

		// Test active page selection
		config.emoteWheelActivePage = 1;
		EmoteWheel.ensurePages(config);
		assertEquals("p2_1", config.emoteWheelFavorites.get(0));

		// Clamp out of bounds activePage
		config.emoteWheelActivePage = 99;
		EmoteWheel.ensurePages(config);
		assertEquals(0, config.emoteWheelActivePage);
	}
}
