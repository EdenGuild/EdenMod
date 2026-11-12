package tel.eden.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tel.eden.mod.gui.EdenBackdrop;

/**
 * Lessens the menu background blurriness setting while an EdenMod screen is open, so every one of them
 * has the same light blur whatever the player's setting is (see {@link EdenBackdrop}).
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
		if (mc != null && mc.screen instanceof EdenBackdrop backdrop) {
			cir.setReturnValue(Math.min(current, backdrop.lessenedBlurRadius()));
		}
	}
}
