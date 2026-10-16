package tel.eden.mod.emote;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.EdenModClient;

/**
 * Radial "emote wheel" (avomod2 port). Pressing the keybind opens a wheel of your favorite
 * Wynncraft {@code /emote} animations; hovering a sector and clicking (or pressing 1-8)
 * plays it, and releasing the key closes the wheel. Favorites and the list of emotes you've
 * unlocked are stored in the mod config; {@link UnlockedEmoteDetector} keeps the unlocked
 * list current by reading Wynncraft's emotes menu.
 */
public final class EmoteWheel {
	private EmoteWheel() {
	}

	/** Number of slots around the wheel. */
	public static final int SLOTS = 8;

	private static KeyMapping key;

	public static KeyMapping key() {
		return key;
	}

	public static void register(KeyMapping.Category category) {
		key = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.edenmod.open_emote_wheel", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, category));
	}

	/** Opens the wheel on a fresh press; the screen itself closes on key release. */
	public static void onTick(Minecraft mc) {
		if (key == null) {
			return;
		}
		boolean armed = EdenModClient.instance().config().emoteWheelEnabled;
		while (key.consumeClick()) {
			if (armed && mc.screen == null && mc.player != null && mc.getConnection() != null) {
				mc.setScreen(new EmoteWheelScreen());
			}
		}
	}

	/** The 8 favorite emote names for the active page, padded with empty strings to a full wheel. */
	public static List<String> favorites() {
		EdenModClient client = EdenModClient.instance();
		tel.eden.mod.config.BridgeConfig config = client != null ? client.config() : null;
		if (config == null) {
			List<String> empty = new ArrayList<>();
			while (empty.size() < SLOTS) {
				empty.add("");
			}
			return empty;
		}
		ensurePages(config);
		return favoritesForPage(config.emoteWheelActivePage);
	}

	/** The 8 favorite emote names for the given page index, padded with empty strings to a full wheel. */
	public static List<String> favoritesForPage(int pageIndex) {
		EdenModClient client = EdenModClient.instance();
		tel.eden.mod.config.BridgeConfig config = client != null ? client.config() : null;
		if (config == null) {
			List<String> empty = new ArrayList<>();
			while (empty.size() < SLOTS) {
				empty.add("");
			}
			return empty;
		}
		ensurePages(config);
		int idx = Math.max(0, Math.min(pageIndex, config.emoteWheelPages.size() - 1));
		List<String> page = config.emoteWheelPages.get(idx);
		List<String> favorites = new ArrayList<>(page);
		while (favorites.size() < SLOTS) {
			favorites.add("");
		}
		return favorites.subList(0, SLOTS);
	}

	public static void ensurePages(tel.eden.mod.config.BridgeConfig config) {
		if (config == null) {
			return;
		}
		if (config.emoteWheelPages == null) {
			config.emoteWheelPages = new ArrayList<>();
		}
		if (config.emoteWheelPages.isEmpty()) {
			if (config.emoteWheelFavorites != null && !config.emoteWheelFavorites.isEmpty()) {
				config.emoteWheelPages.add(new ArrayList<>(config.emoteWheelFavorites));
			} else {
				config.emoteWheelPages.add(new ArrayList<>());
			}
		}
		if (config.emoteWheelActivePage < 0 || config.emoteWheelActivePage >= config.emoteWheelPages.size()) {
			config.emoteWheelActivePage = 0;
		}
		config.emoteWheelFavorites = new ArrayList<>(config.emoteWheelPages.get(config.emoteWheelActivePage));
	}
}
