package tel.eden.mod.war.eco;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class TerritoryPresetDeduplicationTest {

	@Test
	void testTerritoryPresetCaseAndHqDeduplication() {
		Map<String, TerritoryPreset.TerritoryLoadout> loadouts = new HashMap<>();
		loadouts.put("cinfras outskirts", TerritoryPreset.TerritoryLoadout.ZERO);
		loadouts.put("Cinfras Outskirts (HQ)", TerritoryPreset.TerritoryLoadout.ZERO);
		loadouts.put("Thanos", TerritoryPreset.TerritoryLoadout.ZERO);
		loadouts.put("thanos", TerritoryPreset.TerritoryLoadout.ZERO);

		TerritoryPreset preset = new TerritoryPreset("Test Claim", "Notes", Set.of(), loadouts);
		assertEquals(2, preset.territories().size(), "Should deduplicate case differences and (HQ) suffix");
		assertTrue(preset.territories().containsKey("Cinfras Outskirts"), "Preferred title case key");
		assertTrue(preset.territories().containsKey("Thanos"), "Preferred title case key");
		assertFalse(preset.territories().containsKey("Cinfras Outskirts (HQ)"));
	}

	@Test
	void testCanonicalTerritoryName() {
		assertEquals("Cinfras Outskirts", TerritoryGuideController.canonicalTerritoryName("§f§lÀCinfras Outskirts (HQ)"));
		assertEquals("Cinfras Outskirts", TerritoryGuideController.canonicalTerritoryName("Cinfras Outskirts"));
		assertEquals("Thanos", TerritoryGuideController.canonicalTerritoryName("§4§l[§c§l!§4§l] §fThanos"));
	}
}
