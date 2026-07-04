package tel.eden.mod.chat;

/**
 * One captured guild-chat line.
 *
 * @param username the real account username (recovered from the player-name
 *     component's hover text or shift-click insertion)
 * @param nickname the visible display name (may equal {@code username})
 * @param message the chat body
 */
public record CapturedMessage(String username, String nickname, String message) {
}
