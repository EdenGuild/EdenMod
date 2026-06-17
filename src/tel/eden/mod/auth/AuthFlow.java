package tel.eden.mod.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the browser link flow: {@code POST /auth/start} -> open the authorization
 * URL in the player's browser -> poll {@code GET /auth/status} until COMPLETE, then
 * hand back the backend-signed JWT and its expiry.
 *
 * <p>Runs on a daemon thread so the game is never blocked.
 */
public final class AuthFlow {
    private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
    private static final String MOD_VERSION = "1.0.0";
    private static final int POLL_ATTEMPTS = 150; // 150 * 2s = 5 minutes
    private static final long POLL_INTERVAL_MS = 2000L;

    /** Result callback for the link flow. */
    public interface Callback {
        void onSuccess(String jwt, long expiresAt);

        void onError(String message);
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Begin a link flow against {@code backendBaseUrl} (an https:// URL). */
    public void begin(String backendBaseUrl, Callback callback) {
        Thread thread = new Thread(() -> run(backendBaseUrl, callback), "edenmod-auth");
        thread.setDaemon(true);
        thread.start();
    }

    private void run(String backendBaseUrl, Callback callback) {
        String base = backendBaseUrl.strip();
        if (!base.startsWith("https://")) {
            callback.onError("Backend URL must be https.");
            return;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        try {
            JsonObject start = postJson(base + "/auth/start");
            String authorizationUrl = start.get("authorizationUrl").getAsString();
            String pollToken = start.get("pollToken").getAsString();
            // Never hand an arbitrary scheme to the OS protocol handler: a hostile or
            // compromised backend could return e.g. ms-msdt:/file:/search-ms: and turn
            // "open in browser" into local code execution. Only ever open https.
            URI authUri = URI.create(authorizationUrl);
            if (authUri.getScheme() == null || !authUri.getScheme().equalsIgnoreCase("https")) {
                callback.onError("Backend returned a non-https authorization URL; refusing to open it.");
                return;
            }
            Util.getPlatform().openUri(authUri);
            pollUntilComplete(base, pollToken, callback);
        } catch (Exception e) {
            LOGGER.warn("Link flow failed", e);
            callback.onError("Linking failed: " + e.getMessage());
        }
    }

    private void pollUntilComplete(String base, String pollToken, Callback callback)
            throws Exception {
        for (int attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
            String encodedToken = URLEncoder.encode(pollToken, StandardCharsets.UTF_8);
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/auth/status?token=" + encodedToken))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                if ("COMPLETE".equals(stringOrEmpty(body, "status"))) {
                    String jwt = stringOrEmpty(body, "jwt");
                    callback.onSuccess(jwt, extractExpiry(jwt));
                    return;
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        callback.onError("Linking timed out. Please try again.");
    }

    private JsonObject postJson(String url) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .header("X-Mod-Version", MOD_VERSION)
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("auth/start returned " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String stringOrEmpty(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    /**
     * Read the {@code exp} claim purely to schedule re-auth before the token lapses.
     *
     * <p><strong>This does NOT authenticate the token.</strong> The JWT signature is
     * never verified here (the client has no key to verify it with), so the parsed
     * claims must never drive any security decision on the client. The bridge backend
     * is the sole authority: it must re-validate the signature (and any claims it
     * trusts) on every authenticated request, since a MITM or hostile backend could
     * hand the mod a forged token with an arbitrary {@code exp}.
     */
    public static long extractExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return 0L;
            }
            String payload =
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
            return obj.has("exp") ? obj.get("exp").getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
