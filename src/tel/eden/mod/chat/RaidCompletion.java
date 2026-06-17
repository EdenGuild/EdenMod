package tel.eden.mod.chat;

import java.util.List;

/**
 * One parsed guild raid completion.
 *
 * @param party     the real account usernames of the (up to four) participants,
 *     hover-resolved from their displayed nicknames
 * @param raidName  the raid's name (e.g. {@code "Nest of the Grootslangs"})
 * @param aspects   aspects claimed (per participant)
 * @param emeralds  emeralds claimed (per participant)
 * @param guildExp  the guild-experience reward as shown in chat (e.g. {@code "633"}
 *     from {@code "+633m Guild Experience"}); kept verbatim for display only
 */
public record RaidCompletion(
        List<String> party, String raidName, int aspects, int emeralds, String guildExp) {}
