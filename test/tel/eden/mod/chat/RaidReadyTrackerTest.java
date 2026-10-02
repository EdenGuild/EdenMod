package tel.eden.mod.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.ChatFormatting;
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

		RaidReadyTracker.onSystemChat(Component.literal("Zasper0W/riptide guy is ready!"));
		assertTrue(RaidReadyTracker.readyPlayersForTesting().contains("zasper0w"));

		RaidReadyTracker.onSystemChat(Component.literal("Tawnyy/20 is no longer ready!"));
		assertFalse(RaidReadyTracker.readyPlayersForTesting().contains("tawnyy"));
		assertTrue(RaidReadyTracker.readyPlayersForTesting().contains("youremomgood"));
		assertTrue(RaidReadyTracker.readyPlayersForTesting().contains("zasper0w"));
	}

	@Test
	void resetsOnNewRaidPromptAndMarksStarterReady() {
		RaidReadyTracker.onSystemChat(Component.literal("YoureMomGood is ready!"));
		RaidReadyTracker.onSystemChat(Component.literal("Tawnyy/20 is ready!"));
		assertEquals(2, RaidReadyTracker.readyPlayersForTesting().size());

		RaidReadyTracker.onSystemChat(Component.literal("Bynt would like to start Orphion's Nexus of Light!"));
		assertEquals(1, RaidReadyTracker.readyPlayersForTesting().size());
		assertTrue(RaidReadyTracker.readyPlayersForTesting().contains("bynt"));

		// Wynncraft starter with nickname: PlayerLeader/PlayerNick
		RaidReadyTracker.onSystemChat(Component.literal("  PlayerLeader/PlayerNick would like to start The Nameless Anomaly!"));
		assertEquals(1, RaidReadyTracker.readyPlayersForTesting().size());
		assertTrue(RaidReadyTracker.readyPlayersForTesting().contains("playerleader"));
	}

	@Test
	void parsesPawnComponentColorsCorrectly() {
		// 2 ready (green), 1 unready (gray), 1 empty (dark gray)
		Component component = Component.empty().append(Component.literal("- Players: ")).append(Component.literal("♙").withStyle(ChatFormatting.GREEN)).append(Component.literal("♙").withStyle(ChatFormatting.GREEN)).append(Component.literal("♙").withStyle(ChatFormatting.GRAY)).append(Component.literal("♙").withStyle(ChatFormatting.DARK_GRAY));

		RaidReadyTracker.PawnStatus status = RaidReadyTracker.parsePawnComponent(component);
		assertNotNull(status);
		assertEquals(2, status.ready());
		assertEquals(1, status.unready());
		assertEquals(1, status.empty());
		assertEquals(3, status.partySize());
		assertEquals(4, status.totalPieces());
	}

	@Test
	void parsesPawnComponentLegacyColorsCorrectly() {
		// 3 ready (green), 1 unready (gray) via legacy formatting codes
		Component component = Component.literal("- Players: §a♙§a♙§a♙§7♙");

		RaidReadyTracker.PawnStatus status = RaidReadyTracker.parsePawnComponent(component);
		assertNotNull(status);
		assertEquals(3, status.ready());
		assertEquals(1, status.unready());
		assertEquals(0, status.empty());
		assertEquals(4, status.partySize());
	}

	@Test
	void parsesWynncraftPawnGlyphComponent() {
		// Exact Wynncraft format: 1 ready, 1 unready, 2 empty using \uE085
		Component component = Component.empty().append(Component.literal("- Players: ")).append(Component.literal("\uE085").withStyle(ChatFormatting.GREEN)).append(Component.literal("\uE085").withStyle(ChatFormatting.GRAY)).append(Component.literal("\uE085").withStyle(ChatFormatting.DARK_GRAY)).append(Component.literal("\uE085").withStyle(ChatFormatting.DARK_GRAY));

		RaidReadyTracker.PawnStatus status = RaidReadyTracker.parsePawnComponent(component);
		assertNotNull(status);
		assertEquals(1, status.ready());
		assertEquals(1, status.unready());
		assertEquals(2, status.empty());
		assertEquals(2, status.partySize());
		assertEquals(4, status.totalPieces());
	}

	@Test
	void parsesWynncraftRawScoreboardLine() {
		// Exact Wynncraft line from user logs: 1 ready (§a), 1 unready (§7), 2 empty (§8)
		Component component = Component.literal("§e- §7Players: §a\uE085§7\uE085§8\uE085\uE085");

		RaidReadyTracker.PawnStatus status = RaidReadyTracker.parsePawnComponent(component);
		assertNotNull(status);
		assertEquals(1, status.ready());
		assertEquals(1, status.unready());
		assertEquals(2, status.empty());
		assertEquals(2, status.partySize());
		assertEquals(4, status.totalPieces());
	}

	@Test
	void debugStateReturnsUsefulDiagnostics() {
		List<String> lines = RaidReadyTracker.debugState();
		assertFalse(lines.isEmpty());
		assertTrue(lines.get(0).contains("Raid Ready Tracker"));
	}

	@Test
	void formatsWaitDurationCorrectly() {
		assertEquals("30 seconds", RaidReadyTracker.formatWaitDuration(30));
		assertEquals("1 minute", RaidReadyTracker.formatWaitDuration(60));
		assertEquals("2 minutes", RaidReadyTracker.formatWaitDuration(120));
		assertEquals("1 minute 30 seconds", RaidReadyTracker.formatWaitDuration(90));
		assertEquals("2 minutes 15 seconds", RaidReadyTracker.formatWaitDuration(135));
		assertEquals("1 second", RaidReadyTracker.formatWaitDuration(1));
	}
}
