package tel.eden.mod.net;

/**
 * One offline mail the bot held for this player, pushed in a {@code mailDelivery}.
 *
 * @param id      the bot's mail id, echoed back in {@code mailAck}
 * @param from    the sender's username
 * @param content the message text
 * @param sentAt  when it was sent, epoch milliseconds
 */
public record MailEntry(long id, String from, String content, long sentAt) {
}
