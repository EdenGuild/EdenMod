package tel.eden.mod.guild;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache of known allied guild names to their tags.
 * Populated from diplomacy screen scraping, root manage menu tooltips,
 * and ally activity replies to ensure tags are always present in output.
 */
public final class AllyTagCache {
	private static final Map<String, String> NAME_TO_TAG = new ConcurrentHashMap<>();
	private static final Map<String, String> TAG_TO_NAME = new ConcurrentHashMap<>();

	private AllyTagCache() {
	}

	public static void remember(String name, String tag) {
		if (name == null || name.isBlank() || tag == null || tag.isBlank()) {
			return;
		}
		String cleanName = name.trim();
		String cleanTag = tag.trim();
		NAME_TO_TAG.put(cleanName.toLowerCase(Locale.ROOT), cleanTag);
		TAG_TO_NAME.put(cleanTag.toLowerCase(Locale.ROOT), cleanName);
	}

	public static String getTag(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		return NAME_TO_TAG.get(name.trim().toLowerCase(Locale.ROOT));
	}

	public static String getName(String tag) {
		if (tag == null || tag.isBlank()) {
			return null;
		}
		return TAG_TO_NAME.get(tag.trim().toLowerCase(Locale.ROOT));
	}

	public static void clear() {
		NAME_TO_TAG.clear();
		TAG_TO_NAME.clear();
	}
}
