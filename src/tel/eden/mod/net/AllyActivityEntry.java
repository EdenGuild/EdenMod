package tel.eden.mod.net;

/** One allied guild's activity state from an {@code allyActivityReply} (backend-authoritative). */
public record AllyActivityEntry(String name, String tag, double elapsedMinutes, String lastRaidName) {
}
