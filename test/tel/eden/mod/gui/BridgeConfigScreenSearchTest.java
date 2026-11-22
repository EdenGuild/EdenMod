package tel.eden.mod.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tel.eden.mod.gui.BridgeConfigScreen.Category;

class BridgeConfigScreenSearchTest {
	@Test
	void blankOrNullQueryMatchesAllRows() {
		assertTrue(BridgeConfigScreen.matchesSearch("War start bell (25s)", Category.WAR, null));
		assertTrue(BridgeConfigScreen.matchesSearch("War start bell (25s)", Category.WAR, ""));
		assertTrue(BridgeConfigScreen.matchesSearch("War start bell (25s)", Category.WAR, "   "));
	}

	@Test
	void matchesCaseInsensitiveMultiTokenQueriesAcrossLabelAndCategory() {
		assertTrue(BridgeConfigScreen.matchesSearch("War start bell (25s)", Category.WAR, "war bell"));
		assertTrue(BridgeConfigScreen.matchesSearch("Party health bar size", Category.PARTY_RAIDS, "HEALTH size"));
		assertTrue(BridgeConfigScreen.matchesSearch("Radiance tracker", Category.WAR, "war radiance"));
		assertTrue(BridgeConfigScreen.matchesSearch("Image preview size", Category.VISUALS, "visuals"));
	}

	@Test
	void rejectsWhenAnyTokenMissesBothLabelAndCategory() {
		assertFalse(BridgeConfigScreen.matchesSearch("War start bell (25s)", Category.WAR, "war missing"));
		assertFalse(BridgeConfigScreen.matchesSearch("Emote wheel", Category.VISUALS, "raid"));
	}
}
