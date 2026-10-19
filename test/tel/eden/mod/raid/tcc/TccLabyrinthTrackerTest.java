package tel.eden.mod.raid.tcc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TccLabyrinthTrackerTest {

	@BeforeEach
	public void setup() {
		TccLabyrinthTracker.reset();
		TccLabyrinthTracker.setDebugForceShow(true);
	}

	@Test
	public void testGolemCountAndLookup() {
		assertEquals(15, TccLabyrinthTracker.Golem.values().length);
		assertNotNull(TccLabyrinthTracker.Golem.fromId("B1"));
		assertNotNull(TccLabyrinthTracker.Golem.fromId("b2"));
		assertNotNull(TccLabyrinthTracker.Golem.fromId("Y3"));
		assertNotNull(TccLabyrinthTracker.Golem.fromId("g4"));
		assertNotNull(TccLabyrinthTracker.Golem.fromId("R5"));
		assertNull(TccLabyrinthTracker.Golem.fromId("Z99"));
	}

	@Test
	public void testCoordinateProjectionAccuracy() {
		// Spawn (10934, 3479) should project near center (302, 446)
		float[] spawnPix = TccLabyrinthTracker.worldToMapPixels(10934.0, 3479.0);
		assertEquals(300.5f, spawnPix[0], 4.0f);
		assertEquals(447.9f, spawnPix[1], 4.0f);

		// B1 (10996, 3477)
		float[] b1Pix = TccLabyrinthTracker.worldToMapPixels(10996.0, 3477.0);
		assertEquals(300.0f, b1Pix[0], 10.0f);
		assertEquals(336.0f, b1Pix[1], 10.0f);

		// Y1 (10937, 3539)
		float[] y1Pix = TccLabyrinthTracker.worldToMapPixels(10937.0, 3539.0);
		assertEquals(416.0f, y1Pix[0], 10.0f);
		assertEquals(441.0f, y1Pix[1], 10.0f);

		// G1 (10934, 3404)
		float[] g1Pix = TccLabyrinthTracker.worldToMapPixels(10934.0, 3404.0);
		assertEquals(159.0f, g1Pix[0], 12.0f);
		assertEquals(444.0f, g1Pix[1], 12.0f);

		// R1 (10889, 3481)
		float[] r1Pix = TccLabyrinthTracker.worldToMapPixels(10889.0, 3481.0);
		assertEquals(300.0f, r1Pix[0], 12.0f);
	}

	@Test
	public void testFirstPlayerWingsLockIn() {
		UUID player1 = UUID.randomUUID();
		UUID player2 = UUID.randomUUID();

		// Player 1 reaches Y = 75 (wings)
		TccLabyrinthTracker.onPeerPosition("Flyer1", player1.toString(), 10934, 75.0, 3479, 0f, false);
		assertEquals(player1, TccLabyrinthTracker.getWingsPlayerUUID());

		// Player 2 subsequently reaches Y = 80 (should NOT override player 1)
		TccLabyrinthTracker.onPeerPosition("Jumper2", player2.toString(), 10934, 80.0, 3479, 0f, false);
		assertEquals(player1, TccLabyrinthTracker.getWingsPlayerUUID());
	}

	@Test
	public void testGroundPlayerTriggersGolemButWingsDoesNot() {
		UUID flyer = UUID.randomUUID();
		UUID ground = UUID.randomUUID();

		// Lock in flyer
		TccLabyrinthTracker.onPeerPosition("Flyer", flyer.toString(), 10934, 75.0, 3479, 0f, false);

		// Flyer moves right over B1 barrier (10998, 3477) away from spawn
		TccLabyrinthTracker.onPeerPosition("Flyer", flyer.toString(), 10998, 75.0, 3477, 0f, true);
		assertFalse(TccLabyrinthTracker.getDefeatedGolems().contains("B1"));

		// Ground player before barrier (towards spawn: 10993, 3477) should NOT trigger
		TccLabyrinthTracker.onPeerPosition("Ground", ground.toString(), 10993, 45.0, 3477, 0f, false);
		assertFalse(TccLabyrinthTracker.getDefeatedGolems().contains("B1"));

		// Ground player steps past B1 barrier (10998, 3477) away from spawn -> triggers!
		TccLabyrinthTracker.onPeerPosition("Ground", ground.toString(), 10998, 45.0, 3477, 0f, false);
		assertTrue(TccLabyrinthTracker.getDefeatedGolems().contains("B1"));
	}

	@Test
	public void testTraversalZoneExtendsAwayFromSpawnNotTowards() {
		UUID player = UUID.randomUUID();

		// G1 is at 10934, 3404 (North of spawn at 10934, 3479)
		// Approaching G1 from spawn (Z = 3406, 2 blocks towards spawn) should NOT trigger
		TccLabyrinthTracker.onPeerPosition("Walker", player.toString(), 10934, 45.0, 3406, 0f, false);
		assertFalse(TccLabyrinthTracker.getDefeatedGolems().contains("G1"));

		// Moving past G1 barrier away from spawn (Z = 3401, North) triggers defeat
		TccLabyrinthTracker.onPeerPosition("Walker", player.toString(), 10934, 45.0, 3401, 0f, false);
		assertTrue(TccLabyrinthTracker.getDefeatedGolems().contains("G1"));
	}

	@Test
	public void testPartyFallsBackToRaidPartyTrackerWithoutWynntils() {
		// Wynntils isn't loaded in tests, exactly the case the fallback is for.
		tel.eden.mod.chat.RaidPartyTracker.reset();
		assertTrue(TccLabyrinthTracker.getRaidPartyMembers().isEmpty());
		try {
			tel.eden.mod.chat.RaidPartyTracker.addPartyMemberForTesting("Alice");
			tel.eden.mod.chat.RaidPartyTracker.addPartyMemberForTesting("Bob");
			assertEquals(java.util.List.of("Alice", "Bob"), TccLabyrinthTracker.getRaidPartyMembers());
		} finally {
			tel.eden.mod.chat.RaidPartyTracker.reset();
		}
	}

	@Test
	public void testFightingAgainstTheBarrierDoesNotTrigger() {
		UUID fighter = UUID.randomUUID();

		// B1's barrier point is 10996, 3477 and its hallway runs east, away from spawn. Standing
		// against the bars (slightly before, on, or just under a block past the point) while the
		// golem is still alive must not mark it dead.
		for (double x : new double[]{10995.9, 10996.0, 10996.5, 10996.9}) {
			TccLabyrinthTracker.onPeerPosition("Fighter", fighter.toString(), x, 45.0, 3477, 0f, false);
			assertFalse(TccLabyrinthTracker.getDefeatedGolems().contains("B1"), "triggered at x=" + x);
		}

		// Over a block past the barrier is only reachable once it has opened.
		TccLabyrinthTracker.onPeerPosition("Fighter", fighter.toString(), 10997.2, 45.0, 3477, 0f, false);
		assertTrue(TccLabyrinthTracker.getDefeatedGolems().contains("B1"));
	}

	@Test
	public void testExitTitleDetectionWithoutAutoReporting() {
		assertFalse(TccLabyrinthTracker.hasExitTitle());
		assertFalse(TccLabyrinthTracker.isExitReached());
		assertNull(TccLabyrinthTracker.getConfirmedExit());

		TccLabyrinthTracker.onTitle(Component.literal("Waiting for players..."), null);

		assertTrue(TccLabyrinthTracker.hasExitTitle());
		// Auto-detection is removed: title alone does NOT confirm exit!
		assertFalse(TccLabyrinthTracker.isExitReached());
		assertNull(TccLabyrinthTracker.getConfirmedExit());
	}

	@Test
	public void testReportExitDoesNotRequireTitle() {
		TccLabyrinthTracker.setDebugForceShow(true);
		TccLabyrinthTracker.setHasExitTitleForTest(false);

		// Without title, reportExit succeeds directly based on look direction
		TccLabyrinthTracker.ExitPoint reported = TccLabyrinthTracker.reportExit(10827.0, 3396.0, 0f, null, null);
		assertNotNull(reported);
		assertEquals(TccLabyrinthTracker.ExitPoint.RR3, reported);
		assertEquals(reported, TccLabyrinthTracker.getConfirmedExit());
		assertTrue(TccLabyrinthTracker.isExitReached());
	}

	@Test
	public void testLookBasedExitSelection() {
		TccLabyrinthTracker.setDebugForceShow(true);

		// 1. Player standing right at RR3 (10827, 3396)
		TccLabyrinthTracker.ExitPoint atExit = TccLabyrinthTracker.findClosestLookedAt(10827.0, 3396.0, 180f);
		assertEquals(TccLabyrinthTracker.ExitPoint.RR3, atExit);

		// 2. Wings / Center player (10934, 3479) looking East toward Blue (yaw = -90)
		TccLabyrinthTracker.ExitPoint lookingBlue = TccLabyrinthTracker.findClosestLookedAt(10934.0, 3479.0, -90f);
		assertNotNull(lookingBlue);
		assertEquals(TccLabyrinthTracker.ExitColor.BLUE, lookingBlue.color);

		// 3. Wings / Center player looking toward Yellow (South / South-East, yaw = -30)
		TccLabyrinthTracker.ExitPoint lookingYellow = TccLabyrinthTracker.findClosestLookedAt(10934.0, 3479.0, -30f);
		assertNotNull(lookingYellow);
		assertEquals(TccLabyrinthTracker.ExitColor.YELLOW, lookingYellow.color);

		// 4. Wings / Center player looking toward Green (West / North-West, yaw = 135)
		TccLabyrinthTracker.ExitPoint lookingGreen = TccLabyrinthTracker.findClosestLookedAt(10934.0, 3479.0, 135f);
		assertNotNull(lookingGreen);
		assertEquals(TccLabyrinthTracker.ExitColor.GREEN, lookingGreen.color);
	}

	@Test
	public void testExitPointsCountAndLookup() {
		assertEquals(16, TccLabyrinthTracker.ExitPoint.values().length);

		// New 16 exit names
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("RL"));
		assertEquals(TccLabyrinthTracker.ExitColor.RED, TccLabyrinthTracker.ExitPoint.fromName("RL").color);
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("RR2"));
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("RR3"));
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("RR4"));
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("RR5"));
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("Blue left 1"));
		assertEquals(TccLabyrinthTracker.ExitColor.BLUE, TccLabyrinthTracker.ExitPoint.fromName("Blue left 1").color);
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("Y straight"));
		assertEquals(TccLabyrinthTracker.ExitColor.YELLOW, TccLabyrinthTracker.ExitPoint.fromName("Y straight").color);
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("G left"));
		assertEquals(TccLabyrinthTracker.ExitColor.GREEN, TccLabyrinthTracker.ExitPoint.fromName("G left").color);

		// Legacy alias backwards compatibility
		assertEquals(TccLabyrinthTracker.ExitPoint.BLUE_RIGHT_2, TccLabyrinthTracker.ExitPoint.fromName("BLUE_TOP_RIGHT"));
		assertEquals(TccLabyrinthTracker.ExitPoint.Y_RIGHT, TccLabyrinthTracker.ExitPoint.fromName("yellow_far_right"));
		assertEquals(TccLabyrinthTracker.ExitPoint.RR2, TccLabyrinthTracker.ExitPoint.fromName("red_bottom"));
		assertNull(TccLabyrinthTracker.ExitPoint.fromName("NON_EXISTENT"));
	}

	@Test
	public void testExitConfirmationAndReset() {
		assertNull(TccLabyrinthTracker.getConfirmedExit());
		assertFalse(TccLabyrinthTracker.isExitReached());

		TccLabyrinthTracker.confirmExit(TccLabyrinthTracker.ExitPoint.Y_RIGHT, null);
		assertEquals(TccLabyrinthTracker.ExitPoint.Y_RIGHT, TccLabyrinthTracker.getConfirmedExit());
		assertTrue(TccLabyrinthTracker.isExitReached());

		TccLabyrinthTracker.reset();
		assertNull(TccLabyrinthTracker.getConfirmedExit());
		assertFalse(TccLabyrinthTracker.isExitReached());
	}

	@Test
	public void testPeerExitReachedConfirmation() {
		assertNull(TccLabyrinthTracker.getConfirmedExit());
		TccLabyrinthTracker.onPeerExitReached("Peer1", "RR2", 10804.0, 3449.0);
		assertEquals(TccLabyrinthTracker.ExitPoint.RR2, TccLabyrinthTracker.getConfirmedExit());
		assertTrue(TccLabyrinthTracker.isExitReached());

		// Peer corrects exit to BLUE_LEFT_2
		TccLabyrinthTracker.onPeerExitReached("Peer1", "BLUE_LEFT_2", 11117.0, 3474.0);
		assertEquals(TccLabyrinthTracker.ExitPoint.BLUE_LEFT_2, TccLabyrinthTracker.getConfirmedExit());
	}

	@Test
	public void testReportExitCooldown() {
		assertEquals(0L, TccLabyrinthTracker.getLastReportKeyMs());
		long now = System.currentTimeMillis();
		TccLabyrinthTracker.setLastReportKeyMsForTest(now);
		assertEquals(now, TccLabyrinthTracker.getLastReportKeyMs());

		TccLabyrinthTracker.reset();
		assertEquals(0L, TccLabyrinthTracker.getLastReportKeyMs());
	}

	@Test
	public void testResetClearsState() {
		TccLabyrinthTracker.markGolemDefeated("B1", null);
		TccLabyrinthTracker.confirmExit(TccLabyrinthTracker.ExitPoint.BLUE_RIGHT_2, null);
		TccLabyrinthTracker.setHasExitTitleForTest(true);
		TccLabyrinthTracker.setLastReportKeyMsForTest(System.currentTimeMillis());
		assertTrue(TccLabyrinthTracker.getDefeatedGolems().contains("B1"));
		assertTrue(TccLabyrinthTracker.isExitReached());
		assertTrue(TccLabyrinthTracker.hasExitTitle());
		assertTrue(TccLabyrinthTracker.getLastReportKeyMs() > 0L);
		assertEquals(TccLabyrinthTracker.ExitPoint.BLUE_RIGHT_2, TccLabyrinthTracker.getConfirmedExit());

		TccLabyrinthTracker.reset();
		assertFalse(TccLabyrinthTracker.getDefeatedGolems().contains("B1"));
		assertFalse(TccLabyrinthTracker.isExitReached());
		assertFalse(TccLabyrinthTracker.hasExitTitle());
		assertEquals(0L, TccLabyrinthTracker.getLastReportKeyMs());
		assertNull(TccLabyrinthTracker.getConfirmedExit());
		assertNull(TccLabyrinthTracker.getWingsPlayerUUID());
	}
}
