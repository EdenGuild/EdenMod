package tel.eden.mod.net;

/** One member's war count from a {@code warCountsReply} (backend-authoritative). */
public record WarCountEntry(String name, int wars) {
}
