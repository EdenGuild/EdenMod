package tel.eden.mod.war;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live territory reference data for the war suite.
 *
 * <p>Two independent sources are merged: the public Wynncraft API (fetched
 * asynchronously every few minutes) gives each territory's bounding box, used for
 * beacons and the current-territory lookup; the client's synced <em>advancements</em>
 * (scraped on the client thread every few seconds) carry the live defense rating
 * Wynncraft only exposes there. Both degrade gracefully — a failed fetch or an empty
 * advancement set simply leaves the maps stale, never throwing into the render/tick
 * loop.
 */
public final class TerritoryData {
	private TerritoryData() {
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
	private static final String TERRITORY_API = "https://api.wynncraft.com/v3/guild/list/territory";
	private static final int HTTP_OK = 200;
	private static final long API_REFRESH_MS = 10 * 60 * 1000L;
	private static final long ADVANCEMENT_SCRAPE_MS = 10 * 1000L;

	private static final Pattern DEFENSE = Pattern.compile("Territory Defences:\\s*(.+?)\\s*Trading");

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

	/** Territory name to bounding box {@code [minX, minZ, maxX, maxZ]} from the API. */
	private static volatile Map<String, int[]> rects = new HashMap<>();
	/** Every territory name from the API, for war-detection validation. */
	private static volatile List<String> names = new ArrayList<>();
	/** Territory name to defense rating ("Very Low".."Very High"), from advancements. */
	private static final Map<String, String> defenses = new HashMap<>();

	private static volatile boolean fetchInFlight = false;
	private static long lastApiRefresh = 0L;
	private static long lastScrape = 0L;

	/** Drive both refresh cycles from the client tick; cheap when nothing is due. */
	public static void onTick() {
		if (Minecraft.getInstance().getConnection() == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!fetchInFlight && (rects.isEmpty() || now - lastApiRefresh > API_REFRESH_MS)) {
			lastApiRefresh = now;
			fetchApi();
		}
		if (now - lastScrape > ADVANCEMENT_SCRAPE_MS || defenses.isEmpty()) {
			lastScrape = now;
			scrapeAdvancements();
		}
	}

	/** Names of every known territory (empty until the first API fetch lands). */
	public static List<String> territoryNames() {
		return names;
	}

	/** Defense rating for a territory, or "Unknown" if not scraped yet. */
	public static String defense(String territory) {
		return defenses.getOrDefault(territory, "Unknown");
	}

	/** Territory containing world coordinates {@code (x, z)}, or null. */
	public static String territoryAt(int x, int z) {
		for (Map.Entry<String, int[]> entry : rects.entrySet()) {
			int[] r = entry.getValue();
			if (x > r[0] && x < r[2] && z > r[1] && z < r[3]) {
				return entry.getKey();
			}
		}
		return null;
	}

	/** Bounding box {@code [minX, minZ, maxX, maxZ]} for a territory, or null. */
	public static int[] rect(String territory) {
		return rects.get(territory);
	}

	/** Centre {@code [x, z]} of a territory, or null if unknown. */
	public static int[] middle(String territory) {
		int[] r = rects.get(territory);
		if (r == null) {
			return null;
		}
		return new int[]{(r[0] + r[2]) / 2, (r[1] + r[3]) / 2};
	}

	private static void fetchApi() {
		fetchInFlight = true;
		HTTP.sendAsync(HttpRequest.newBuilder(URI.create(TERRITORY_API)).header("User-Agent", "EdenMod").timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			try {
				if (err == null && resp.statusCode() == HTTP_OK) {
					parseApi(resp.body());
				}
			} catch (RuntimeException e) {
				LOGGER.debug("Territory API parse failed", e);
			} finally {
				fetchInFlight = false;
			}
		});
	}

	private static void parseApi(String body) {
		JsonObject root = JsonParser.parseString(body).getAsJsonObject();
		Map<String, int[]> newRects = new HashMap<>();
		List<String> newNames = new ArrayList<>();
		for (String name : root.keySet()) {
			newNames.add(name);
			JsonObject terr = root.getAsJsonObject(name);
			if (terr == null || !terr.has("location")) {
				continue;
			}
			JsonObject loc = terr.getAsJsonObject("location");
			if (!loc.has("start") || !loc.has("end")) {
				continue;
			}
			int sx = loc.getAsJsonArray("start").get(0).getAsInt();
			int sz = loc.getAsJsonArray("start").get(1).getAsInt();
			int ex = loc.getAsJsonArray("end").get(0).getAsInt();
			int ez = loc.getAsJsonArray("end").get(1).getAsInt();
			newRects.put(name, new int[]{Math.min(sx, ex), Math.min(sz, ez), Math.max(sx, ex), Math.max(sz, ez)});
		}
		rects = newRects;
		names = newNames;
	}

	private static void scrapeAdvancements() {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			return;
		}
		try {
			for (AdvancementNode node : connection.getAdvancements().getTree().nodes()) {
				DisplayInfo display = node.advancement().display().orElse(null);
				if (display == null) {
					continue;
				}
				String territory = display.getTitle().getString().trim();
				String description = normalize(display.getDescription().getString());
				Matcher matcher = DEFENSE.matcher(description);
				if (matcher.find()) {
					defenses.put(territory, matcher.group(1).trim());
				}
			}
		} catch (RuntimeException e) {
			LOGGER.debug("Advancement scrape failed", e);
		}
	}

	private static String normalize(String raw) {
		return raw.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
	}
}
