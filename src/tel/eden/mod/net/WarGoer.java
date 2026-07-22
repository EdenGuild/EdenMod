package tel.eden.mod.net;

/**
 * One guild member heading to a territory (from the {@code warBoard} broadcast):
 * their display name and Wynncraft uuid, used to draw their head on the attack-timer
 * HUD.
 */
public record WarGoer(String name, String uuid) {
}
