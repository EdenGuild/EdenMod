package tel.eden.mod.hud;

import net.minecraft.client.Minecraft;
import tel.eden.mod.config.BridgeConfig;

/**
 * Resolves a named HUD panel's top-left screen position from the saved config
 * fractions, falling back to a built-in default anchor. Positions are stored as
 * {@code [xFraction, yFraction]} (each 0-1 of the current screen size) so a panel
 * keeps its relative place across resolution and GUI-scale changes.
 */
public final class HudLayout {
	private HudLayout() {
	}

	private static float[] fractions(BridgeConfig config, String name, float defaultX, float defaultY) {
		float[] saved = config.hudPositions.get(name);
		if (saved != null && saved.length == 2) {
			return saved;
		}
		return new float[]{defaultX, defaultY};
	}

	/** Top-left X in scaled pixels for the named panel. */
	public static int x(BridgeConfig config, String name, float defaultX, float defaultY) {
		int screen = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		return Math.round(fractions(config, name, defaultX, defaultY)[0] * screen);
	}

	/** Top-left Y in scaled pixels for the named panel. */
	public static int y(BridgeConfig config, String name, float defaultX, float defaultY) {
		int screen = Minecraft.getInstance().getWindow().getGuiScaledHeight();
		return Math.round(fractions(config, name, defaultX, defaultY)[1] * screen);
	}

	/** Scale factor for the named panel (1.0 when unset), clamped to a sane range. */
	public static float scale(BridgeConfig config, String name) {
		Float saved = config.hudScales.get(name);
		if (saved == null) {
			return 1.0f;
		}
		return Math.max(0.5f, Math.min(3.0f, saved));
	}

	/** Store a panel's scale factor. */
	public static void setScale(BridgeConfig config, String name, float value) {
		config.hudScales.put(name, Math.max(0.5f, Math.min(3.0f, value)));
	}

	/** Store a panel's new position as fractions of the current screen size. */
	public static void save(BridgeConfig config, String name, int pixelX, int pixelY) {
		int width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int height = Minecraft.getInstance().getWindow().getGuiScaledHeight();
		if (width <= 0 || height <= 0) {
			return;
		}
		config.hudPositions.put(name, new float[]{(float) pixelX / width, (float) pixelY / height});
	}
}
