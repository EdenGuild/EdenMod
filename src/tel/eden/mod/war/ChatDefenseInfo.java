package tel.eden.mod.war;

/**
 * A guild-reported territory defense rating (e.g. from a member's Wynntils "defense
 * is High" announcement), overlaid on the attack-timer HUD as fresher intel than the
 * scraped advancement value. Considered "recent" for 40 minutes.
 */
public record ChatDefenseInfo(String username, String territory, String defense, long timestamp) {
	private static final long RECENT_MS = 40L * 60L * 1000L;

	public boolean isRecent() {
		return System.currentTimeMillis() - timestamp < RECENT_MS;
	}
}
