package tel.eden.mod.guild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GuildLogEventParserTest {
	@Test
	void parsesRewardRowsWithoutRetainingRawText() {
		var event = (GuildLogEvent.Reward) GuildLogEventParser.parse(new GuildLogEntry("6 hours ago (09/22/26 09:30 AM EDT)", "§7catboy rewarded 1024 Emeralds to Asthae\nfrom Guild Rewards")).orElseThrow();

		assertEquals("catboy", event.giver());
		assertEquals("Asthae", event.receiver());
		assertEquals("emeralds", event.kind());
		assertEquals(1024, event.amount());
	}

	@Test
	void parsesARealSingleLineRewardRowWithNoTrailingSuffix() {
		// Confirmed against production log text: a reward row has no second
		// "from Guild Rewards" lore line at all. Regression for the bug where every
		// real reward row was silently dropped because the pattern required it.
		var event = (GuildLogEvent.Reward) GuildLogEventParser.parse(new GuildLogEntry("1 second ago (09/24/26 06:21 AM EDT)", "§7catboy rewarded an Aspect to catboy")).orElseThrow();

		assertEquals("catboy", event.giver());
		assertEquals("catboy", event.receiver());
		assertEquals("aspects", event.kind());
		assertEquals(1, event.amount());
	}

	@Test
	void parsesAnAspectRewardRow() {
		var event = (GuildLogEvent.Reward) GuildLogEventParser.parse(new GuildLogEntry("2 hours ago (09/22/26 09:30 AM EDT)", "FadeDave rewarded an Aspect to Asthae\nfrom Guild Rewards")).orElseThrow();

		assertEquals("FadeDave", event.giver());
		assertEquals("Asthae", event.receiver());
		assertEquals("aspects", event.kind());
		assertEquals(1, event.amount());
	}

	@Test
	void parsesAQuantifiedAspectRewardRow() {
		// Raid loot elsewhere in this same menu is quantified as "Nx Aspects" — reward
		// rows plausibly use that phrasing too, not just the singular "an Aspect".
		var event = (GuildLogEvent.Reward) GuildLogEventParser.parse(new GuildLogEntry("2 hours ago (09/22/26 09:30 AM EDT)", "FadeDave rewarded 3x Aspects to Asthae\nfrom Guild Rewards")).orElseThrow();

		assertEquals("aspects", event.kind());
		assertEquals(3, event.amount());
	}

	@Test
	void parsesAQuantifiedGuildTomeRewardRow() {
		var event = (GuildLogEvent.Reward) GuildLogEventParser.parse(new GuildLogEntry("2 hours ago (09/22/26 09:30 AM EDT)", "FadeDave rewarded 2x Guild Tomes to Asthae\nfrom Guild Rewards")).orElseThrow();

		assertEquals("tomes", event.kind());
		assertEquals(2, event.amount());
	}

	@Test
	void parsesASingularGuildTomeRewardRow() {
		var event = (GuildLogEvent.Reward) GuildLogEventParser.parse(new GuildLogEntry("2 hours ago (09/22/26 09:30 AM EDT)", "FadeDave rewarded a Guild Tome to Asthae\nfrom Guild Rewards")).orElseThrow();

		assertEquals("tomes", event.kind());
		assertEquals(1, event.amount());
	}

	@Test
	void parsesARewardRowWhoseReceiverIsANicknameContainingASpace() {
		// Regression: production hit "catboy rewarded 1024 Emeralds to buddy jingu",
		// where "buddy jingu" is a Wynncraft nickname (nicknames may contain spaces).
		// The strict [A-Za-z0-9_]{3,16} username charset silently dropped every such
		// row as an unrecognized event, undercounting Guild Log reconciliation for
		// any nicknamed player.
		var event = (GuildLogEvent.Reward) GuildLogEventParser.parse(new GuildLogEntry("3 seconds ago (09/26/26 07:22 PM EDT)", "§7catboy rewarded 1024 Emeralds to buddy jingu")).orElseThrow();

		assertEquals("catboy", event.giver());
		assertEquals("buddy jingu", event.receiver());
		assertEquals("emeralds", event.kind());
		assertEquals(1024, event.amount());
	}

	@Test
	void parsesABankRowWhoseActorIsANicknameContainingASpace() {
		var event = (GuildLogEvent.Bank) GuildLogEventParser.parse(new GuildLogEntry("1 minute ago (09/22/26 10:30 AM EDT)", "buddy jingu deposited 1x Schist\nto the Guild Bank (High Ranked)")).orElseThrow();

		assertEquals("buddy jingu", event.actor());
	}

	@Test
	void refusesARewardRowWithAnUnrecognizedRewardPhrase() {
		assertEquals(java.util.Optional.empty(), GuildLogEventParser.parse(new GuildLogEntry("2 hours ago (09/22/26 09:30 AM EDT)", "FadeDave rewarded a Mystery Box to Asthae\nfrom Guild Rewards")));
	}

	@Test
	void parsesWrappedBankRows() {
		var event = (GuildLogEvent.Bank) GuildLogEventParser.parse(new GuildLogEntry("1 minute ago (09/22/26 10:30 AM EDT)", "YoureMomGood deposited 1x Schist\nto the Guild Bank (High Ranked)")).orElseThrow();

		assertEquals("YoureMomGood", event.actor());
		assertEquals("deposited", event.action());
		assertEquals(1, event.quantity());
		assertEquals("Schist", event.item());
		assertNull(event.charges());
		assertEquals("High Ranked", event.accessTier());
	}

	@Test
	void splitsABracketedChargesSuffixOffTheItemName() {
		// Regression: without this, a bracketed item's parsed `item` text ("X [3/3]")
		// never matches the ticker path's already-stripped item name, defeating the
		// live-ticker/Guild-Log cross-path dedup and double-reporting the withdrawal.
		var event = (GuildLogEvent.Bank) GuildLogEventParser.parse(new GuildLogEntry("1 minute ago (09/22/26 10:30 AM EDT)", "T_Dqwg withdrew 1x Embroided Paper of the Thieves [3/3]\nfrom the Guild Bank (High Ranked)")).orElseThrow();

		assertEquals("Embroided Paper of the Thieves", event.item());
		assertEquals("3/3", event.charges());
	}

	@Test
	void parsesWrappedRaidRowsWithoutRawText() {
		var event = (GuildLogEvent.Raid) GuildLogEventParser.parse(new GuildLogEntry("3 hours ago (09/22/26 01:22 PM EDT)", "Koaci finished The Wartorn Palace\nand claimed 2048x Emeralds,\n+974m Guild Experience, and\n+80 Seasonal Rating")).orElseThrow();

		assertEquals(java.util.List.of("Koaci"), event.participants());
		assertEquals("The Wartorn Palace", event.raid());
		assertEquals(2048, event.emeralds());
		assertEquals(974L, event.guildExperienceMillions());
		assertEquals(80, event.seasonalRating());
	}

	@Test
	void parsesMultipleRaidParticipantsAndAspectRewards() {
		var event = (GuildLogEvent.Raid) GuildLogEventParser.parse(new GuildLogEntry("1 minute ago (09/22/26 10:30 AM EDT)", "AllIWantForXmas, YoureMomGood, threefourteen, and IEAIAIO finished Orphion's\uE000 Nexus of Light and claimed 2x\uE001 Aspects, 2048x Emeralds, +3897m Guild Experience, and +440 Seasonal Rating")).orElseThrow();

		assertEquals(java.util.List.of("AllIWantForXmas", "YoureMomGood", "threefourteen", "IEAIAIO"), event.participants());
		assertEquals(2, event.aspects());
		assertEquals(2048, event.emeralds());
	}
}
