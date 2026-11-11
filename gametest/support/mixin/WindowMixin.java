package support.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In background mode the game window is created hidden, so it never flashes on screen while the
 * game loads. Hiding it from the test itself comes too late: resources take several seconds to
 * load first.
 */
@Mixin(Window.class)
abstract class WindowMixin {
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false))
	private void edenmod$createHiddenInBackgroundMode(CallbackInfo ci) {
		if (Boolean.getBoolean("edenmod.gametest.background")) {
			GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
			System.out.println("[gametest] creating the game window hidden");
		}
	}
}
