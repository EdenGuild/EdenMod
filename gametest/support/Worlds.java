package support;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Opens the world every suite runs in. */
public final class Worlds {
	private static final int DEFAULT_RENDER_DISTANCE = 12;

	private Worlds() {
	}

	/**
	 * Loading a world is where a small CI runner struggles: the client redraws the loading screen as
	 * fast as it can and meshes a full render distance of chunks in software, which starves the
	 * server thread. Spawn preparation stalls ("Preparing spawn area: 16%") and the load times out
	 * ("Timeout loading world"). So load a small world at the tick rate in a tiny window, and
	 * restore the normal settings once it is ready; each suite then picks its own window size.
	 */
	public static TestSingleplayerContext open(ClientGameTestContext context) {
		Background.prepare(context);
		context.runOnClient(client -> {
			client.options.framerateLimit().set(20);
			client.options.renderDistance().set(4);
		});
		context.getInput().resizeWindow(320, 240);
		TestSingleplayerContext world = context.worldBuilder().create();
		world.getClientWorld().waitForChunksRender();
		context.runOnClient(client -> {
			client.options.framerateLimit().set(120);
			client.options.renderDistance().set(DEFAULT_RENDER_DISTANCE);
		});
		return world;
	}
}
