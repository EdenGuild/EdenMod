package tel.eden.mod.party;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class PartyHealthBarRendererTest {

	@Test
	public void disabledSettingReturnsOne() {
		// Even at 100 blocks away, if slider is 0 (off), scale is 1.0
		float scale = PartyHealthBarRenderer.computeDistanceScale(100.0 * 100.0, 0);
		assertEquals(1.0f, scale, 0.001f);
	}

	@Test
	public void closeDistanceReturnsOne() {
		// Within 20 blocks, scale is always 1.0
		float scaleAt5m = PartyHealthBarRenderer.computeDistanceScale(5.0 * 5.0, 100);
		float scaleAt20m = PartyHealthBarRenderer.computeDistanceScale(20.0 * 20.0, 100);
		assertEquals(1.0f, scaleAt5m, 0.001f);
		assertEquals(1.0f, scaleAt20m, 0.001f);
	}

	@Test
	public void farDistanceScalesAtFullIntensity() {
		// At 40 blocks (excess = 20), +2.5% per block -> +50% scale (1.5x)
		float scaleAt40m = PartyHealthBarRenderer.computeDistanceScale(40.0 * 40.0, 100);
		assertEquals(1.5f, scaleAt40m, 0.01f);

		// At 60 blocks (excess = 40), +2.5% per block -> +100% scale (2.0x)
		float scaleAt60m = PartyHealthBarRenderer.computeDistanceScale(60.0 * 60.0, 100);
		assertEquals(2.0f, scaleAt60m, 0.01f);
	}

	@Test
	public void halfIntensityHalvesTheBoost() {
		// At 60 blocks (excess = 40) with 50% slider -> +50% scale (1.5x)
		float scaleAt60m = PartyHealthBarRenderer.computeDistanceScale(60.0 * 60.0, 50);
		assertEquals(1.5f, scaleAt60m, 0.01f);
	}

	@Test
	public void doubleIntensityDoublesTheBoost() {
		// At 60 blocks (excess = 40) with 200% slider -> +200% scale (3.0x)
		float scaleAt60m = PartyHealthBarRenderer.computeDistanceScale(60.0 * 60.0, 200);
		assertEquals(3.0f, scaleAt60m, 0.01f);
	}

	@Test
	public void extremeDistanceIsCapped() {
		// At 200 blocks, max boost is capped at +2.5x -> 3.5x total
		float scaleAt200m = PartyHealthBarRenderer.computeDistanceScale(200.0 * 200.0, 100);
		assertEquals(3.5f, scaleAt200m, 0.01f);
	}
}
