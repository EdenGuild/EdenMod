package tel.eden.mod.chat;

/**
 * A territory defense victory detected from in-game chat.
 *
 * @param territory the defended territory name
 * @param attacker  the enemy attacking guild (tag or name)
 */
public record TerritoryDefendedEvent(String territory, String attacker) {
}
