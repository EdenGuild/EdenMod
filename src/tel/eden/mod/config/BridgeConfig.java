package tel.eden.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persisted client configuration, stored at {@code config/edenmod.json}.
 *
 * <p>
 * Holds the backend base URL the mod talks to, the current backend-signed JWT
 * (and its expiry, so we can re-auth before it lapses), and whether the bridge
 * is
 * enabled.
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
	 * {@code [JOIN #id]} feed. When false, parties are only
	 * shown on demand via {@code /eden party list}. Toggle with
	 * {@code /eden party announce on|off}.
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
