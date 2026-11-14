package tel.eden.mod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.gui.ProgressScreenGuard;
import tel.eden.mod.guild.BackgroundContainerSession;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	/** While a background run's progress screen is held, other screen switches leave it where it is; see {@link ProgressScreenGuard}. */
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void edenmod$holdProgressScreen(Screen screen, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		Screen held = ProgressScreenGuard.replacementFor(mc, screen);
		if (held == null) {
			return;
		}
		ci.cancel();
		if (mc.screen != held) {
			mc.setScreen(held);
		}
		if (screen instanceof AbstractContainerScreen<?>) {
			// The server has already opened that menu for us; close it rather than leave it open unseen.
			BackgroundContainerSession.closeContainerKeepingScreen(mc);
		}
	}
}
