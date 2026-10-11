package tel.eden.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tel.eden.mod.gui.BackgroundProgressScreen;

/**
 * Dynamically lessens the menu background blurriness setting when a background container
 * automation modal (such as /eden dump, /eden gift, or ally management) is active.
 */
@Mixin(Options.class)
public class OptionsMixin {
	@Inject(method = "getMenuBackgroundBlurriness", at = @At("RETURN"), cancellable = true)
	private void eden_lessenBlurriness(CallbackInfoReturnable<Integer> cir) {
		int current = cir.getReturnValue();
		if (current <= 0) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.screen instanceof BackgroundProgressScreen progressScreen) {
			cir.setReturnValue(Math.min(current, progressScreen.lessenedBlurRadius()));
		}
	}
}
