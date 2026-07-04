package tel.eden.mod.chat;

/**
 * A parsed level-up announcement, e.g. combat ("combat level 121") or a profession
 * ("level 120 in Fishing").
 *
 * @param name   the real account name of the player who levelled up (hover-resolved)
 * @param detail the level detail, e.g. {@code "combat level 121"} or {@code "level 120 in Fishing"}
 */
public record LevelUp(String name, String detail) {
}
