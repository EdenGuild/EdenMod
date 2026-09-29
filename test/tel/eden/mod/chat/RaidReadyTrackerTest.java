package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RaidReadyTrackerTest {

	@BeforeEach
	void setUp() {
		RaidReadyTracker.reset();
	}

	@Test
	void extractsRealNameFromVariousNickFormats() {
		Component dummy = Component.empty();

		// RealIGN/nickname format
		assertEquals("Tawnyy", RaidReadyTracker.extractRealName(dummy, "Tawnyy/20"));
		assertEquals("Zasper0W", RaidReadyTracker.extractRealName(dummy, "Zasper0W/riptide guy"));

		// Plain IGN
		assertEquals("YoureMomGood", RaidReadyTracker.extractRealName(dummy, "YoureMomGood"));

		// IGN(nickname)
		assertEquals("PlayerOne", RaidReadyTracker.extractRealName(dummy, "PlayerOne(MyNickname)"));

		// nickname(IGN) where nickname is invalid IGN (e.g. 20)
		assertEquals("RealPlayer", RaidReadyTracker.extractRealName(dummy, "20(RealPlayer)"));

		// nickname(IGN) where alias is known in PlayerNameResolver
		PlayerNameResolver.recordAlias("SomeNick", "RealPlayer");
		assertEquals("RealPlayer", RaidReadyTracker.extractRealName(dummy, "SomeNick(RealPlayer)"));
	}

	@Test
	void tracksReadyAndUnreadyMessages() {
		RaidReadyTracker.onSystemChat(Component.literal("YoureMomGood is ready!"));
		assertTrue(RaidReadyTracker.readyPlayersForTesting().contains("youremomgood"));

		RaidReadyTracker.onSystemChat(Component.literal("Tawnyy/20 is ready!"));
		assertTrue(RaidReadyTracker.readyPlayersForTesting().contains("tawnyy"));

		RaidReadyTracker.onSystemChat(Component.literal("Tawnyy/20 is no longer ready!"));
		assertFalse(RaidReadyTracker.readyPlayersForTesting().contains("tawnyy"));
		assertTrue(RaidReadyTracker.readyPlayersForTesting().contains("youremomgood"));
	}

	@Test
	void resetsOnNewRaidPrompt() {
		RaidReadyTracker.onSystemChat(Component.literal("YoureMomGood is ready!"));
		RaidReadyTracker.onSystemChat(Component.literal("Tawnyy/20 is ready!"));
		assertEquals(2, RaidReadyTracker.readyPlayersForTesting().size());

		RaidReadyTracker.onSystemChat(Component.literal("Bynt would like to start Orphion's Nexus of Light!"));
		assertEquals(0, RaidReadyTracker.readyPlayersForTesting().size());
	}
}
