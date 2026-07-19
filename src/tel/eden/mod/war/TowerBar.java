package tel.eden.mod.war;

import java.util.Arrays;

/**
 * Parses Wynncraft's war-tower boss-bar text
 * ("[TAG] Some Territory Tower - &#10084; ... - &#9760; ...").
 *
 * <p>Shared by {@link WarDPS} (tower stats) and {@link WarTracker} (attendance territory
 * tag) so both derive the territory the same way, and only one parse needs updating if
 * Wynncraft changes the bar format. Input is expected already colour-/glyph-cleaned (see
 * {@link WarDPS#clean}).
 */
final class TowerBar {
	private TowerBar() {
	}

	/** Whether a cleaned boss-bar line is a war-tower bar. */
	static boolean isTowerBar(String cleaned) {
		return cleaned.contains("Tower");
	}

	/**
	 * The territory name from a cleaned tower-bar line, or null if it doesn't parse. The name
	 * is the words between the leading "[TAG]" and the trailing "Tower" word (i.e. before the
	 * first " - " stat separator).
	 */
	static String territory(String cleaned) {
		String[] words = cleaned.split(" ");
		int firstDash = Arrays.asList(words).indexOf("-");
		if (firstDash < 2) {
			return null;
		}
		StringBuilder name = new StringBuilder();
		for (int i = 1; i < firstDash - 1; i++) {
			name.append(words[i]).append(" ");
		}
		String territory = name.toString().trim();
		return territory.isEmpty() ? null : territory;
	}
}
