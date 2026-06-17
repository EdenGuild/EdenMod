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
 * <p>Holds the backend base URL the mod talks to, the current backend-signed JWT
 * (and its expiry, so we can re-auth before it lapses), and whether the bridge is
 * enabled.
 */
public final class BridgeConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH =
            FabricLoader.getInstance().getConfigDir().resolve("edenmod.json");

    /**
     * Default backend URL baked into the build, so a fresh install (or a cleared
     * config) connects without anyone typing it in-game. Update this to the
     * permanent bridge domain once the named tunnel is set up.
     */
    public static final String DEFAULT_BACKEND_URL = "https://bridge.eden.tel/";

    /** Public HTTPS base URL of the EdenBot backend (e.g. https://eden.example.com). */
    public String backendBaseUrl = DEFAULT_BACKEND_URL;

    /** Backend-signed JWT obtained from the link flow; empty until linked. */
    public String jwt = "";

    /** Unix epoch seconds at which {@link #jwt} expires (0 when unknown). */
    public long jwtExpiresAt = 0L;

    /** Whether the bridge should connect while on Wynncraft. */
    public boolean enabled = true;

    /**
     * Whether open/full raid parties are auto-announced in chat with a clickable
     * {@code [JOIN #id]} feed. When false, parties are only
     * shown on demand via {@code /eden party list}. Toggle with
     * {@code /eden party announce on|off}.
     */
    public boolean partyAnnounce = true;

    /**
     * Whether shared Wynncraft item strings seen in guild chat are decoded (via
     * Wynntils) and relayed to the bridge channel as a rendered item card.
     */
    public boolean relayItemCards = true;

    /** Load the config from disk, or return defaults (and write them) if absent. */
    public static BridgeConfig load() {
        if (Files.isRegularFile(PATH)) {
            try {
                String json = Files.readString(PATH, StandardCharsets.UTF_8);
                BridgeConfig config = GSON.fromJson(json, BridgeConfig.class);
                if (config != null) {
                    if (config.backendBaseUrl == null || config.backendBaseUrl.isBlank()) {
                        config.backendBaseUrl = DEFAULT_BACKEND_URL;
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
