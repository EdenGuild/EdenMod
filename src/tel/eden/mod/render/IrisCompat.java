package tel.eden.mod.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.RenderPipelines;
import tel.eden.mod.EdenModClient;

/**
 * Makes our world-space gizmos show up when an Iris shader pack is active.
 *
 * <p>Iris replaces the vanilla programs with the pack's own, but only for the render pipelines it
 * knows about. Gizmo lines are on that list; the filled quads (the territory wall) are not, so under
 * a shader pack they were drawn with the wrong program and never showed up. Iris's public API lets a
 * mod say which pack program an unknown pipeline should use, which is what this does. It is reflective
 * so the mod neither needs Iris at compile time nor fails to load without it.
 */
public final class IrisCompat {
	private IrisCompat() {
	}

	/** Plain colored geometry has no texture or lighting, so the pack's basic program fits it. */
	private static final String PROGRAM = "BASIC";

	public static void register() {
		if (!FabricLoader.getInstance().isModLoaded("iris")) {
			return;
		}
		try {
			Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Class<?> programClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram");
			Object api = apiClass.getMethod("getInstance").invoke(null);
			Method assign = apiClass.getMethod("assignPipeline", RenderPipeline.class, programClass);
			Object program = programClass.getField(PROGRAM).get(null);
			// Gizmo rectangles are drawn with DEBUG_FILLED_BOX; DEBUG_QUADS is its sibling for other debug quads.
			assign.invoke(api, RenderPipelines.DEBUG_FILLED_BOX, program);
			assign.invoke(api, RenderPipelines.DEBUG_QUADS, program);
			EdenModClient.LOGGER.info("Iris found: territory walls assigned to the shader pack's {} program", PROGRAM);
		} catch (ReflectiveOperationException | LinkageError e) {
			EdenModClient.LOGGER.warn("Iris found, but its API did not match; territory walls may be invisible under shaders", e);
		}
	}
}
