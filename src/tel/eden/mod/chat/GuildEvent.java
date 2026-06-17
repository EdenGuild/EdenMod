package tel.eden.mod.chat;

/**
 * A guild-management/alliance event parsed from chat.
 *
 * <p>{@code kind} is one of {@code join}, {@code leave}, {@code kick},
 * {@code invite}, {@code uninvite}, {@code alliance_formed},
 * {@code alliance_revoked}, {@code alliance_request}. {@code actor} is who
 * performed it (empty when the message has no actor, e.g. a join); {@code subject}
 * is the affected player or guild.
 */
public record GuildEvent(String kind, String actor, String subject) {}
