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
 * @param seasonalRating  the guild's total Seasonal Rating for the raid (the sum over the
 *     named party, e.g. {@code 440} from {@code "+440 Seasonal Rating"}), or {@code null}
 *     when the line carried none
 */
public record RaidCompletion(List<String> party, String raidName, int aspects, int emeralds, String guildExp, Integer seasonalRating) {
	public RaidCompletion {
		party = List.copyOf(party);
	}
}
