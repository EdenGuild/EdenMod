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
	public void testReportExitRequiresTitle() {
		// Simulates being in Room 2, but title not yet received
		TccLabyrinthTracker.setDebugForceShow(true);
		TccLabyrinthTracker.setHasExitTitleForTest(false);

		// Without title, reportExit returns null and does not confirm
		assertNull(TccLabyrinthTracker.reportExit(11120.0, 3500.0, null, null));
		assertNull(TccLabyrinthTracker.getConfirmedExit());
		assertFalse(TccLabyrinthTracker.isExitReached());

		// When title is received
		TccLabyrinthTracker.onTitle(Component.literal("Waiting for players..."), null);
		assertTrue(TccLabyrinthTracker.hasExitTitle());

		// Standing at spawn (140+ pixels away from any exit) is rejected
		assertNull(TccLabyrinthTracker.reportExit(10934.0, 3479.0, null, null));
		assertNull(TccLabyrinthTracker.getConfirmedExit());

		// Standing near an exit successfully reports it
		TccLabyrinthTracker.ExitPoint reported = TccLabyrinthTracker.reportExit(11120.0, 3500.0, null, null);
		assertNotNull(reported);
		assertEquals(reported, TccLabyrinthTracker.getConfirmedExit());
		assertTrue(TccLabyrinthTracker.isExitReached());
	}

	@Test
	public void testReportExitCorrectionOnRepress() {
		TccLabyrinthTracker.onTitle(Component.literal("Waiting for players..."), null);
		assertTrue(TccLabyrinthTracker.hasExitTitle());

		// Initially near Blue Top Right
		TccLabyrinthTracker.ExitPoint first = TccLabyrinthTracker.reportExit(11120.0, 3500.0, null, null);
		assertNotNull(first);
		assertEquals(first, TccLabyrinthTracker.getConfirmedExit());

		// Player mislabeled or was near another exit (Yellow Upper / South)
		TccLabyrinthTracker.ExitPoint corrected = TccLabyrinthTracker.reportExit(11050.0, 3570.0, null, null);
		assertNotNull(corrected);
		assertEquals(corrected, TccLabyrinthTracker.getConfirmedExit());
		assertEquals(TccLabyrinthTracker.ExitPoint.YELLOW_UPPER, corrected);
		assertTrue(first != corrected);
	}

	@Test
	public void testExitPointsCountAndLookup() {
		assertEquals(16, TccLabyrinthTracker.ExitPoint.values().length);
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("BLUE_TOP_RIGHT"));
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("yellow_far_right"));
		assertNotNull(TccLabyrinthTracker.ExitPoint.fromName("red_bottom"));
		assertNull(TccLabyrinthTracker.ExitPoint.fromName("NON_EXISTENT"));

		// Match near Yellow Far Right (509, 260)
		TccLabyrinthTracker.ExitPoint closest = TccLabyrinthTracker.ExitPoint.findClosest(510f, 262f);
		assertEquals(TccLabyrinthTracker.ExitPoint.YELLOW_FAR_RIGHT, closest);
	}

	@Test
	public void testExitConfirmationAndReset() {
		assertNull(TccLabyrinthTracker.getConfirmedExit());
		assertFalse(TccLabyrinthTracker.isExitReached());

		TccLabyrinthTracker.confirmExit(TccLabyrinthTracker.ExitPoint.YELLOW_FAR_RIGHT, null);
		assertEquals(TccLabyrinthTracker.ExitPoint.YELLOW_FAR_RIGHT, TccLabyrinthTracker.getConfirmedExit());
		assertTrue(TccLabyrinthTracker.isExitReached());

		TccLabyrinthTracker.reset();
		assertNull(TccLabyrinthTracker.getConfirmedExit());
		assertFalse(TccLabyrinthTracker.isExitReached());
	}

	@Test
	public void testPeerExitReachedConfirmation() {
		assertNull(TccLabyrinthTracker.getConfirmedExit());
		TccLabyrinthTracker.onPeerExitReached("Peer1", "RED_BOTTOM", 10850.0, 3350.0);
		assertEquals(TccLabyrinthTracker.ExitPoint.RED_BOTTOM, TccLabyrinthTracker.getConfirmedExit());
		assertTrue(TccLabyrinthTracker.isExitReached());

		// Peer corrects exit to BLUE_TOP_LEFT
		TccLabyrinthTracker.onPeerExitReached("Peer1", "BLUE_TOP_LEFT", 11100.0, 3450.0);
		assertEquals(TccLabyrinthTracker.ExitPoint.BLUE_TOP_LEFT, TccLabyrinthTracker.getConfirmedExit());
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
		TccLabyrinthTracker.confirmExit(TccLabyrinthTracker.ExitPoint.BLUE_TOP_RIGHT, null);
		TccLabyrinthTracker.setHasExitTitleForTest(true);
		TccLabyrinthTracker.setLastReportKeyMsForTest(System.currentTimeMillis());
		assertTrue(TccLabyrinthTracker.getDefeatedGolems().contains("B1"));
		assertTrue(TccLabyrinthTracker.isExitReached());
		assertTrue(TccLabyrinthTracker.hasExitTitle());
		assertTrue(TccLabyrinthTracker.getLastReportKeyMs() > 0L);
		assertEquals(TccLabyrinthTracker.ExitPoint.BLUE_TOP_RIGHT, TccLabyrinthTracker.getConfirmedExit());

		TccLabyrinthTracker.reset();
		assertFalse(TccLabyrinthTracker.getDefeatedGolems().contains("B1"));
		assertFalse(TccLabyrinthTracker.isExitReached());
		assertFalse(TccLabyrinthTracker.hasExitTitle());
		assertEquals(0L, TccLabyrinthTracker.getLastReportKeyMs());
		assertNull(TccLabyrinthTracker.getConfirmedExit());
		assertNull(TccLabyrinthTracker.getWingsPlayerUUID());
	}
}
