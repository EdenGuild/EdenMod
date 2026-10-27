package tel.eden.mod.guild;

/**
 * Stable guild-status values exposed by the root {@code /gu man} menu.
 *
 * @param seasonEndsIn the raw displayed countdown (e.g. {@code "5 hours"}), not a
 *     resolved timestamp — Wynncraft's own text only updates hourly-ish, so using it
 *     as-is for equality comparisons doesn't cause a resend on every read the way
 *     re-deriving "time left" from a stored deadline each tick would.
 * @param srPerHour the status item's "+N SR per Hour" line, or {@code null}: Wynncraft
 *     only shows it while the guild holds territories, so unlike the other fields its
 *     absence is normal and never holds the snapshot back. It was earned at
 *     {@code territoryCount}, read in the same pass.
 */
public record GuildManageSnapshot(Integer seasonNumber, String seasonEndsIn, Long seasonRating, Integer seasonPosition, Integer weeklyCompleted, Integer weeklyGoal, Integer territoryCount, Integer srPerHour, java.util.List<Alliance> alliances) {
	public GuildManageSnapshot {
		alliances = alliances == null ? java.util.List.of() : java.util.List.copyOf(alliances);
	}

	/** One guild ally as displayed by the root menu's Diplomacy button. */
	public record Alliance(String name, String tag) {
	}
}
