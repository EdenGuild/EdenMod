package tel.eden.mod.gui;

/**
 * Marker interface for modal screens shown while background server container automation
 * runs (e.g. {@code /eden dump}, {@code /eden gift}, and ally management commands).
 *
 * <p>These screens hold the player while the run finishes, so a container being closed under them
 * must not take them down with it. They share the EdenMod backdrop like every other screen.
 */
public interface BackgroundProgressScreen extends EdenBackdrop {
}
