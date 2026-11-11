package behaviour;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import support.Failures;
import support.Output;
import support.Worlds;
import tel.eden.mod.util.BabyPlayerEmoteTracker;

/**
 * Checks that EdenMod does the right thing in a real client, in one shared world. Each check is a
 * method that gets a clean world (no entities but the player) and throws if the behaviour is wrong.
 * Add a check by writing a method and calling it from {@link #runTest}.
 */
public final class BehaviourGameTest implements FabricClientGameTest {
	/** Around the ring display. Integer summon coordinates are centred on the block, so it is at 3.5, -59, 0.5 and not 3, -59, 0. */
	private static final AABB RING_AREA = new AABB(2, -60, -1, 5, -58, 2);
	/** Generous: a two-core CI runner can leave the server seconds behind. */
	private static final int WAIT_TICKS = 600;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Output.enabled("behaviour")) {
			Failures.run("behaviour", () -> runSuite(context));
		}
	}

	private void runSuite(ClientGameTestContext context) {
		try (TestSingleplayerContext world = Worlds.open(context)) {
			TestServerContext server = world.getServer();
			server.runCommand("gamemode creative @p");

			cleanWorld(context, server);
			babyModeIgnoresDisplaysNearAMountedRider(context, server);
		}
		Output.marker("behaviour.ok");
	}

	/** Back to the player alone at the origin, so one check cannot see another's entities. */
	private static void cleanWorld(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("ride @p dismount");
		server.runCommand("kill @e[type=!player]");
		server.runCommand("tp @p 0 -60 0 0 0");
		context.waitFor(client -> client.player.position().distanceToSqr(0, -60, 0) < 1.0, WAIT_TICKS);
	}

	/**
	 * Baby mode must not treat things merely near a mounted rider as part of the rider.
	 *
	 * <p>Wynncraft draws a mount in the same packed format as an emote, so a rider counts as
	 * "active" for the whole ride. Time-trial rings and mobs that came within five blocks were then
	 * scaled about the rider's feet and appeared to shrink and sink. Run on foot, the same display
	 * is a legitimate emote companion, so the second half proves this check can tell the difference.
	 */
	private static void babyModeIgnoresDisplaysNearAMountedRider(ClientGameTestContext context, TestServerContext server) {
		server.runCommand("summon horse 0 -60 0 {Tame:1b,NoAI:1b,Tags:[\"behaviour-mount\"]}");
		// A ring-like display three blocks from the rider, not attached to anything.
		server.runCommand("summon item_display 3 -59 0 {item:{id:\"minecraft:oak_boat\",count:1}}");
		// Wait for the condition, not a number of ticks: a small CI runner can leave the server
		// seconds behind, so the client has not been told about the entities yet.
		context.waitFor(client -> !client.level.getEntitiesOfClass(Display.ItemDisplay.class, RING_AREA).isEmpty(), WAIT_TICKS);

		server.runCommand("ride @p mount @e[tag=behaviour-mount,limit=1]");
		context.waitFor(client -> client.player.getRootVehicle() != client.player, WAIT_TICKS);
		check(ownerNear(context) == null, "a mounted rider must not own a display that is only nearby");

		server.runCommand("ride @p dismount");
		context.waitFor(client -> client.player.getRootVehicle() == client.player, WAIT_TICKS);
		check(ownerNear(context) != null, "on foot, the same nearby display should still be matched as an emote companion");
	}

	/** Marks the local player as mid-emote, then asks who owns the ring display; both in one client call so the grace window cannot lapse. */
	private static AbstractClientPlayer ownerNear(ClientGameTestContext context) {
		return context.computeOnClient(client -> {
			BabyPlayerEmoteTracker.markActive(client.player.getUUID());
			List<Display.ItemDisplay> displays = client.level.getEntitiesOfClass(Display.ItemDisplay.class, RING_AREA);
			check(!displays.isEmpty(), "the ring display was not found on the client");
			Entity ring = displays.getFirst();
			return BabyPlayerEmoteTracker.findActiveOwner(ring);
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
