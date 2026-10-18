package tel.eden.mod.war.eco;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class TerritoryScannerTest {

	@Test
	public void testParseTerritoryFromUpgradeTitle() {
		// Clean ASCII
		assertEquals("Cinfras Outskirts", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("Cinfras Outskirts: Upgrades"));
		// With Wynncraft custom font glyphs (e.g. \u00C0 / À)
		assertEquals("Cinfras Outskirts", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("\u00C0Cinfras Outskirts: Upgrades"));
		assertEquals("Thanos", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("\u00C0[!] Thanos: Upgrades"));
		// With trailing (HQ)
		assertEquals("Cinfras Outskirts", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("\u00C0Cinfras Outskirts (HQ): Upgrades"));
		// With Bonuses title
		assertEquals("Cinfras Outskirts", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("\u00C0Cinfras Outskirts: Bonuses"));
		// Clean territory overview title (no colon)
		assertEquals("Path to Talor", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("Path to Talor"));
		assertEquals("Dark Forest Village", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("Dark Forest Village"));

		// Submenu titles without colons
		assertEquals("Twisted Ridge", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("Twisted Ridge Guild Tower"));
		assertEquals("Twisted Ridge", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("Twisted Ridge Bonus"));
		assertEquals("Cinfras Outskirts", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("\u00C0Cinfras Outskirts (HQ) Guild Tower"));
		assertEquals("Mesquis Tower", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("Mesquis Tower Guild Tower"));
		assertEquals("Mesquis Tower", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("Mesquis Tower Bonus"));
		assertEquals("Mesquis Tower", TerritoryContainerScanner.parseTerritoryFromUpgradeTitle("Mesquis Tower"));
	}

	@Test
	public void testFakeMediumAndHighAbsoluteMatching() {
		// Exact Fake Medium
		TerritoryPreset.TerritoryLoadout exactMed = new TerritoryPreset.TerritoryLoadout(2, 2, 3, 2, Map.of("Tower Aura", 1, "Tower Volley", 1));
		assertTrue(TerritoryGuideController.isFakeMedium(exactMed));

		// Fake Medium stats with extra economy bonus -> MUST NOT match Fake Medium
		TerritoryPreset.TerritoryLoadout medWithEco = new TerritoryPreset.TerritoryLoadout(2, 2, 3, 2, Map.of("Tower Aura", 1, "Tower Volley", 1, "Larger Resource Storage", 2));
		assertFalse(TerritoryGuideController.isFakeMedium(medWithEco));

		// Exact Fake High
		TerritoryPreset.TerritoryLoadout exactHigh = new TerritoryPreset.TerritoryLoadout(5, 4, 5, 5, Map.of("Tower Aura", 1, "Tower Volley", 3));
		assertTrue(TerritoryGuideController.isFakeHigh(exactHigh));

		// Fake High stats with extra bonus -> MUST NOT match Fake High
		TerritoryPreset.TerritoryLoadout highWithEco = new TerritoryPreset.TerritoryLoadout(5, 4, 5, 5, Map.of("Tower Aura", 1, "Tower Volley", 3, "Emerald Rate", 3));
		assertFalse(TerritoryGuideController.isFakeHigh(highWithEco));
	}

	@Test
	public void testBidirectionalRemainingClicks() {
		TerritoryPreset.TerritoryLoadout target = new TerritoryPreset.TerritoryLoadout(2, 2, 3, 2, Map.of("Tower Aura", 1));

		// Current has higher damage (needs right-click downgrade from 7 to 2 = 5 clicks)
		TerritoryPreset.TerritoryLoadout overLeveled = new TerritoryPreset.TerritoryLoadout(7, 2, 3, 2, Map.of("Tower Aura", 1));
		assertEquals(5, TerritoryGuideController.remainingClicks(overLeveled, target));

		// Current has unwanted bonus (needs right-click downgrade from 3 to 0 = 3 clicks)
		TerritoryPreset.TerritoryLoadout extraBonus = new TerritoryPreset.TerritoryLoadout(2, 2, 3, 2, Map.of("Tower Aura", 1, "Tome Seeking", 3));
		assertEquals(3, TerritoryGuideController.remainingClicks(extraBonus, target));

		// Both upgrade and downgrade needed
		TerritoryPreset.TerritoryLoadout mixed = new TerritoryPreset.TerritoryLoadout(7, 1, 3, 2, Map.of("Tower Aura", 1, "Tome Seeking", 2));
		// Damage -5, Attack +1, Tome Seeking -2 -> 5 + 1 + 2 = 8
		assertEquals(8, TerritoryGuideController.remainingClicks(mixed, target));
	}

	@Test
	public void testOverviewRoutingLogic() {
		TerritoryPreset.TerritoryLoadout target = new TerritoryPreset.TerritoryLoadout(2, 2, 3, 2, Map.of("Tower Aura", 1, "Tower Volley", 1));
		TerritoryPreset.TerritoryLoadout currentZero = TerritoryPreset.TerritoryLoadout.ZERO;

		// When tower stats are incomplete -> needs tower
		boolean needTower = target.damage() > currentZero.damage() || target.attack() > currentZero.attack() || target.health() > currentZero.health() || target.defence() > currentZero.defence();
		assertTrue(needTower);

		// When tower stats are complete, but bonuses incomplete -> needs bonus
		TerritoryPreset.TerritoryLoadout currentTowerDone = new TerritoryPreset.TerritoryLoadout(2, 2, 3, 2, Map.of());
		boolean needTower2 = target.damage() > currentTowerDone.damage() || target.attack() > currentTowerDone.attack() || target.health() > currentTowerDone.health() || target.defence() > currentTowerDone.defence();
		assertFalse(needTower2);
		boolean needBonuses = false;
		for (Map.Entry<String, Integer> b : target.bonuses().entrySet()) {
			if (b.getValue() > currentTowerDone.bonus(b.getKey())) {
				needBonuses = true;
				break;
			}
		}
		assertTrue(needBonuses);

		// When both tower stats and bonuses complete -> all complete (Back)
		TerritoryPreset.TerritoryLoadout currentAllDone = new TerritoryPreset.TerritoryLoadout(2, 2, 3, 2, Map.of("Tower Aura", 1, "Tower Volley", 1));
		boolean needTower3 = target.damage() > currentAllDone.damage() || target.attack() > currentAllDone.attack() || target.health() > currentAllDone.health() || target.defence() > currentAllDone.defence();
		boolean needBonuses3 = false;
		for (Map.Entry<String, Integer> b : target.bonuses().entrySet()) {
			if (b.getValue() > currentAllDone.bonus(b.getKey())) {
				needBonuses3 = true;
				break;
			}
		}
		assertFalse(needTower3);
		assertFalse(needBonuses3);
	}

	@Test
	public void testCanonicalUpgradeName() {
		assertEquals("Damage", TerritoryContainerScanner.canonicalUpgradeName("Damage"));
		assertEquals("Damage", TerritoryContainerScanner.canonicalUpgradeName("\u00C0Damage"));
		assertEquals("Defence", TerritoryContainerScanner.canonicalUpgradeName("Defense"));
		assertEquals("Tower Aura", TerritoryContainerScanner.canonicalUpgradeName("Aura"));
		assertEquals("Tower Volley", TerritoryContainerScanner.canonicalUpgradeName("Tower Volley"));
		assertEquals("Stronger Minions", TerritoryContainerScanner.canonicalUpgradeName("Stronger Mobs"));
		assertEquals("Larger Resource Storage", TerritoryContainerScanner.canonicalUpgradeName("Resource Storage"));
		assertEquals("Efficient Resources", TerritoryContainerScanner.canonicalUpgradeName("Efficient Resources"));
		assertEquals("Tome Seeking", TerritoryContainerScanner.canonicalUpgradeName("Tome Seeking"));
	}

	@Test
	public void testRemainingClicksMatchingTarget() {
		TerritoryPreset.TerritoryLoadout current = new TerritoryPreset.TerritoryLoadout(8, 8, 8, 8, Map.of("Tower Aura", 2, "Tower Volley", 1));
		TerritoryPreset.TerritoryLoadout target = new TerritoryPreset.TerritoryLoadout(8, 8, 8, 8, Map.of("Tower Aura", 2, "Tower Volley", 1));
		assertEquals(0, TerritoryGuideController.remainingClicks(current, target));

		TerritoryPreset.TerritoryLoadout under = new TerritoryPreset.TerritoryLoadout(6, 8, 8, 7, Map.of("Tower Aura", 1, "Tower Volley", 1));
		assertEquals(2 + 1 + 1, TerritoryGuideController.remainingClicks(under, target));
	}

	@Test
	public void testPresetCodecRoundTrip() {
		Map<String, TerritoryPreset.TerritoryLoadout> map = Map.of("Detlas", TerritoryGuideController.FAKE_MEDIUM, "Ragni", TerritoryGuideController.FAKE_HIGH, "Almuj", new TerritoryPreset.TerritoryLoadout(8, 8, 8, 8, Map.of()));
		TerritoryPreset preset = new TerritoryPreset("WarPreset", "Test notes", Set.of("Detlas"), map);

		// Base64 roundtrip
		String encoded = TerritoryPresetCodec.encodeBase64(preset);
		assertTrue(encoded.startsWith("EDEN1:"));
		TerritoryPreset decoded = TerritoryPresetCodec.decodeBase64(encoded);
		assertEquals("WarPreset", decoded.name());
		assertEquals("Test notes", decoded.notes());
		assertTrue(decoded.isExcluded("Detlas"));
		assertFalse(decoded.isExcluded("Ragni"));
		assertEquals(3, decoded.territories().size());
		assertTrue(TerritoryGuideController.isFakeMedium(decoded.loadout("Detlas")));
		assertTrue(TerritoryGuideController.isFakeHigh(decoded.loadout("Ragni")));

		// Parse auto-detection
		TerritoryPreset parsed = TerritoryPresetCodec.parse(encoded);
		assertEquals("WarPreset", parsed.name());
	}

	@Test
	public void testGuideControllerPhases() {
		Map<String, TerritoryPreset.TerritoryLoadout> presetMap = Map.of("TerrA", TerritoryGuideController.FAKE_MEDIUM, "TerrB", TerritoryGuideController.FAKE_HIGH, "HQ", new TerritoryPreset.TerritoryLoadout(8, 8, 8, 8, Map.of()));
		TerritoryPreset preset = new TerritoryPreset("Phased", "", Set.of(), presetMap);
		TerritoryGuideController.setActivePreset(preset);

		// State: all at zero
		Map<String, TerritoryPreset.TerritoryLoadout> current = Map.of("TerrA", TerritoryPreset.TerritoryLoadout.ZERO, "TerrB", TerritoryPreset.TerritoryLoadout.ZERO, "HQ", TerritoryPreset.TerritoryLoadout.ZERO);

		// Phase 1: Fake Medium needed -> instruct CLICK_LOADOUTS_BUTTON
		TerritoryGuideController.GuideStep step1 = TerritoryGuideController.evaluate(current, Set.of(), "Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, step1.action());
		assertEquals(List.of("TerrA"), step1.targetTerritories());

		// Phase 1 in Selection screen with TerrA unselected
		TerritoryGuideController.GuideStep stepSelect = TerritoryGuideController.evaluate(current, Set.of(), "Select Territories", true);
		assertEquals(TerritoryGuideController.ActionType.SELECT_TERRITORIES, stepSelect.action());

		// Phase 1 in Selection screen with TerrA selected -> DOUBLE_CLICK_APPLY_BUTTON
		TerritoryGuideController.GuideStep stepApply = TerritoryGuideController.evaluate(current, Set.of("TerrA"), "Select Territories", true);
		assertEquals(TerritoryGuideController.ActionType.DOUBLE_CLICK_APPLY_BUTTON, stepApply.action());

		// State: TerrA has Fake Medium, TerrB at zero
		Map<String, TerritoryPreset.TerritoryLoadout> current2 = Map.of("TerrA", TerritoryGuideController.FAKE_MEDIUM, "TerrB", TerritoryPreset.TerritoryLoadout.ZERO, "HQ", TerritoryPreset.TerritoryLoadout.ZERO);

		// Phase 2: Fake High needed
		TerritoryGuideController.GuideStep step2 = TerritoryGuideController.evaluate(current2, Set.of(), "Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, step2.action());
		assertEquals(List.of("TerrB"), step2.targetTerritories());

		// State: TerrA has Fake Medium, TerrB has Fake High, HQ at zero
		Map<String, TerritoryPreset.TerritoryLoadout> current3 = Map.of("TerrA", TerritoryGuideController.FAKE_MEDIUM, "TerrB", TerritoryGuideController.FAKE_HIGH, "HQ", TerritoryPreset.TerritoryLoadout.ZERO);

		// Phase 3: Individual territory HQ
		TerritoryGuideController.GuideStep step3 = TerritoryGuideController.evaluate(current3, Set.of(), "Territories", false);
		assertEquals(TerritoryGuideController.ActionType.OPEN_TERRITORY, step3.action());
		assertEquals(List.of("HQ"), step3.targetTerritories());

		// State: all complete
		Map<String, TerritoryPreset.TerritoryLoadout> currentComplete = Map.of("TerrA", TerritoryGuideController.FAKE_MEDIUM, "TerrB", TerritoryGuideController.FAKE_HIGH, "HQ", new TerritoryPreset.TerritoryLoadout(8, 8, 8, 8, Map.of()));
		TerritoryGuideController.GuideStep stepDone = TerritoryGuideController.evaluate(currentComplete, Set.of(), "Territories", false);
		assertEquals(TerritoryGuideController.ActionType.ALL_COMPLETE, stepDone.action());
	}

	@Test
	public void testUnselectAndPresetManagement() {
		TerritoryPreset preset = new TerritoryPreset("TestPreset", "", Set.of(), Map.of("Detlas", TerritoryGuideController.FAKE_MEDIUM));
		TerritoryGuideController.setActivePreset(preset);
		assertTrue(TerritoryGuideController.hasActivePreset());
		assertEquals("TestPreset", TerritoryGuideController.activePreset().name());

		TerritoryGuideController.unselectPreset();
		assertFalse(TerritoryGuideController.hasActivePreset());
		assertEquals("None", TerritoryGuideController.activePreset().name());

		TerritoryGuideController.GuideStep step = TerritoryGuideController.evaluate(Map.of("Detlas", TerritoryPreset.TerritoryLoadout.ZERO), Set.of(), "Territories", false);
		assertEquals(TerritoryGuideController.ActionType.NONE, step.action());
		assertTrue(step.targetTerritories().isEmpty());
	}

	@Test
	public void testBatchLoadoutSelectionPreservesTargetsWhenSlotPreviewsNewLore() {
		Map<String, TerritoryPreset.TerritoryLoadout> presetMap = new java.util.LinkedHashMap<>();
		presetMap.put("Med1", TerritoryGuideController.FAKE_MEDIUM);
		presetMap.put("Med2", TerritoryGuideController.FAKE_MEDIUM);
		presetMap.put("High1", TerritoryGuideController.FAKE_HIGH);
		presetMap.put("High2", TerritoryGuideController.FAKE_HIGH);
		TerritoryGuideController.setActivePreset(new TerritoryPreset("BatchTest", "", Set.of(), presetMap));

		Map<String, TerritoryPreset.TerritoryLoadout> initial = Map.of("Med1", TerritoryPreset.TerritoryLoadout.ZERO, "Med2", TerritoryPreset.TerritoryLoadout.ZERO, "High1", TerritoryPreset.TerritoryLoadout.ZERO, "High2", TerritoryPreset.TerritoryLoadout.ZERO);

		// 1. On Eden: Territories -> prompts CLICK_LOADOUTS_BUTTON for both Med1 and Med2
		TerritoryGuideController.GuideStep s1 = TerritoryGuideController.evaluate(initial, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, s1.action());
		assertEquals(Set.of("Med1", "Med2"), Set.copyOf(s1.targetTerritories()));

		// 2. On Eden's Territory Loadouts (empty live map) -> prompts CLICK_FAKE_MEDIUM_OPTION
		TerritoryGuideController.GuideStep s2 = TerritoryGuideController.evaluate(Map.of(), Set.of(), "Eden's Territory Loadouts", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_FAKE_MEDIUM_OPTION, s2.action());

		// 3. In Select Territories: user clicks Med1, and Wynncraft previews FAKE_MEDIUM lore on Med1's item
		Map<String, TerritoryPreset.TerritoryLoadout> afterClickMed1 = Map.of("Med1", TerritoryGuideController.FAKE_MEDIUM, "Med2", TerritoryPreset.TerritoryLoadout.ZERO, "High1", TerritoryPreset.TerritoryLoadout.ZERO, "High2", TerritoryPreset.TerritoryLoadout.ZERO);
		TerritoryGuideController.GuideStep s3 = TerritoryGuideController.evaluate(afterClickMed1, Set.of("Med1"), "Select Territories", true);
		assertEquals(TerritoryGuideController.ActionType.SELECT_TERRITORIES, s3.action());
		assertEquals(List.of("Med2"), s3.targetTerritories());
		assertEquals(2, s3.totalCount());
		assertEquals(1, s3.completedCount());

		// 4. User clicks Med2 as well (both preview FAKE_MEDIUM in Select Territories) -> must prompt DOUBLE_CLICK_APPLY_BUTTON
		Map<String, TerritoryPreset.TerritoryLoadout> afterClickBoth = Map.of("Med1", TerritoryGuideController.FAKE_MEDIUM, "Med2", TerritoryGuideController.FAKE_MEDIUM, "High1", TerritoryPreset.TerritoryLoadout.ZERO, "High2", TerritoryPreset.TerritoryLoadout.ZERO);
		TerritoryGuideController.GuideStep s4 = TerritoryGuideController.evaluate(afterClickBoth, Set.of("Med1", "Med2"), "Select Territories", true);
		assertEquals(TerritoryGuideController.ActionType.DOUBLE_CLICK_APPLY_BUTTON, s4.action());
		assertEquals(2, s4.totalCount());
		assertEquals(2, s4.completedCount());

		// 5. Return to Eden: Territories (even if only Page 1 loaded so far) -> moves to Fake High batch
		Map<String, TerritoryPreset.TerritoryLoadout> page1AfterApply = Map.of("Med1", TerritoryGuideController.FAKE_MEDIUM, "High1", TerritoryPreset.TerritoryLoadout.ZERO);
		TerritoryGuideController.GuideStep s5 = TerritoryGuideController.evaluate(page1AfterApply, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, s5.action());
		assertEquals(Set.of("High1", "High2"), Set.copyOf(s5.targetTerritories()));

		// 6. On Eden's Territory Loadouts -> now prompts CLICK_FAKE_HIGH_OPTION
		TerritoryGuideController.GuideStep s6 = TerritoryGuideController.evaluate(Map.of(), Set.of(), "Eden's Territory Loadouts", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_FAKE_HIGH_OPTION, s6.action());
	}

	@Test
	public void testPresetTrailingNameEncodingAndDefaults() {
		TerritoryPreset preset = new TerritoryPreset("My Preset 1", "Notes", Set.of(), Map.of("Detlas", TerritoryGuideController.FAKE_MEDIUM));
		String encoded = TerritoryPresetCodec.encodeBase64(preset);
		// Trailing name format: EDEN1:<base64>:My Preset 1
		assertTrue(encoded.startsWith("EDEN1:"));
		assertTrue(encoded.endsWith(":My Preset 1"));

		// Decoding parses trailing name
		TerritoryPreset decoded = TerritoryPresetCodec.decodeBase64(encoded);
		assertEquals("My Preset 1", decoded.name());

		// When trailing name is empty or blank -> defaults to "Default"
		String emptyTrailing = encoded.substring(0, encoded.lastIndexOf(':') + 1) + "   ";
		TerritoryPreset decodedEmpty = TerritoryPresetCodec.decodeBase64(emptyTrailing);
		assertEquals("Default", decodedEmpty.name());

		// Backwards compatibility: decode legacy preset without trailing colon
		String legacy = encoded.substring(0, encoded.lastIndexOf(':'));
		TerritoryPreset decodedLegacy = TerritoryPresetCodec.decodeBase64(legacy);
		assertEquals("My Preset 1", decodedLegacy.name());
	}

	@Test
	public void testSkipCurrentInstruction() {
		Map<String, TerritoryPreset.TerritoryLoadout> presetMap = new java.util.LinkedHashMap<>();
		presetMap.put("Med1", TerritoryGuideController.FAKE_MEDIUM);
		presetMap.put("High1", TerritoryGuideController.FAKE_HIGH);
		presetMap.put("Custom1", new TerritoryPreset.TerritoryLoadout(8, 8, 8, 8, Map.of()));
		presetMap.put("Custom2", new TerritoryPreset.TerritoryLoadout(7, 7, 7, 7, Map.of()));
		TerritoryGuideController.setActivePreset(new TerritoryPreset("SkipTest", "", Set.of(), presetMap));

		Map<String, TerritoryPreset.TerritoryLoadout> current = Map.of("Med1", TerritoryPreset.TerritoryLoadout.ZERO, "High1", TerritoryPreset.TerritoryLoadout.ZERO, "Custom1", TerritoryPreset.TerritoryLoadout.ZERO, "Custom2", TerritoryPreset.TerritoryLoadout.ZERO);

		// Step 1: Fake Medium is evaluated
		TerritoryGuideController.GuideStep s1 = TerritoryGuideController.evaluate(current, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, s1.action());
		assertEquals(List.of("Med1"), s1.targetTerritories());

		// Skip Fake Medium instruction
		TerritoryGuideController.skipCurrentInstruction();

		// Step 2: Now advances to Fake High
		TerritoryGuideController.GuideStep s2 = TerritoryGuideController.evaluate(current, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, s2.action());
		assertEquals(List.of("High1"), s2.targetTerritories());

		// Skip Fake High instruction
		TerritoryGuideController.skipCurrentInstruction();

		// Step 3: Now advances to Phase 3 Custom1
		TerritoryGuideController.GuideStep s3 = TerritoryGuideController.evaluate(current, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.OPEN_TERRITORY, s3.action());
		assertEquals(List.of("Custom1"), s3.targetTerritories());

		// Skip Custom1
		TerritoryGuideController.skipCurrentInstruction();

		// Step 4: Now advances to Custom2
		TerritoryGuideController.GuideStep s4 = TerritoryGuideController.evaluate(current, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.OPEN_TERRITORY, s4.action());
		assertEquals(List.of("Custom2"), s4.targetTerritories());

		// Skip Custom2 -> All skipped, so instructions loop back to Fake Medium!
		TerritoryGuideController.skipForward();
		TerritoryGuideController.GuideStep sLoop = TerritoryGuideController.evaluate(current, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, sLoop.action());
		assertEquals(List.of("Med1"), sLoop.targetTerritories());

		// Test skipBackwards:
		// Going backwards from Fake Medium loops to Custom2
		TerritoryGuideController.skipBackwards();
		TerritoryGuideController.GuideStep sBack1 = TerritoryGuideController.evaluate(current, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.OPEN_TERRITORY, sBack1.action());
		assertEquals(List.of("Custom2"), sBack1.targetTerritories());

		// Going backwards from Custom2 returns to Custom1
		TerritoryGuideController.skipBackwards();
		TerritoryGuideController.GuideStep sBack2 = TerritoryGuideController.evaluate(current, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.OPEN_TERRITORY, sBack2.action());
		assertEquals(List.of("Custom1"), sBack2.targetTerritories());

		// Going backwards from Custom1 returns to Fake High
		TerritoryGuideController.skipBackwards();
		TerritoryGuideController.GuideStep sBack3 = TerritoryGuideController.evaluate(current, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, sBack3.action());
		assertEquals(List.of("High1"), sBack3.targetTerritories());

		// Going backwards from Fake High returns to Fake Medium
		TerritoryGuideController.skipBackwards();
		TerritoryGuideController.GuideStep sBack4 = TerritoryGuideController.evaluate(current, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, sBack4.action());
		assertEquals(List.of("Med1"), sBack4.targetTerritories());
	}

	@Test
	public void testApplyLoadoutReturnsToLoadoutContainerInstructsBack() {
		Map<String, TerritoryPreset.TerritoryLoadout> presetMap = Map.of("Terr1", TerritoryGuideController.FAKE_MEDIUM, "Terr2", TerritoryGuideController.FAKE_MEDIUM);
		TerritoryGuideController.setActivePreset(new TerritoryPreset("LoadoutReturnTest", "", Set.of(), presetMap));

		Map<String, TerritoryPreset.TerritoryLoadout> initial = Map.of("Terr1", TerritoryPreset.TerritoryLoadout.ZERO, "Terr2", TerritoryPreset.TerritoryLoadout.ZERO);

		// 1. Initial screen: Eden: Territories -> prompts CLICK_LOADOUTS_BUTTON
		TerritoryGuideController.GuideStep step1 = TerritoryGuideController.evaluate(initial, Set.of(), "Eden: Territories", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_LOADOUTS_BUTTON, step1.action());

		// 2. Open loadouts container: Eden's Territory Loadouts -> prompts CLICK_FAKE_MEDIUM_OPTION
		TerritoryGuideController.GuideStep step2 = TerritoryGuideController.evaluate(Map.of(), Set.of(), "Eden's Territory Loadouts", false);
		assertEquals(TerritoryGuideController.ActionType.CLICK_FAKE_MEDIUM_OPTION, step2.action());

		// 3. User selects both territories in Select Territories -> prompts DOUBLE_CLICK_APPLY_BUTTON
		TerritoryGuideController.GuideStep step3 = TerritoryGuideController.evaluate(initial, Set.of("Terr1", "Terr2"), "Select Territories", true);
		assertEquals(TerritoryGuideController.ActionType.DOUBLE_CLICK_APPLY_BUTTON, step3.action());

		// 4. User applies; Wynncraft loads the player back into Eden's Territory Loadouts
		// Pending batch must be committed to sessionLoadouts, NOT prompt CLICK_FAKE_MEDIUM_OPTION again!
		TerritoryGuideController.GuideStep step4 = TerritoryGuideController.evaluate(Map.of(), Set.of(), "Eden's Territory Loadouts", false);
		assertFalse(step4.action() == TerritoryGuideController.ActionType.CLICK_FAKE_MEDIUM_OPTION, "Should not re-prompt Fake Medium after applying!");
		assertEquals(TerritoryGuideController.ActionType.ALL_COMPLETE, step4.action());
	}

	@Test
	public void testPresetSecurityLimits() throws Exception {
		// 1. Rejects codes > 64 KB (MAX_INPUT_CHARS)
		String hugeCode = TerritoryPresetCodec.PREFIX + "A".repeat(TerritoryPresetCodec.MAX_INPUT_CHARS + 10);
		assertThrows(IllegalArgumentException.class, () -> TerritoryPresetCodec.parse(hugeCode));

		// 2. Rejects zip bomb expanding to > 1 MB
		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
		try (java.util.zip.DeflaterOutputStream dos = new java.util.zip.DeflaterOutputStream(baos, deflater); java.io.DataOutputStream out = new java.io.DataOutputStream(dos)) {
			out.writeByte(1);
			out.writeUTF("Bomb");
			byte[] payload = new byte[1_200_000]; // 1.2 MB > 1 MB limit
			java.util.Arrays.fill(payload, (byte) 'A');
			out.writeInt(payload.length);
			out.write(payload);
		} finally {
			deflater.end();
		}
		String bombCode = TerritoryPresetCodec.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(baos.toByteArray());
		assertThrows(IllegalArgumentException.class, () -> TerritoryPresetCodec.decodeBase64(bombCode));

		// 3. Rejects presets with > 500 territories
		java.io.ByteArrayOutputStream baos2 = new java.io.ByteArrayOutputStream();
		java.util.zip.Deflater deflater2 = new java.util.zip.Deflater();
		try (java.util.zip.DeflaterOutputStream dos = new java.util.zip.DeflaterOutputStream(baos2, deflater2); java.io.DataOutputStream out = new java.io.DataOutputStream(dos)) {
			out.writeByte(1);
			out.writeUTF("TooMany");
			out.writeUTF("");
			out.writeShort(0);
			out.writeShort(501); // 501 > 500 max
		} finally {
			deflater2.end();
		}
		String tooManyCode = TerritoryPresetCodec.PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(baos2.toByteArray());
		assertThrows(IllegalArgumentException.class, () -> TerritoryPresetCodec.decodeBase64(tooManyCode));
	}

	@Test
	public void testUniquePresetNaming() {
		Map<String, String> existing = new java.util.LinkedHashMap<>();
		existing.put("Default", "code1");
		existing.put("Claim Oct 18 12:37", "code2");
		existing.put("Claim Oct 18 12:37 (2)", "code3");

		// Non-conflicting name remains unchanged
		assertEquals("My Preset", TerritoryGuideController.getUniquePresetName(existing, "My Preset"));

		// Conflicting name appends (2)
		assertEquals("Default (2)", TerritoryGuideController.getUniquePresetName(existing, "Default"));

		// Case-insensitive conflict
		assertEquals("Default (2)", TerritoryGuideController.getUniquePresetName(existing, "default"));

		// Multiple conflicts increment counter
		assertEquals("Claim Oct 18 12:37 (3)", TerritoryGuideController.getUniquePresetName(existing, "Claim Oct 18 12:37"));
	}

	@Test
	public void testKnownTerritoryAndMenuDetection() {
		TerritoryPreset preset = new TerritoryPreset("Test", "", Set.of(), Map.of("Cinfras Outskirts", new TerritoryPreset.TerritoryLoadout(0, 0, 0, 0, Map.of()), "Mesquis Tower", new TerritoryPreset.TerritoryLoadout(0, 0, 0, 0, Map.of())));
		TerritoryGuideController.setActivePreset(preset);

		// Preset territories are known
		assertTrue(TerritoryGuideController.isKnownTerritory("Cinfras Outskirts"));
		assertTrue(TerritoryGuideController.isKnownTerritory("Mesquis Tower"));
		assertTrue(TerritoryGuideController.isKnownTerritory("mesquis tower"));

		// Clean territory title removes (HQ) and special chars
		assertEquals("Cinfras Outskirts", TerritoryContainerScanner.cleanTerritoryTitle("§f§lÀCinfras Outskirts (HQ)"));
		assertEquals("Mesquis Tower", TerritoryContainerScanner.cleanTerritoryTitle("Mesquis Tower"));

		// Unrelated menus and chests are NOT known territories
		assertFalse(TerritoryGuideController.isKnownTerritory("Chest"));
		assertFalse(TerritoryGuideController.isKnownTerritory("Large Chest"));
		assertFalse(TerritoryGuideController.isKnownTerritory("Bank"));
		assertFalse(TerritoryGuideController.isKnownTerritory("Daily Login"));
		assertFalse(TerritoryGuideController.isKnownTerritory("Bonus"));
		assertFalse(TerritoryGuideController.isKnownTerritory("Guild Tower"));
		assertFalse(TerritoryGuideController.isKnownTerritory(""));
		assertFalse(TerritoryGuideController.isKnownTerritory(null));
	}
}
