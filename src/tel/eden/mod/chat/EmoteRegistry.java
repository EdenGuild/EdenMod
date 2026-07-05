package tel.eden.mod.chat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves {@code :shortcode:} tokens in relayed Discord messages to the
 * private-use codepoint that the {@code edenmod:emotes} bitmap font renders as
 * an inline image, e.g. {@code :catplush1:} -&gt; the glyph backed by
 * {@code textures/catplush/catplush1.png}.
 *
 * <p>Both the font ({@code assets/edenmod/font/emotes.json}) and the manifest
 * this class reads ({@code assets/edenmod/emotes_manifest.json}) are generated
 * at build time by the {@code generateEmoteAssets} Gradle task from whatever
 * PNGs exist under {@code resources/assets/edenmod/textures/}. Adding a new
 * emote is purely an asset change — drop a PNG in one of the configured
 * directories and rebuild; nothing in this class needs to change.
 */
public final class EmoteRegistry {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
	private static final String MANIFEST_PATH = "/assets/edenmod/emotes_manifest.json";
	private static final String DEFAULT_FONT_ID = "edenmod:emotes";

	private static volatile Map<String, Integer> emotes;
	private static volatile FontDescription font;
	private static volatile boolean failedToLoad;

	private EmoteRegistry() {
	}

	/** The private-use codepoint for {@code shortcode} (without colons), or {@code null} if unknown. */
	public static Integer codepointFor(String shortcode) {
		ensureLoaded();
		return emotes.get(shortcode);
	}

	/** The font every emote glyph renders with. */
	public static FontDescription font() {
		ensureLoaded();
		return font;
	}

	/** Every known emote shortcode (without colons), sorted — for listing and autocomplete. */
	public static java.util.List<String> shortcodes() {
		ensureLoaded();
		java.util.List<String> list = new java.util.ArrayList<>(emotes.keySet());
		java.util.Collections.sort(list);
		return list;
	}

	private static void ensureLoaded() {
		if (emotes != null) {
			return;
		}
		synchronized (EmoteRegistry.class) {
			if (emotes == null) {
				load();
			}
		}
	}

	private static void load() {
		Map<String, Integer> loaded = new ConcurrentHashMap<>();
		String fontId = DEFAULT_FONT_ID;
		try (InputStream in = EmoteRegistry.class.getResourceAsStream(MANIFEST_PATH)) {
			if (in == null) {
				// Not fatal: happens if someone runs from sources without ever having
				// built (the manifest is gitignored, generated). Emotes just render
				// as their literal ":shortcode:" text until the project is built.
				LOGGER.warn("No emote manifest at {} — run a build to generate it; chat emotes will render as plain text until then", MANIFEST_PATH);
			} else {
				try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					JsonObject root = new Gson().fromJson(reader, JsonObject.class);
					if (root != null) {
						if (root.has("font") && !root.get("font").isJsonNull()) {
							fontId = root.get("font").getAsString();
						}
						JsonObject entries = root.getAsJsonObject("emotes");
						if (entries != null) {
							for (String shortcode : entries.keySet()) {
								loaded.put(shortcode, entries.get(shortcode).getAsInt());
							}
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Failed to load emote manifest; chat emotes disabled", e);
			loaded.clear();
		}
		font = new FontDescription.Resource(Identifier.parse(fontId));
		emotes = Collections.unmodifiableMap(loaded);
	}
}