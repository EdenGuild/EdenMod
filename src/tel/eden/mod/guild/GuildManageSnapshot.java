package tel.eden.mod.guild;

/** Stable guild-status values exposed by the root {@code /gu man} menu. */
public record GuildManageSnapshot(Long seasonRating, Integer seasonPosition, Integer weeklyCompleted, Integer weeklyGoal, Integer territoryCount, java.util.List<Alliance> alliances) {
	public GuildManageSnapshot {
		alliances = alliances == null ? java.util.List.of() : java.util.List.copyOf(alliances);
	}

	/** One guild ally as displayed by the root menu's Diplomacy button. */
	public record Alliance(String name, String tag) {
	}
}
