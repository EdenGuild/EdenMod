package tel.eden.mod.render;

/**
 * Accessor interface duck-typed onto {@link net.minecraft.client.renderer.entity.state.ItemEntityRenderState}
 * to carry the dropped-item scale from extraction to submission. See {@link EdenDisplayState}
 * for why this lives on the state rather than in a map keyed by it.
 */
public interface EdenItemScaleState {
	float edenmod$getScale();

	void edenmod$setScale(float scale);

	/** The scale recorded on {@code state}, or {@code 1.0f} if it has none. */
	static float scaleOf(Object state) {
		return state instanceof EdenItemScaleState ext ? ext.edenmod$getScale() : 1.0f;
	}
}
