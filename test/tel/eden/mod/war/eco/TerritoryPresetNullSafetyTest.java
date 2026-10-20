package tel.eden.mod.war.eco;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TerritoryPresetNullSafetyTest {
	@AfterEach
	void tearDown() {
		TerritoryGuideController.setActivePreset(null);
		TerritoryGuideController.reset();
	}

	@Test
	void jsonPresetWithNullsImportsAndGuidesWithoutThrowing() {
		// Shared JSON presets can carry nulls. A null bonus level used to be unboxed while the
		// guide rendered on the Wynntils territory screen, crashing the game every time it opened.
		String json = "{\"name\":\"x\",\"excludedTerritories\":[null,\"Ragni\"],\"territories\":{" + "\"Thanos\":{\"damage\":1,\"bonuses\":{\"Emerald Rate\":null,\"Tower Aura\":2}}," + "\"Detlas\":null}}";
		TerritoryPreset preset = TerritoryPresetCodec.parse(json);

		assertEquals(Set.of("Ragni"), preset.excludedTerritories());
		assertEquals(Set.of("Thanos"), preset.territories().keySet());
		assertEquals(0, preset.loadout("Thanos").bonus("Emerald Rate"));
		assertEquals(2, preset.loadout("Thanos").bonus("Tower Aura"));

		TerritoryGuideController.setActivePreset(preset);
		Map<String, TerritoryPreset.TerritoryLoadout> live = Map.of("Thanos", TerritoryPreset.TerritoryLoadout.ZERO, "Detlas", TerritoryPreset.TerritoryLoadout.ZERO);
		assertDoesNotThrow(() -> TerritoryGuideController.evaluate(live, Set.of(), "Territory Management", false));
		// Saving it (Copy, rename, duplicate names) re-encodes it, which must not throw either
		assertDoesNotThrow(() -> TerritoryPresetCodec.encodeBase64(preset));
	}
}
