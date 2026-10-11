package tel.eden.mod.gui;

/**
 * Marker interface for modal screens shown while background server container automation
 * runs (e.g. {@code /eden dump}, {@code /eden gift}, and ally management commands).
 *
 * <p>While one of these screens is active, background blur is lessened so the in-game
 * surroundings remain visible to the player during the automated operation.
 */
public interface BackgroundProgressScreen {
	/**
	 * Desired maximum blur radius while this screen is active.
	 * Default is 1 (subtle blur) instead of Minecraft's default 5-10.
	 */
	default int lessenedBlurRadius() {
		return 1;
	}
}
