package tel.eden.mod.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tel.eden.mod.chat.PlayerNameResolver;
import tel.eden.mod.guild.BackgroundContainerSession;
import tel.eden.mod.guild.GuildLogEvent;
import tel.eden.mod.reward.GuildRewards.RewardType;

/**
 * Regression coverage for {@code sendConfirmedDeduction} — the mod's own local
 * confirmation now drives a real deduction request directly (source: "automatic"
 * on the backend) instead of uploading raw Guild Log evidence for server-side
 * reconciliation. Getting the real-unit conversion wrong here would be exactly
 * the kind of over/under-deduction bug already found twice today in the old
 * settlement path — these tests exist to make sure the replacement doesn't
 * reintroduce it.
 */
class SendConfirmedDeductionTest {
	private record Request(String rewardKind, String target, int amount) {
	}

	private static GuildRewards rewardsWithSender(List<Request> captured) {
		GuildRewards rewards = new GuildRewards(new BackgroundContainerSession());
		rewards.setRewardDeductSender((rewardKind, target, amount) -> captured.add(new Request(rewardKind, target, amount)));
		return rewards;
	}

	@Test
	void sendsRealUnitsForAspects() {
		List<Request> captured = new ArrayList<>();
		GuildRewards rewards = rewardsWithSender(captured);
		// Aspects are 1 real unit per click, same as menuClicksFor/realUnitsFor.
		rewards.sendConfirmedDeduction("Alice", RewardType.ASPECT, 3);
		assertEquals(List.of(new Request("aspects", "Alice", 3)), captured);
	}

	@Test
	void sendsRealEmeraldsNotClickCounts() {
		List<Request> captured = new ArrayList<>();
		GuildRewards rewards = rewardsWithSender(captured);
		// 2 confirmed clicks must convert to 2048 real emeralds before it's sent —
		// the exact conversion RewardUnitConversionTest locks in for menuClicksFor.
		rewards.sendConfirmedDeduction("Bob", RewardType.EMERALD, 2);
		assertEquals(List.of(new Request("emeralds", "Bob", 2048)), captured);
	}

	@Test
	void tomesNeverSendADeductionRequest() {
		// Tomes have no pending balance on the backend at all.
		List<Request> captured = new ArrayList<>();
		GuildRewards rewards = rewardsWithSender(captured);
		rewards.sendConfirmedDeduction("Carol", RewardType.TOME, 5);
		assertTrue(captured.isEmpty());
	}

	@Test
	void zeroOrNegativeConfirmedSendsNothing() {
		List<Request> captured = new ArrayList<>();
		GuildRewards rewards = rewardsWithSender(captured);
		rewards.sendConfirmedDeduction("Dave", RewardType.ASPECT, 0);
		rewards.sendConfirmedDeduction("Dave", RewardType.EMERALD, -1);
		assertTrue(captured.isEmpty());
	}

	@Test
	void giveawayNeverSendsADeductionRequest() {
		// Regression: a giveaway hands out surplus rewards that were never anyone's
		// tracked pending balance, so requesting a deduction for it errors on the
		// backend ("member does not have N pending to deduct"). settlesPending=false
		// must suppress sendConfirmedDeductions entirely, regardless of how much the
		// ticker/log confirmed.
		List<Request> captured = new ArrayList<>();
		GuildRewards rewards = rewardsWithSender(captured);
		Map<String, Integer> attempted = new LinkedHashMap<>(Map.of("Eve", 5));
		Map<String, Integer> ticker = new LinkedHashMap<>(Map.of("Eve", 5));
		rewards.sendConfirmedDeductions(attempted, ticker, Map.of(), RewardType.ASPECT, false);
		assertTrue(captured.isEmpty());
	}

	@Test
	void guildLogCountsResolvesANicknamedReceiverBackToTheirAccountName() {
		// Regression: production hit a reward row for "buddy jingu" — a nickname —
		// while the payout was addressed to "Asthae", the account name. Without
		// canonicalizing the receiver the same way the giver already is, this row's
		// count was silently lost from reconciliation even after the parser was
		// fixed to stop dropping the row outright.
		GuildRewards rewards = rewardsWithSender(new ArrayList<>());
		PlayerNameResolver.recordAlias("buddy jingu", "Asthae");
		GuildLogEvent.Reward reward = new GuildLogEvent.Reward("3 seconds ago", "catboy", "buddy jingu", "emeralds", 1024);

		Map<String, Integer> counts = rewards.guildLogCounts(Map.of("Asthae", 1), RewardType.EMERALD, List.of(reward));

		assertEquals(Map.of("Asthae", 1), counts);
	}

	@Test
	void payoutSendsDeductionsForTheConfirmedAmount() {
		List<Request> captured = new ArrayList<>();
		GuildRewards rewards = rewardsWithSender(captured);
		Map<String, Integer> attempted = new LinkedHashMap<>(Map.of("Eve", 5));
		Map<String, Integer> ticker = new LinkedHashMap<>(Map.of("Eve", 3));
		Map<String, Integer> log = new LinkedHashMap<>(Map.of("Eve", 4));
		rewards.sendConfirmedDeductions(attempted, ticker, log, RewardType.ASPECT, true);
		// max(ticker=3, log=4) = 4, capped at attempted=5 -> 4.
		assertEquals(List.of(new Request("aspects", "Eve", 4)), captured);
	}
}
