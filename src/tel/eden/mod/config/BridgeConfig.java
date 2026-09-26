package tel.eden.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import tel.eden.mod.EdenLogger;

/**
 * Persisted client configuration, stored at {@code config/edenmod.json}.
 *
 * <p>
 * Holds the backend base URL the mod talks to and the per-feature toggles.
 * No auth state is persisted here — identity is verified live via Mojang on
 * every /ws/v2 connection, and standing (linked/member/rank) is reported by the
 * backend in the {@code authOk} frame.
 */
public final class BridgeConfig {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("edenmod.json");

	/** The EdenBot backend URL, baked into the build. Never written to the config file. */
	public static final String DEFAULT_BACKEND_URL = "https://bridge.eden.tel/";

	/** Always {@link #DEFAULT_BACKEND_URL}; transient so it is never written to the config file. */
	public transient String backendBaseUrl = DEFAULT_BACKEND_URL;

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

	// ---- Territory / war suite (all client-side; no effect off Wynncraft) --------

	/** HUD list of upcoming territory attacks (scoreboard timers + defense ratings). */
	public boolean warAttackTimers = true;

	/**
	 * Max attack-timer rows shown before the rest collapse into a "+N more" footer (the
	 * soonest are kept). A full-scale war can queue 30-40+ territories. Range 1-50.
	 */
	public int warAttackTimerMaxRows = 14;

	/** Green in-world beacon marking the soonest upcoming territory attack. */
	public boolean warGreenBeacon = true;

	// ---- Dropped item scaling ---------------------------------------------------

	public static final float GROUND_ITEM_MIN_SCALE = 0.1f;
	public static final float GROUND_ITEM_MAX_SCALE = 10.0f;

	/** Master toggle for client-only dropped-item rescaling. */
	public boolean groundItemVisibility = false;

	/** Ordered filter rules for dropped items. The first matching rule supplies the scale. */
	public List<GroundItemVisibilityRule> groundItemVisibilityRules = new ArrayList<>();

	public static final class GroundItemVisibilityRule {
		/** Case-insensitive substring filter against the dropped item's visible name. */
		public String nameContains = "";
		/** Render-only dropped-item scale multiplier. Range 0.1-10.0; 1.0 means unchanged. */
		public float size = 1.0f;

		public GroundItemVisibilityRule() {
		}

		public GroundItemVisibilityRule(String nameContains, float size) {
			this.nameContains = nameContains;
			this.size = size;
		}

		public void sanitize() {
			nameContains = sanitizeGroundItemName(nameContains);
			if (!Float.isFinite(size)) {
				size = 1.0f;
			}
			size = Math.max(GROUND_ITEM_MIN_SCALE, Math.min(GROUND_ITEM_MAX_SCALE, size));
		}
	}

	/** War info overlay: tower EHP, team DPS, and estimated time remaining. */
	public boolean warDpsHud = true;

	/** HUD chip showing your rolling 7-day war count (from the backend). */
	public boolean warWeeklyCountHud = false;

	/**
	 * Run {@code /stream} automatically on entering a Wynncraft world. Off by default:
	 * it changes what the server shows you, so it should be an opt-in.
	 */
	public boolean autoStream = false;

	/** Make guild shouts clickable to pre-fill {@code /msg <shouter>}. */
	public boolean shoutsClickable = true;

	/**
	 * Add a "Click to say Congratulations!" button to milestone broadcasts. Off by
	 * default; when on, clicking DMs the player {@link #congratsMessage}.
	 */
	public boolean clickToCongratulate = false;

	/** Message sent by the click-to-congratulate button. */
	public String congratsMessage = "Congrats!";

	/**
	 * Custom textures for crafted consumables (food/potions/scrolls), avomod2-style. The
	 * models/textures ship under {@code assets/edenmod/{items,models,textures}} (ported from
	 * avomod2), so this is on by default.
	 */
	public boolean customItemTextures = true;

	/**
	 * Draw a short stat label ("MR", "SD", "+ATK") over crafted consumables in slots, so a
	 * bank page can be read at a glance. Independent of {@link #customItemTextures} — the
	 * two share the same rule match, but either can be shown without the other.
	 */
	public boolean consumableLabels = true;

	/** Label text scale; 1.0 is the same size as the stack-count number. */
	public float consumableLabelScale = 0.75f;

	/** Label offset from the slot's top-left corner, in (unscaled) pixels. */
	public int consumableLabelOffsetX = 0;

	public int consumableLabelOffsetY = 0;

	/** Radial emote wheel (hold the keybind) for playing Wynncraft {@code /emote} animations. */
	public boolean emoteWheelEnabled = true;

	/** The emote-wheel favorites, one per slot (empty string = unused slot). */
	public List<String> emoteWheelFavorites = new ArrayList<>();

	/** Emotes detected as unlocked from Wynncraft's emotes menu (for the favorites picker). */
	public List<String> emoteUnlocked = new ArrayList<>();

	/** Permanently discovered hidden features. */
	public List<String> unlockedSecrets = new ArrayList<>();

	public static final String SECRET_BABY_PLAYERS = "baby_players";

	/** Whether players are rendered at the unlocked baby-player size. */
	public boolean babyPlayers = false;

	/**
	 * Saved HUD element positions as {@code name -> [xFraction, yFraction]} (0-1 of
	 * the screen). Absent elements fall back to their built-in default anchor.
	 */
	public Map<String, float[]> hudPositions = new HashMap<>();

	/**
	 * Saved HUD element scales as {@code name -> factor} (1.0 = default size). Absent
	 * elements render at 1.0.
	 */
	public Map<String, Float> hudScales = new HashMap<>();

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

	/**
	 * Whether the emote-picker keybind may open chat directly from gameplay when no
	 * other screen is open. When disabled, the binding only works while chat is
	 * already focused.
	 */
	public boolean emotePickerOpenFromGameplay = true;

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
					if (config.congratsMessage == null) {
						config.congratsMessage = "Congrats!";
					}
					if (config.hudPositions == null) {
						config.hudPositions = new HashMap<>();
					}
					if (config.hudScales == null) {
						config.hudScales = new HashMap<>();
					}
					if (config.emoteWheelFavorites == null) {
						config.emoteWheelFavorites = new ArrayList<>();
					}
					if (config.emoteUnlocked == null) {
						config.emoteUnlocked = new ArrayList<>();
					}
					if (config.unlockedSecrets == null) {
						config.unlockedSecrets = new ArrayList<>();
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
					if (config.groundItemVisibilityRules == null) {
						config.groundItemVisibilityRules = new ArrayList<>();
					}
					config.groundItemVisibilityRules.removeIf(rule -> rule == null);
					for (GroundItemVisibilityRule rule : config.groundItemVisibilityRules) {
						rule.sanitize();
					}
					config.emotePickerColumns = Math.max(1, Math.min(10, config.emotePickerColumns));
					config.emotePickerRows = Math.max(1, Math.min(10, config.emotePickerRows));
					config.warAttackTimerMaxRows = Math.max(1, Math.min(50, config.warAttackTimerMaxRows));
					config.imagePreviewSize = Math.max(1, Math.min(100, config.imagePreviewSize));
					// The label scale divides the draw position, so a hand-edited 0 would put
					// the text at an infinite coordinate; the offsets are bounded to keep a
					// typo from parking a label off-screen with no GUI control to undo it.
					config.consumableLabelScale = Math.max(0.25f, Math.min(2.0f, config.consumableLabelScale));
					config.consumableLabelOffsetX = Math.max(-64, Math.min(64, config.consumableLabelOffsetX));
					config.consumableLabelOffsetY = Math.max(-64, Math.min(64, config.consumableLabelOffsetY));
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

	public boolean isSecretUnlocked(String id) {
		return unlockedSecrets.contains(id);
	}

	/** Remember a discovery once; subsequent activations do not rewrite the config. */
	public void unlockSecret(String id) {
		if (!unlockedSecrets.contains(id)) {
			unlockedSecrets.add(id);
			save();
		}
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

	public static String sanitizeGroundItemName(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().replaceAll("\\s+", " ");
	}

	public static String normalizeGroundItemName(String value) {
		if (value == null) {
			return "";
		}
		return sanitizeGroundItemName(value).toLowerCase(Locale.ROOT);
	}
}
