package tel.eden.mod.chat;

/**
 * A guild reward handout parsed from chat, e.g. {@code "OfficerName rewarded
 * an Aspect to MemberName"}.
 *
 * @param giver    the real account name of who granted the reward (hover-resolved)
 * @param reward   the reward as shown in chat (e.g. {@code "an Aspect"},
 *     {@code "a Guild Tome"}, {@code "1024 Emeralds"}); kept verbatim for display
 * @param receiver the real account name of who received it (hover-resolved)
 */
public record GuildReward(String giver, String reward, String receiver) {
}
