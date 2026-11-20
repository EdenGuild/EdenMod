package tel.eden.mod.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GuildRewardsUrlTest {
	@Test
	void fetchesByUuidWhenThePlayerRecordHasOne() {
		assertEquals("https://api.wynncraft.com/v3/guild/uuid/221efb28-7f93-4d5d-b219-9ddf43f7418b", GuildRewards.guildUrl("221efb28-7f93-4d5d-b219-9ddf43f7418b", "Eden"));
	}

	@Test
	void fallsBackToTheNameWithoutAUsableUuid() {
		assertEquals("https://api.wynncraft.com/v3/guild/Eden", GuildRewards.guildUrl(null, "Eden"));
		assertEquals("https://api.wynncraft.com/v3/guild/Some+Guild", GuildRewards.guildUrl("../player/x", "Some Guild"));
	}
}
