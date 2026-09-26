package tel.eden.mod.guild;

/** Structured, locally parsed Guild Log observation; raw log text is deliberately excluded. */
public sealed interface GuildLogEvent permits GuildLogEvent.Reward, GuildLogEvent.Bank, GuildLogEvent.Raid {
	String occurredAt();

	record Reward(String occurredAt, String giver, String receiver, String kind, int amount) implements GuildLogEvent {
	}

	record Bank(String occurredAt, String actor, String action, Integer quantity, String item, String charges, String accessTier) implements GuildLogEvent {
	}

	record Raid(String occurredAt, java.util.List<String> participants, String raid, Integer aspects, Integer emeralds, Long guildExperienceMillions, Integer seasonalRating) implements GuildLogEvent {
	}
}
