package tel.eden.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persisted client configuration, stored at {@code config/edenmod.json}.
 *
 * <p>
 * Holds the backend base URL the mod talks to, the current backend-signed JWT
 * (and its expiry, so we can re-auth before it lapses), and whether the bridge
 * is enabled.
 */
public final class BridgeConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("edenmod.json");

	/** The EdenBot backend URL, baked into the build. Never written to the config file. */
	public static final String DEFAULT_BACKEND_URL = "https://bridge.eden.tel/";

	/** Always {@link #DEFAULT_BACKEND_URL}; transient so it is never written to the config file. */
	public transient String backendBaseUrl = DEFAULT_BACKEND_URL;

	/** Backend-signed JWT obtained from the link flow; empty until linked. */
	public String jwt = "";

	/** Unix epoch seconds at which {@link #jwt} expires (0 when unknown). */
	public long jwtExpiresAt = 0L;

	/** Minecraft username that completed the link flow; empty if never linked or unknown. */
	public String linkedUsername = "";

	/** Whether the bridge should connect while on Wynncraft. */
	public boolean enabled = true;

	/**
	 * Whether your own login/logout is announced to the guild bridge (both in-game
	 * and in the Discord bridge chat). On by default; turn it off to keep your own
	 * comings and goings quiet. Other members' presence notices are unaffected, and
	 * you still appear in {@code /eden online} either way.
	 */
	public boolean announceSelfPresence = true;

	/**
	 * Whether open/full raid parties are auto-announced in chat with a clickable
	 * {@code [JOIN #id]} feed. When false, parties are only shown on demand via
	 * {@code /eden party list}. Toggle with {@code /eden party announce on|off}.
	 */
	public boolean partyAnnounce = true;

	public enum GameDisplayMode {
		ALL("Shown (All)"), NONE("Hidden (All)"), REACTIONS("Show Only Reactions");

		private final String name;

		GameDisplayMode(String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	@Deprecated
	public Boolean showGameMessages = null;

	public GameDisplayMode gameDisplayMode = GameDisplayMode.ALL;

	/**
	 * How much of the screen (as a percentage) an image preview can occupy.
	 * Range 1-100, default 40.
	 */
	public int imagePreviewSize = 40;

	/** Client-side aliases that rewrite typed server commands before they are sent. */
	public List<CommandAlias> commandAliases = new ArrayList<>();

	/** Client-side bindings that run commands from keyboard or mouse input. */
	public List<CommandKeybind> commandKeybinds = new ArrayList<>();

	/** Favorited chat emotes shown by the right-click picker when the star filter is enabled. */
	public List<String> favoriteEmotes = new ArrayList<>();

	public enum ChatEmoteToolsMode {
		UI("UI"), AUTO("Auto"), UI_AND_AUTO("UI & Auto"), NONE("None");

		private final String label;

		ChatEmoteToolsMode(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** Legacy toggle kept only to migrate older configs into {@link #chatEmoteToolsMode}. */
	@Deprecated
	public Boolean chatEmoteUiEnabled = null;

	/** Which chat emote tools are enabled: inline/picker UI, autocomplete, both, or none. */
	public ChatEmoteToolsMode chatEmoteToolsMode = ChatEmoteToolsMode.UI_AND_AUTO;

	/** Visible emote-picker columns in the chat overlay. */
	public int emotePickerColumns = 5;

	/** Visible emote-picker rows in the chat overlay before scrolling. */
	public int emotePickerRows = 4;

	/** Whether chat emote autocomplete should only suggest favorited emotes. */
	public boolean autocompleteFavoriteEmotes = false;

	public enum EmotePickerOpenMode {
		CURSOR("Cursor"), CENTER("Center"), CUSTOM("Custom");

		private final String label;

		EmotePickerOpenMode(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/** Where the chat emote picker should open when triggered. */
	public EmotePickerOpenMode emotePickerOpenMode = EmotePickerOpenMode.CURSOR;

	/** Saved custom top-left X position for the chat emote picker. */
	public int emotePickerCustomX = -1;

	/** Saved custom top-left Y position for the chat emote picker. */
	public int emotePickerCustomY = -1;

	public static final class CommandAlias {
		public String alias = "";
		public String command = "";

		public CommandAlias() {
		}

		public CommandAlias(String alias, String command) {
			this.alias = alias;
			this.command = command;
		}
	}

	public static final class CommandKeybind {
		public String input = "";
		public String command = "";

		public CommandKeybind() {
		}

		public CommandKeybind(String input, String command) {
			this.input = input;
			this.command = command;
		}
	}

	/** Load the config from disk, or return defaults (and write them) if absent. */
	public static BridgeConfig load() {
		if (Files.isRegularFile(PATH)) {
			try {
				String json = Files.readString(PATH, StandardCharsets.UTF_8);
				BridgeConfig config = GSON.fromJson(json, BridgeConfig.class);
				if (config != null) {
					if (config.showGameMessages != null) {
						config.gameDisplayMode = config.showGameMessages ? GameDisplayMode.ALL : GameDisplayMode.NONE;
						config.showGameMessages = null;
					}
					if (config.commandAliases == null) {
						config.commandAliases = new ArrayList<>();
					}
					if (config.commandKeybinds == null) {
						config.commandKeybinds = new ArrayList<>();
					}
					if (config.favoriteEmotes == null) {
						config.favoriteEmotes = new ArrayList<>();
					}
					if (config.chatEmoteUiEnabled != null) {
						config.chatEmoteToolsMode = config.chatEmoteUiEnabled ? ChatEmoteToolsMode.UI_AND_AUTO : ChatEmoteToolsMode.NONE;
						config.chatEmoteUiEnabled = null;
					}
					if (config.chatEmoteToolsMode == null) {
						config.chatEmoteToolsMode = ChatEmoteToolsMode.UI_AND_AUTO;
					}
					if (config.emotePickerOpenMode == null) {
						config.emotePickerOpenMode = EmotePickerOpenMode.CURSOR;
					}
					config.emotePickerColumns = Math.max(1, Math.min(10, config.emotePickerColumns));
					config.emotePickerRows = Math.max(1, Math.min(10, config.emotePickerRows));
					config.imagePreviewSize = Math.max(1, Math.min(100, config.imagePreviewSize));
					return config;
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Failed to read edenmod config; using defaults", e);
			}
		}
		BridgeConfig fresh = new BridgeConfig();
		fresh.save();
		return fresh;
	}

	/** Persist this config to disk. */
	public synchronized void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Failed to write edenmod config", e);
		}
	}

	/** Whether we currently hold a non-expired JWT. */
	public boolean hasValidJwt() {
		return !jwt.isEmpty() && jwtExpiresAt > (System.currentTimeMillis() / 1000L);
	}
}
