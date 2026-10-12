package tel.eden.mod.war;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tel.eden.mod.config.BridgeConfig;

class TerritoryLossTrackerTest {
	private BridgeConfig config;

	@BeforeEach
	void setUp() {
		config = new BridgeConfig();
		config.territoryLossSound = true;
		config.territoryLossBlacklistEnabled = true;
		config.territoryLossBlacklist = new ArrayList<>(BridgeConfig.DEFAULT_TERRITORY_LOSS_BLACKLIST);
	}

	@Test
	void testExampleTerritoryLossMessageDefaultBlacklistSuppression() {
		// Guardian of the Forest is in the default blacklist -> should be suppressed when blacklist is enabled
		String line = "[pots] has taken control of Guardian of the Forest!";
		assertFalse(TerritoryLossTracker.processMessage(line, config), "Default blacklisted territory must be suppressed");

		// When blacklist master toggle is turned off, the sound should play
		config.territoryLossBlacklistEnabled = false;
		assertTrue(TerritoryLossTracker.processMessage(line, config), "Must play sound when blacklist is disabled");
	}

	@Test
	void testNonBlacklistedTerritoryPlaysSound() {
		String line = "[pots] has taken control of Detlas!";
		assertTrue(TerritoryLossTracker.processMessage(line, config), "Non-blacklisted territory must trigger sound");
	}

	@Test
	void testTagFormatsAndLengths() {
		// 3-letter tag
		assertTrue(TerritoryLossTracker.processMessage("[ABC] has taken control of Detlas!", config));
		// 4-letter tag
		assertTrue(TerritoryLossTracker.processMessage("[WXYZ] has taken control of Detlas!", config));
		// Lowercase tag
		assertTrue(TerritoryLossTracker.processMessage("[pots] has taken control of Detlas!", config));
		// Uppercase tag
		assertTrue(TerritoryLossTracker.processMessage("[POTS] has taken control of Detlas!", config));
		// Mixed case tag
		assertTrue(TerritoryLossTracker.processMessage("[PoTs] has taken control of Detlas!", config));
		// Alphanumeric tag
		assertTrue(TerritoryLossTracker.processMessage("[123] has taken control of Detlas!", config));

		// Too short tag (2 letters)
		assertFalse(TerritoryLossTracker.processMessage("[AB] has taken control of Detlas!", config));
		// Too long tag (5 letters)
		assertFalse(TerritoryLossTracker.processMessage("[ABCDE] has taken control of Detlas!", config));
	}

	@Test
	void testAllDefaultBlacklistedTerritories() {
		List<String> defaultBlacklist = List.of("Timeworn Arch", "Pine Pillar Forest", "Evergreen Outbreak", "Burning Farm", "Waterfall Cave", "Paper Trail", "Big Mushroom Cave", "Collapsed Bridge", "Mycelial Expanse", "Dodegar's Forge", "Unicorn Trail", "Guardian of the Forest", "Heavenly Ingress", "Luminous Plateau", "Primal Fen", "Field of Life", "Path to Light", "Otherworldly Monolith", "Azure Frontier", "Nexus of Light");

		for (String territory : defaultBlacklist) {
			String message = "[pots] has taken control of " + territory + "!";
			assertFalse(TerritoryLossTracker.processMessage(message, config), territory + " should be blacklisted");
		}
	}

	@Test
	void testCaseInsensitiveBlacklistMatching() {
		assertTrue(TerritoryLossTracker.isBlacklisted("guardian of the forest", config));
		assertTrue(TerritoryLossTracker.isBlacklisted("GUARDIAN OF THE FOREST", config));
		assertTrue(TerritoryLossTracker.isBlacklisted("  Guardian of the Forest  ", config));
		assertFalse(TerritoryLossTracker.isBlacklisted("Detlas", config));
	}

	@Test
	void testFeatureMasterToggle() {
		config.territoryLossSound = false;
		assertFalse(TerritoryLossTracker.processMessage("[pots] has taken control of Detlas!", config));
		assertFalse(TerritoryLossTracker.processMessage("[pots] has taken control of Guardian of the Forest!", config));
	}

	@Test
	void testEdenCapturesIgnored() {
		assertFalse(TerritoryLossTracker.processMessage("[EDN] has taken control of Detlas!", config));
		assertFalse(TerritoryLossTracker.processMessage("[Eden] has taken control of Detlas!", config));
		assertFalse(TerritoryLossTracker.processMessage("[eden] has taken control of Detlas!", config));
		assertFalse(TerritoryLossTracker.processMessage("[EDEN] has taken control of Detlas!", config));
	}

	@Test
	void testPunctuationVariations() {
		assertTrue(TerritoryLossTracker.processMessage("[pots] has taken control of Detlas!", config));
		assertTrue(TerritoryLossTracker.processMessage("[pots] has taken control of Detlas", config));
		assertTrue(TerritoryLossTracker.processMessage("[pots] has taken control of Detlas.", config));
		assertTrue(TerritoryLossTracker.processMessage("[pots] has taken control of Detlas!!!", config));
	}

	@Test
	void testColorCodesAndPrivateUseGlyphs() {
		String colored = "§7[§apots§7] has taken control of §bDetlas!";
		assertTrue(TerritoryLossTracker.processMessage(colored, config));

		String glyphPadded = "\uE001[pots] has taken control of Detlas!\uE002";
		assertTrue(TerritoryLossTracker.processMessage(glyphPadded, config));
	}

	@Test
	void testNonCaptureAndChatMessagesIgnored() {
		assertFalse(TerritoryLossTracker.processMessage("[Guild] Player: [pots] has taken control of Detlas!", config));
		assertFalse(TerritoryLossTracker.processMessage("[Allies] Ally: [pots] has taken control of Detlas!", config));
		assertFalse(TerritoryLossTracker.processMessage("[Party] Member: [pots] has taken control of Detlas!", config));
		assertFalse(TerritoryLossTracker.processMessage("You have taken control of Detlas from [pots]!", config));
		assertFalse(TerritoryLossTracker.processMessage("Your guild has successfully defended Detlas.", config));
		assertFalse(TerritoryLossTracker.processMessage("[pots] has lost the war!", config));
		assertFalse(TerritoryLossTracker.processMessage(null, config));
		assertFalse(TerritoryLossTracker.processMessage("", config));
	}
}
