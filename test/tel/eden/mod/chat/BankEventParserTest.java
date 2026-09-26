package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.junit.jupiter.api.Test;

class BankEventParserTest {
	@Test
	void parsesAWithdrawalWithQuantityAndCharges() {
		BankEvent event = BankEventParser.parse(Component.literal("PlayerName withdrew 1x Dernic Axe T12 from the Guild Bank (Everyone)")).orElseThrow();

		assertEquals("withdrawal", event.action());
		assertEquals("PlayerName", event.player());
		assertEquals(1, event.quantity());
		assertEquals("Dernic Axe T12", event.item());
		assertEquals("Everyone", event.accessTier());
	}

	@Test
	void parsesADepositWithNoExplicitQuantity() {
		BankEvent event = BankEventParser.parse(Component.literal("PlayerName deposited Oak Wood to the Guild Bank (High Ranked)")).orElseThrow();

		assertEquals("deposit", event.action());
		assertNull(event.quantity());
		assertEquals("Oak Wood", event.item());
		assertEquals("High Ranked", event.accessTier());
	}

	@Test
	void parsesALargeQuantityDeposit() {
		BankEvent event = BankEventParser.parse(Component.literal("PlayerName deposited 64x Oak Wood to the Guild Bank (High Ranked)")).orElseThrow();

		assertEquals(64, event.quantity());
		assertEquals("Oak Wood", event.item());
	}

	@Test
	void resolvesThePlayersRealNameFromHover() {
		Component player = Component.literal("catboy").withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(Component.literal("catboy's real username is FadeDave"))));
		Component message = Component.empty().append(player).append(" deposited 1x Emerald Block to the Guild Bank (Everyone)");

		BankEvent event = BankEventParser.parse(message).orElseThrow();

		assertEquals("FadeDave", event.player());
	}

	@Test
	void rejectsUnrelatedMessagesAndNull() {
		assertTrue(BankEventParser.parse(Component.literal("Just a regular guild chat line")).isEmpty());
		assertTrue(BankEventParser.parse(null).isEmpty());
	}

	@Test
	void isCandidateRequiresBothTheBankPhraseAndAVerb() {
		assertTrue(BankEventParser.isCandidate(Component.literal("X deposited Y to the Guild Bank (Everyone)")));
		assertTrue(BankEventParser.isCandidate(Component.literal("X withdrew Y from the Guild Bank (Everyone)")));
	}
}
