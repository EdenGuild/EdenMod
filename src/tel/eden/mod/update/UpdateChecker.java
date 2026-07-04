package tel.eden.mod.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks the EdenMod GitHub repo for a release newer than the installed version.
 *
 * <p>Queries the public {@code releases/latest} endpoint (no auth), reads the
 * {@code tag_name} and the (non-sources) jar asset, and compares the version to the
 * one Fabric reports for this mod. Network/parse failures resolve to "no update" so
 * a checker hiccup never disrupts play.
 */
public final class UpdateChecker {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
	private static final String MOD_ID = "edenmod";
	private static final String LATEST_RELEASE_API = "https://api.github.com/repos/EdenGuild/EdenMod/releases/latest";
	private static final int HTTP_OK = 200;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

	/** The installed mod version (e.g. {@code "1.0.3"}), or null if unknown. */
	public static String currentVersion() {
		return FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(null);
	}

	/** The latest release if it is newer than the installed version, else empty. */
	public Optional<UpdateInfo> check() {
		try {
			HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(LATEST_RELEASE_API)).header("Accept", "application/vnd.github+json").header("User-Agent", "EdenMod-updater").timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != HTTP_OK) {
				LOGGER.debug("Update check returned HTTP {}", resp.statusCode());
				return Optional.empty();
			}
			JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
			String tag = body.has("tag_name") ? body.get("tag_name").getAsString() : "";
			String latest = tag.startsWith("v") ? tag.substring(1) : tag;
			String current = currentVersion();
			if (latest.isEmpty() || current == null || compare(latest, current) <= 0) {
				return Optional.empty();
			}
			String jarUrl = findJarAsset(body);
			if (jarUrl == null) {
				return Optional.empty();
			}
			String pageUrl = body.has("html_url") ? body.get("html_url").getAsString() : "";
			return Optional.of(new UpdateInfo(latest, jarUrl, pageUrl));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		} catch (RuntimeException e) {
			LOGGER.debug("Update check failed", e);
			return Optional.empty();
		} catch (Exception e) {
			LOGGER.debug("Update check failed", e);
			return Optional.empty();
		}
	}

	private static String findJarAsset(JsonObject release) {
		if (!release.has("assets") || !release.get("assets").isJsonArray()) {
			return null;
		}
		JsonArray assets = release.getAsJsonArray("assets");
		for (var element : assets) {
			JsonObject asset = element.getAsJsonObject();
			String name = asset.has("name") ? asset.get("name").getAsString() : "";
			if (name.endsWith(".jar") && !name.endsWith("-sources.jar") && asset.has("browser_download_url")) {
				return asset.get("browser_download_url").getAsString();
			}
		}
		return null;
	}

	/** Compare dotted numeric versions: positive if {@code a} is newer than {@code b}. */
	static int compare(String a, String b) {
		String[] pa = a.split("\\.");
		String[] pb = b.split("\\.");
		int parts = Math.max(pa.length, pb.length);
		for (int i = 0; i < parts; i++) {
			int x = i < pa.length ? numericPrefix(pa[i]) : 0;
			int y = i < pb.length ? numericPrefix(pb[i]) : 0;
			if (x != y) {
				return Integer.compare(x, y);
			}
		}
		return 0;
	}

	private static int numericPrefix(String s) {
		int end = 0;
		while (end < s.length() && Character.isDigit(s.charAt(end))) {
			end++;
		}
		return end == 0 ? 0 : Integer.parseInt(s.substring(0, end));
	}
}
