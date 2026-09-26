package tel.eden.mod.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tel.eden.mod.reward.GuildRewards.RewardType;

/**
 * Regression coverage for {@code menuClicksFor}/{@code realUnitsFor}: a batch payout
 * previously passed real emerald units (e.g. 2048, matching the "2048 emeralds owed"
 * shown in the payout table) straight through as a menu-click count, which would have
 * tried to click ~1024x too many times (2048 clicks instead of 2) for any emerald
 * batch payout — aspects/tomes were unaffected since one click is worth exactly one
 * real unit for those.
 */
class RewardUnitConversionTest {
	@Test
	void aspectsAndTomesNeedNoScaling() {
		assertEquals(5, GuildRewards.menuClicksFor(RewardType.ASPECT, 5));
		assertEquals(5, GuildRewards.realUnitsFor(RewardType.ASPECT, 5));
		assertEquals(7, GuildRewards.menuClicksFor(RewardType.TOME, 7));
		assertEquals(7, GuildRewards.realUnitsFor(RewardType.TOME, 7));
	}

	@Test
	void emeraldsConvertByTheMenuUnit() {
		// The exact scenario reported live: "/eden gift fadedave emerald 2" (2 clicks)
		// hands out 2048 real emeralds. A payout table showing "2048 emeralds owed"
		// must convert back down to 2 clicks before it drives the menu.
		assertEquals(2, GuildRewards.menuClicksFor(RewardType.EMERALD, 2048));
		assertEquals(2048, GuildRewards.realUnitsFor(RewardType.EMERALD, 2));
	}

	@Test
	void menuClicksForTruncatesALeftoverBelowOneClick() {
		// A pending balance that isn't a clean multiple of one click's worth (real
		// production data has always been exact multiples, but this must degrade
		// safely rather than round up into an over-gift if that ever isn't true).
		assertEquals(1, GuildRewards.menuClicksFor(RewardType.EMERALD, 1500));
		assertEquals(0, GuildRewards.menuClicksFor(RewardType.EMERALD, 500));
	}

	@Test
	void roundTripsForAnExactMultiple() {
		int clicks = GuildRewards.menuClicksFor(RewardType.EMERALD, 10240);
		assertEquals(10240, GuildRewards.realUnitsFor(RewardType.EMERALD, clicks));
	}
}
