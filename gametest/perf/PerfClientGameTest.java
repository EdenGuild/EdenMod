package perf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import support.Background;
import support.Failures;
import support.Output;
import support.Worlds;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;

/**
 * Renders scenes that stress EdenMod's per-frame hooks in a real client and fails the build
 * if any of them gets disproportionately expensive. See gametest/README.md.
 *
 * <p>Each scene mirrors something Wynncraft does to the renderer that a vanilla world does
 * not: hundreds of display entities, full inventories of near-identical potions, piles of
 * dropped items. Add a scene when a new hook runs once per entity, item or slot per frame.
 */
public final class PerfClientGameTest implements FabricClientGameTest {
	private static final int WARMUP_FRAMES = 150;
	/**
	 * Starting the JFR recording costs the render thread a handful of slow frames (the first ones of the idle
	 * scene were 38, 25, 22, 21, 20 ms on a CI runner, then normal). They are not the mod's, so the frames after
	 * the recording starts are skipped before counting begins.
	 */
	private static final int SETTLE_FRAMES = 60;
	// Software rasterisation is native time, which the measurement excludes, so a small window costs
	// a fraction of the CPU without changing what is measured.
	private static final int WINDOW_WIDTH = 640;
	private static final int WINDOW_HEIGHT = 360;
	private static final int DISPLAYS = 400;
	private static final int GHOSTS = 60;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Output.enabled("perf")) {
			Failures.run("perf", () -> runSuite(context));
		}
	}

	private void runSuite(ClientGameTestContext context) {
		Path outDir = Output.dir();
		FrameProbe probe = new FrameProbe();
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> probe.onFrame());
		List<PhaseResult> results = new ArrayList<>();
		boolean softwareRendering;

		try (TestSingleplayerContext world = Worlds.open(context)) {
			TestServerContext server = world.getServer();
			server.runCommand("gamemode creative @p");
			server.runCommand("tp @p 0 -60 0 0 0");
			server.runCommand("time set noon");
			server.runCommand("gamerule advance_time false");
			server.runCommand("gamerule spawn_mobs false");
			context.getInput().resizeWindow(WINDOW_WIDTH, WINDOW_HEIGHT);
			softwareRendering = Background.isSoftwareRendering(context);
			context.runOnClient(client -> {
				client.options.enableVsync().set(false);
				client.options.framerateLimit().set(260);
				client.options.hideGui = false;
			});

			results.add(measure(context, probe, outDir, "idle", 800));

			summonDisplays(server);
			context.waitTicks(20);
			context.runOnClient(client -> EdenModClient.instance().config().babyPlayers = false);
			results.add(measure(context, probe, outDir, "displays", 1000));

			context.runOnClient(client -> EdenModClient.instance().config().babyPlayers = true);
			results.add(measure(context, probe, outDir, "displays-baby-mode", 1500));

			// Wynncraft's ghosts: lots of other players, each of which baby mode has to classify.
			List<Integer> ghosts = spawnGhosts(context, GHOSTS);
			results.add(measure(context, probe, outDir, "ghost-players-baby-mode", 1500));
			removeGhosts(context, ghosts);
			context.runOnClient(client -> EdenModClient.instance().config().babyPlayers = false);

			fillInventory(server);
			context.waitTicks(10);
			context.runOnClient(client -> client.setScreen(new InventoryScreen(client.player)));
			context.waitTicks(5);
			results.add(measure(context, probe, outDir, "inventory-of-potions", 1000));
			context.runOnClient(client -> client.setScreen(null));

			enableGroundItemRule(context);
			summonGroundItems(server);
			context.waitTicks(20);
			results.add(measure(context, probe, outDir, "ground-items", 600));
		}

		write(outDir, results);
		List<String> violations = PerfBudgets.load().check(results, softwareRendering);
		if (!violations.isEmpty()) {
			throw new AssertionError("EdenMod render performance budget exceeded:\n  " + String.join("\n  ", violations) + "\nFull results: " + outDir.resolve("results.json"));
		}
	}

	/**
	 * Frames per scene follow how many Java samples the scene yields: the baby-mode scenes are noisy and
	 * need the most, ground items yields thousands of samples from a few hundred frames, and idle is
	 * expected to be about zero.
	 */
	private static PhaseResult measure(ClientGameTestContext context, FrameProbe probe, Path outDir, String name, int measuredFrames) {
		// Let JIT compilation and chunk/model uploads settle so the measured frames are steady state.
		waitForFrames(context, probe, WARMUP_FRAMES);
		AtomicReference<List<FrameProbe.Sample>> samples = new AtomicReference<>();
		JfrShare share = JfrShare.around(outDir.resolve(name + ".jfr"), () -> {
			probe.start();
			waitForFrames(context, probe, SETTLE_FRAMES);
			probe.start();
			waitForFrames(context, probe, measuredFrames);
			samples.set(probe.stop());
		});
		PhaseResult result = PhaseResult.of(name, samples.get(), share);
		System.out.printf("[perf] %-22s median %.2f ms  p99 %.2f ms  max %.2f ms  hitch x%.1f  alloc %.0f KiB/frame  edenmod %.1f%% of render-thread Java samples%n           slowest frames: %s%n", result.name(), result.medianCpuMs(), result.p99CpuMs(), result.maxCpuMs(), result.hitchRatio(), result.allocKiBPerFrame(), result.modSharePercent(), result.slowestFrames());
		// What the scene looked like, so a human can see that it really contains the entities and items.
		Output.keepScreenshot(context.takeScreenshot("perf-" + name));
		return result;
	}

	/** Frames are only produced while the test thread waits, so wait in ticks until enough have rendered. */
	private static void waitForFrames(ClientGameTestContext context, FrameProbe probe, int frames) {
		int target = probe.totalFrames() + frames;
		for (int ticks = 0; probe.totalFrames() < target; ticks++) {
			if (ticks > frames * 4) {
				throw new IllegalStateException("Only " + (probe.totalFrames() - (target - frames)) + " of " + frames + " frames rendered; is the client running?");
			}
			context.waitTick();
		}
	}

	private static void summonDisplays(TestServerContext server) {
		for (int i = 0; i < DISPLAYS; i++) {
			double x = -12 + (i % 25) * 1.0;
			double z = 4 + (i / 25) * 1.0;
			if (i % 2 == 0) {
				server.runCommand("summon text_display " + x + " -59 " + z + " {text:{text:\"Label " + i + "\"},billboard:\"center\"}");
			} else {
				server.runCommand("summon item_display " + x + " -59 " + z + " {item:{id:\"minecraft:player_head\",count:1}}");
			}
		}
	}

	/** Client-side only players, like the ghosts Wynncraft shows: they render but the server never hears of them. */
	private static List<Integer> spawnGhosts(ClientGameTestContext context, int count) {
		List<Integer> ids = new ArrayList<>();
		context.runOnClient(client -> {
			for (int i = 0; i < count; i++) {
				RemotePlayer ghost = new RemotePlayer(client.level, new GameProfile(UUID.randomUUID(), "Ghost" + i));
				// In front of the player (who faces +z), beyond the displays: players behind the camera are culled and cost nothing.
				ghost.setPos(-10 + (i % 10) * 2.0, -60, 24 + (i / 10) * 2.0);
				client.level.addEntity(ghost);
				ids.add(ghost.getId());
			}
		});
		context.waitTicks(10);
		return ids;
	}

	private static void removeGhosts(ClientGameTestContext context, List<Integer> ids) {
		context.runOnClient(client -> ids.forEach(id -> client.level.removeEntity(id, Entity.RemovalReason.DISCARDED)));
	}

	private static void fillInventory(TestServerContext server) {
		for (int slot = 0; slot < 36; slot++) {
			String base = slot % 3 == 0 ? "minecraft:diamond_axe" : "minecraft:potion";
			server.runCommand("item replace entity @p container." + slot + " with " + base + "[custom_name={text:\"Perf item " + slot + "\",color:\"gold\"},lore=[{text:\"Tier 3\",color:\"gray\"},{text:\"Heals 120 HP over 8s\",color:\"green\"},{text:\"Uses remaining: 3/3\",color:\"dark_gray\"}]]");
		}
	}

	private static void enableGroundItemRule(ClientGameTestContext context) {
		context.runOnClient(client -> {
			BridgeConfig config = EdenModClient.instance().config();
			BridgeConfig.GroundItemVisibilityRule rule = new BridgeConfig.GroundItemVisibilityRule();
			rule.nameContains = "perf";
			rule.size = 1.5f;
			config.groundItemVisibility = true;
			config.groundItemVisibilityRules = new ArrayList<>(List.of(rule));
		});
	}

	private static void summonGroundItems(TestServerContext server) {
		for (int i = 0; i < 120; i++) {
			double x = -6 + (i % 12) * 1.2;
			double z = 6 + (i / 12) * 1.2;
			server.runCommand("summon item " + x + " -60 " + z + " {Item:{id:\"minecraft:potion\",count:1,components:{custom_name:{text:\"Perf drop " + i + "\"}}},PickupDelay:32767s,Age:-32768s}");
		}
	}

	private static void write(Path outDir, List<PhaseResult> results) {
		try {
			Files.createDirectories(outDir);
			Files.writeString(outDir.resolve("results.json"), GSON.toJson(results));
		} catch (IOException e) {
			throw new IllegalStateException("Could not write perf results", e);
		}
	}
}
