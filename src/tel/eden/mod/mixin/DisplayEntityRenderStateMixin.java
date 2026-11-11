package tel.eden.mod.mixin;

import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import tel.eden.mod.render.EdenDisplayState;

@Mixin(DisplayEntityRenderState.class)
public abstract class DisplayEntityRenderStateMixin implements EdenDisplayState {
	@Unique
	private Display edenmod$sourceDisplay;

	@Override
	public Display edenmod$getSourceDisplay() {
		return edenmod$sourceDisplay;
	}

	@Override
	public void edenmod$setSourceDisplay(Display display) {
		this.edenmod$sourceDisplay = display;
	}
}
