package tel.eden.mod.gui;

/**
 * What every EdenMod screen looks like behind its widgets: the world stays recognisable under a light
 * blur and a light dim, so a screen opened mid-game doesn't hide what is going on around the player.
 *
 * <p>Vanilla's defaults are a heavy blur plus a dark gradient, and the screens had each drifted to a
 * different mix of the two. Implementing this (through {@link EdenScreen}) is what puts a screen on the
 * shared look; {@code OptionsMixin} caps the blur while one is showing.
 */
public interface EdenBackdrop {
	/** Dim laid over the whole screen, under the widgets. */
	int DIM = 0x66000000;

	/**
	 * The most blur this screen allows, whatever the player's menu blur setting is; a setting of zero
	 * stays zero. One is a subtle blur, against vanilla's default of five.
	 */
	default int lessenedBlurRadius() {
		return 1;
	}
}
