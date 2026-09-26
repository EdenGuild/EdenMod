package tel.eden.mod.net;

/**
 * One member's pending reward count (aspects or emeralds, depending on which request
 * this came from — see {@code aspectsPendingReply}/{@code emeraldsPendingReply}), from
 * the bot's reply to the in-game payout screen.
 *
 * @param name   the member's username
 * @param amount their pending count, in real (not Liquid Emerald) units for emeralds
 */
public record PendingEntry(String name, int amount) {
}
