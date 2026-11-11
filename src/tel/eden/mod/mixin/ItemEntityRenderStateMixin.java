package tel.eden.mod.mixin;

import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import tel.eden.mod.render.EdenItemScaleState;

@Mixin(ItemEntityRenderState.class)
public abstract class ItemEntityRenderStateMixin implements EdenItemScaleState {
	@Unique
	private float edenmod$scale = 1.0f;

	@Override
	public float edenmod$getScale() {
		return edenmod$scale;
	}

	@Override
	public void edenmod$setScale(float scale) {
		this.edenmod$scale = scale;
	}
}
