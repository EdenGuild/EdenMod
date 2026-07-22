package tel.eden.mod.net;

import java.util.List;

/**
 * One territory's live war-board state (from the {@code warBoard} broadcast): the
 * scraped defence rating ({@code ""} when unknown), whether scouts reported conflicting
 * ratings ({@code conflict}), and who is heading there.
 */
public record WarBoardEntry(String territory, String defense, boolean conflict, List<WarGoer> going) {
}
