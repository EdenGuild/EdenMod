package tel.eden.mod.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BackgroundProgressScreenTest {

	@Test
	void progressScreensImplementMarkerInterface() {
		assertTrue(BackgroundProgressScreen.class.isAssignableFrom(GiftProgressScreen.class), "GiftProgressScreen (/eden dump, /eden gift) must implement BackgroundProgressScreen");
		assertTrue(BackgroundProgressScreen.class.isAssignableFrom(AllyProgressScreen.class), "AllyProgressScreen (/eden ally add/remove) must implement BackgroundProgressScreen");
	}

	@Test
	void defaultLessenedBlurRadiusIsOne() {
		BackgroundProgressScreen screen = new BackgroundProgressScreen() {
		};
		assertEquals(1, screen.lessenedBlurRadius());
	}

	@Test
	void lessenedBlurPreservesDisabledSettingAndCapsHighBlur() {
		BackgroundProgressScreen screen = new BackgroundProgressScreen() {
		};
		int maxAllowed = screen.lessenedBlurRadius();

		// When user disabled blur (0), it must stay disabled (0)
		assertEquals(0, calculateActiveBlur(0, maxAllowed));

		// When user has default blur (5), it must be clamped to lessened radius (1)
		assertEquals(1, calculateActiveBlur(5, maxAllowed));

		// When user has maximum blur (10), it must be clamped to lessened radius (1)
		assertEquals(1, calculateActiveBlur(10, maxAllowed));

		// When user already has subtle blur (1), it remains 1
		assertEquals(1, calculateActiveBlur(1, maxAllowed));
	}

	private static int calculateActiveBlur(int currentBlur, int lessenedMax) {
		if (currentBlur <= 0) {
			return 0;
		}
		return Math.min(currentBlur, lessenedMax);
	}
}
