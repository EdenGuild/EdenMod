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
}
