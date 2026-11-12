package tel.eden.mod.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.render.Gammabright;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private CrossFrameResourcePool resourcePool;

	@Shadow
	@Final
	Minecraft minecraft;

	/**
	 * Right after the world and vanilla's own post effect (the creeper and spider views) are done, and before
	 * the HUD is drawn on top.
	 */
	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
	private void edenmod$gammabright(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		Gammabright.apply(this.minecraft, this.resourcePool);
	}
}
