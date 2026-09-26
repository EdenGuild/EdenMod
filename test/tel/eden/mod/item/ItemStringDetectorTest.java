package tel.eden.mod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ItemStringDetectorTest {
	// Item strings are runs of Supplementary Private Use Area A (U+F0000..U+FFFFD)
	// code points. Built via Character.toChars rather than typed literal characters,
	// so this can't silently mismatch what the source's code-point range actually is.
	private static String puaRun(int count) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < count; i++) {
			out.appendCodePoint(0xF0000 + i);
		}
		return out.toString();
	}

	// A short (one/two-glyph) rank-badge-style decoration, well under the minimum.
	private static String shortDecoration() {
		return puaRun(2);
	}

	@Test
	void detectsAQualifyingItemStringWithNoCraftedName() {
		String item = puaRun(8);

		var detected = ItemStringDetector.detect("check this out: " + item + " nice right?").orElseThrow();

		assertEquals(item, detected.itemString());
		assertNull(detected.craftedName());
	}

	@Test
	void detectsACraftedItemsClearTextName() {
		String item = puaRun(8);

		var detected = ItemStringDetector.detect(item + " \"My Cool Weapon\"").orElseThrow();

		assertEquals(item, detected.itemString());
		assertEquals("My Cool Weapon", detected.craftedName());
	}

	@Test
	void ignoresAShortRankOrBannerGlyphRunBelowTheMinimumLength() {
		assertTrue(ItemStringDetector.detect(shortDecoration() + " some guild chat text").isEmpty());
	}

	@Test
	void picksTheLongestRunWhenBothADecorationAndAnItemArePresent() {
		String item = puaRun(10);
		String text = shortDecoration() + " GuildMember: sharing " + item;

		var detected = ItemStringDetector.detect(text).orElseThrow();

		assertEquals(item, detected.itemString());
	}

	@Test
	void rejectsPlainTextAndNullAndEmpty() {
		assertTrue(ItemStringDetector.detect("just plain chat, no items here").isEmpty());
		assertTrue(ItemStringDetector.detect(null).isEmpty());
		assertTrue(ItemStringDetector.detect("").isEmpty());
	}
}
