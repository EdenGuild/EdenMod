package tel.eden.mod.net;

/**
 * One guild member heading to a territory (from the {@code warBoard} broadcast):
 * their display name, Wynncraft uuid, and whether they are currently inside that
 * territory ({@code inside} — green head border when true, red while en route).
 */
public record WarGoer(String name, String uuid, boolean inside) {
}
