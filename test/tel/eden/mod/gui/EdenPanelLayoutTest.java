package tel.eden.mod.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EdenPanelLayoutTest {
	/** Base panel sizes of every screen laid out through {@link EdenReferenceScreen#centeredLayout}. */
	private static final int[][] BASE_PANELS = {{420, 360}, {420, 456}, {420, 300}, {320, 110}, {360, 170}, {360, 310}, {360, 320}, {350, 330}, {480, 350}};

	private static final int[][] WINDOWS = {{854, 480}, {1024, 576}, {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}, {3840, 2160}};

	/** Minecraft only offers a GUI scale if the window still fits 320x240 GUI units at that scale. */
	private static boolean validGuiScale(int windowWidth, int windowHeight, int guiScale) {
		return windowWidth / guiScale >= 320 && windowHeight / guiScale >= 240;
	}

	@Test
	void everyPanelFitsEveryValidWindowAndGuiScale() {
		for (int[] window : WINDOWS) {
			for (int guiScale = 1; guiScale <= 6; guiScale++) {
				if (!validGuiScale(window[0], window[1], guiScale)) {
					continue;
				}
				int screenWidth = window[0] / guiScale;
				int screenHeight = window[1] / guiScale;
				for (int[] base : BASE_PANELS) {
					float uiScale = EdenPanelLayout.fittingScaleFactor(screenWidth, screenHeight, base[0], base[1], guiScale);
					int virtualWidth = Math.round(screenWidth / uiScale);
					int virtualHeight = Math.round(screenHeight / uiScale);
					EdenPanelLayout layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, base[0], base[1]);
					String where = base[0] + "x" + base[1] + " panel in a " + window[0] + "x" + window[1] + " window at GUI scale " + guiScale;
					assertTrue(layout.x(0) >= 0 && layout.y(0) >= 0, where + " starts off screen");
					assertTrue(layout.x(0) + layout.panelWidth() <= virtualWidth, where + " is wider than the screen");
					assertTrue(layout.y(0) + layout.panelHeight() <= virtualHeight, where + " is taller than the screen");
					assertTrue(uiScale <= 1.0f, where + " is drawn larger than the GUI scale");
				}
			}
		}
	}

	@Test
	void textIsDrawnAtAWholeNumberOfScreenPixelsPerUnit() {
		for (int[] window : WINDOWS) {
			for (int guiScale = 1; guiScale <= 6; guiScale++) {
				if (!validGuiScale(window[0], window[1], guiScale)) {
					continue;
				}
				for (int[] base : BASE_PANELS) {
					float uiScale = EdenPanelLayout.fittingScaleFactor(window[0] / guiScale, window[1] / guiScale, base[0], base[1], guiScale);
					float pixelsPerUnit = uiScale * guiScale;
					assertEquals(Math.round(pixelsPerUnit), pixelsPerUnit, 1.0e-3, "blurry text: " + base[0] + "x" + base[1] + " panel in " + window[0] + "x" + window[1] + " at GUI scale " + guiScale + " draws " + pixelsPerUnit + " px per unit");
				}
			}
		}
	}

	@Test
	void screensThatFitAreNeverEnlargedPastTheGuiScale() {
		// 1080p at GUI scale 2 (960x540 units): a 300-unit panel fits with room to spare. It must stay
		// at the player's GUI scale rather than be blown up to fill the screen.
		assertEquals(1.0f, EdenPanelLayout.fittingScaleFactor(960, 540, 420, 300, 2), 1e-6);
	}

	@Test
	void screensThatDoNotFitShrinkAsAWhole() {
		// 1280x720 at GUI scale 3 (427x240 units) cannot fit the tall payout panel at full size.
		float uiScale = EdenPanelLayout.fittingScaleFactor(427, 240, 420, 456, 3);
		assertTrue(uiScale < 1.0f);
	}

	@Test
	void neverShrinksBelowTheReadableMinimum() {
		assertEquals(0.5f, EdenPanelLayout.fittingScaleFactor(200, 100, 420, 456, 1), 1e-6);
	}
}
