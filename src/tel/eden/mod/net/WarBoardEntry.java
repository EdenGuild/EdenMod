package tel.eden.mod.net;

import java.util.List;

/**
 * One territory's live war-board state (from the {@code warBoard} broadcast): the
 * scraped defence rating ({@code ""} when unknown), whether scouts reported conflicting
 * ratings ({@code conflict}), and who is heading there.
 */
public record WarBoardEntry(String territory, String defense, boolean conflict, WarQueuer queuer, List<WarGoer> going) {
	public WarBoardEntry {
		going = going == null ? List.of() : List.copyOf(going);
	}

	public WarBoardEntry(String territory, String defense, boolean conflict, List<WarGoer> going) {
		this(territory, defense, conflict, null, going);
	}
}
