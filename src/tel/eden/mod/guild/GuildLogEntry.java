package tel.eden.mod.guild;

/** One immutable row from the guild's in-game General log. */
public record GuildLogEntry(String occurredAt, String text) {
}
