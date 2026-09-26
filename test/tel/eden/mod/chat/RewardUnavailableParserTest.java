package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

class RewardUnavailableParserTest {
	@Test
	void matchesTimestampAndPrivateUsePrefixFromObservedMessage() {
		Component message = Component.literal("14:33 \uE008\uE002 Rewards are not available at the moment, try again later...");

		assertTrue(RewardUnavailableParser.matches(message));
	}

	@Test
	void matchesCaseInsensitively() {
		assertTrue(RewardUnavailableParser.matches(Component.literal("REWARDS ARE NOT AVAILABLE AT THE MOMENT")));
	}

	@Test
	void rejectsUnrelatedMessagesAndNull() {
		assertFalse(RewardUnavailableParser.matches(Component.literal("Rewards are available.")));
		assertFalse(RewardUnavailableParser.matches(null));
	}

	@Test
	void matchesObservedOutOfStockMessageAcrossLineBreak() {
		Component message = Component.literal("Your guild does not have enough Emeralds to send a\nreward.");

		assertTrue(RewardUnavailableParser.isOutOfStock(message));
		assertFalse(RewardUnavailableParser.isOutOfStock(Component.literal("The guild has Emeralds.")));
	}
}
