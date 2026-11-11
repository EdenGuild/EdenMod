package tel.eden.mod.render;

import net.minecraft.world.entity.Display;

/**
 * Accessor interface duck-typed onto {@link net.minecraft.client.renderer.entity.state.DisplayEntityRenderState}
 * to carry the source entity to {@code submit}, which only receives the render state.
 *
 * <p>Minecraft creates a fresh render state per display per frame, so the link must live on
 * the state itself. A side map keyed by state costs a resize or a stale-key cleanup burst
 * on the render thread for every display entity on screen.
 */
public interface EdenDisplayState {
	Display edenmod$getSourceDisplay();

	void edenmod$setSourceDisplay(Display display);

	/** The source display recorded on {@code state}, or {@code null} if it has none. */
	static Display sourceOf(Object state) {
		return state instanceof EdenDisplayState ext ? ext.edenmod$getSourceDisplay() : null;
	}
}
