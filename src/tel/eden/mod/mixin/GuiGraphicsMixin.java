package tel.eden.mod.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.chat.ImagePreviewManager;

@Mixin(GuiGraphics.class)
public class GuiGraphicsMixin {

	@Inject(method = "renderComponentHoverEffect", at = @At("HEAD"), cancellable = true)
	private void onRenderComponentHoverEffect(Font font, Style style, int mouseX, int mouseY, CallbackInfo ci) {
		if (style != null && style.getHoverEvent() instanceof net.minecraft.network.chat.HoverEvent.ShowText st) {
			String hoverStr = st.value().getString();
			int idx = hoverStr.indexOf("[EDEN_IMG]");
			if (idx != -1) {
				String url = hoverStr.substring(idx + 10);
				ImagePreviewManager.renderPreview((GuiGraphics) (Object) this, url, mouseX, mouseY);
				ci.cancel();
			}
		}
	}
}
