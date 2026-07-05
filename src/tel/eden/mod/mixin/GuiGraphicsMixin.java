package tel.eden.mod.mixin;

import net.minecraft.client.Minecraft;
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
			int idx = hoverStr.indexOf(ImagePreviewManager.HOVER_MARKER);
			if (idx != -1) {
				String url = hoverStr.substring(idx + ImagePreviewManager.HOVER_MARKER.length());
				GuiGraphics guiGraphics = (GuiGraphics) (Object) this;
				Minecraft mc = Minecraft.getInstance();
				int screenWidth = mc.getWindow().getGuiScaledWidth();
				int screenHeight = mc.getWindow().getGuiScaledHeight();

				// Kick off the download and get cached dimensions; fall back to a
				// placeholder box while the "Loading…" / "Failed…" text is showing.
				int imgWidth = ImagePreviewManager.getWidth(url);
				int imgHeight = ImagePreviewManager.getHeight(url);
				int boxW = imgWidth > 0 ? imgWidth : 100;
				int boxH = imgHeight > 0 ? imgHeight : 16;

				// Static, centred on screen — it does not follow the cursor.
				int renderX = (screenWidth - boxW) / 2;
				int renderY = (screenHeight - boxH) / 2;

				// Dark backing panel, then the image (or the loading/error text).
				guiGraphics.fill(renderX - 2, renderY - 2, renderX + boxW + 2, renderY + boxH + 2, 0xCC000000);
				ImagePreviewManager.renderPreview(guiGraphics, url, renderX, renderY);

				ci.cancel();
			}
		}
	}
}
