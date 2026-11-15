package tel.eden.mod.guild;

/** Structured, locally parsed Guild Log observation; raw log lore is deliberately excluded (only a cleaned sentence for the free-text categories). */
public sealed interface GuildLogEvent permits GuildLogEvent.Reward, GuildLogEvent.Bank, GuildLogEvent.Raid, GuildLogEvent.Text {
	String occurredAt();

	record Reward(String occurredAt, String giver, String receiver, String kind, int amount) implements GuildLogEvent {
	}

	record Bank(String occurredAt, String actor, String action, Integer quantity, String item, String charges, String accessTier) implements GuildLogEvent {
	}

	/** A Wars or Economy row, kept as its plain sentence (colour codes and icon glyphs removed): those logs are free text. */
	record Text(String occurredAt, String text) implements GuildLogEvent {
	}

	record Raid(String occurredAt, java.util.List<String> participants, String raid, Integer aspects, Integer emeralds, Long guildExperienceMillions, Integer seasonalRating) implements GuildLogEvent {
	}
}
