package tel.eden.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class GroundItemNameTest {
	@Test
	void sanitizeTrimsAndCollapsesAnyWhitespaceRuns() {
		assertEquals("a b c", BridgeConfig.sanitizeGroundItemName("  a   b\t \n c "));
		assertEquals("", BridgeConfig.sanitizeGroundItemName("   "));
		assertEquals("", BridgeConfig.sanitizeGroundItemName(null));
		// The fast path must agree with the regex for every kind of whitespace and for plain text.
		assertEquals("a b", BridgeConfig.sanitizeGroundItemName("a\tb"));
		assertEquals("a b", BridgeConfig.sanitizeGroundItemName("a\u000Bb"));
		assertEquals("a b", BridgeConfig.sanitizeGroundItemName("a  b"));
		assertEquals("a b", BridgeConfig.sanitizeGroundItemName("a b"));
		assertEquals("plain", BridgeConfig.sanitizeGroundItemName("plain"));
	}

	@Test
	void normalizeLowercases() {
		assertEquals("health potion", BridgeConfig.normalizeGroundItemName("  Health   POTION "));
	}

	@Test
	void ruleNormalizesOnceAndFollowsReassignment() {
		BridgeConfig.GroundItemVisibilityRule rule = new BridgeConfig.GroundItemVisibilityRule("  Perf   Item ", 1.5f);

		String first = rule.normalizedNameContains();
		assertEquals("perf item", first);
		assertSame(first, rule.normalizedNameContains(), "unchanged field must not be re-normalized");

		rule.nameContains = "Other";
		assertEquals("other", rule.normalizedNameContains());
		rule.nameContains = null;
		assertEquals("", rule.normalizedNameContains());
	}
}
