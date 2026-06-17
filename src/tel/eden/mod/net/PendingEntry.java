package tel.eden.mod.net;

/**
 * One member's pending-aspect count, from the bot's reply to the in-game
 * {@code /eden aspects pending} request.
 *
 * @param name    the member's username
 * @param aspects their pending aspect count
 */
public record PendingEntry(String name, int aspects) {}
