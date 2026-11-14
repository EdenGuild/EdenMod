package tel.eden.mod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.ErrorScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import tel.eden.mod.EdenLogger;

/**
 * Keeps a background-run progress screen up for as long as its run lasts.
 *
 * <p>These screens exist to hold the player still while the run drives the guild menus behind them.
 * Anything else that switches screens would take them down and leave the player free to move mid-run:
 * a world teleporter or a server-opened menu, Wynncraft closing a container, a world change finishing
 * its loading screen, a mod. While a screen is held, {@code MinecraftMixin} turns such a switch into
 * "stay on the progress screen"; only loading, connection and death screens, which the game cannot do
 * without, are let through, and the held screen comes back when they are done.
 *
 * <p>A screen holds itself when shown and is released when its run finishes. A hold also ends on its own
 * after {@link #MAX_HOLD_MS}, so a run that never reports finishing cannot lock the player in for good.
 */
public final class ProgressScreenGuard {
	private ProgressScreenGuard() {
	}

	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final long MAX_HOLD_MS = 10 * 60_000L;
	private static final int MAX_LOGGED = 10;

	private static volatile Screen held;
	private static volatile long heldSince;
	private static int logged;

	static void hold(Screen screen) {
		held = screen;
		heldSince = System.currentTimeMillis();
		logged = 0;
	}

	static void release(Screen screen) {
		if (held == screen) {
			held = null;
		}
	}

	/** Lets go of any held screen; for tests, which open and close these screens without a run behind them. */
	public static void releaseAll() {
		held = null;
	}

	/** The screen to show instead of {@code requested}, or null if the switch should go ahead. */
	public static Screen replacementFor(Minecraft mc, Screen requested) {
		Screen current = held;
		if (current == null || requested == current || mc.player == null || mc.level == null) {
			return null;
		}
		if (System.currentTimeMillis() - heldSince > MAX_HOLD_MS) {
			LOGGER.warn("Progress screen '{}' was still up after {} minutes; letting it go", current.getTitle().getString(), MAX_HOLD_MS / 60_000L);
			held = null;
			return null;
		}
		if (isTransition(requested)) {
			return null;
		}
		if (logged++ < MAX_LOGGED) {
			LOGGER.info("Kept '{}' up; ignored a switch to {}", current.getTitle().getString(), requested == null ? "no screen" : requested.getClass().getSimpleName());
		}
		return current;
	}

	private static boolean isTransition(Screen screen) {
		return screen instanceof LevelLoadingScreen || screen instanceof ConnectScreen || screen instanceof ProgressScreen || screen instanceof GenericMessageScreen || screen instanceof DisconnectedScreen || screen instanceof ErrorScreen || screen instanceof TitleScreen || screen instanceof DeathScreen;
	}
}
