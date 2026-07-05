package tel.eden.mod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.update.UpdateChecker;
import tel.eden.mod.update.UpdateInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {
	private static final Identifier LOGO_TEXTURE = Identifier.parse("edenmod:icon.png");
	private static final int LOGO_W = 722;
	private static final int LOGO_H = 693;

	@Inject(method = "render", at = @At("RETURN"))
	private void onRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		TitleScreen screen = (TitleScreen) (Object) this;
		int screenWidth = screen.width;

		int logoW = 32;
		int logoH = logoW * LOGO_H / LOGO_W;
		int x = screenWidth - logoW - 5;
		int y = 5;
		int logoSize = logoH;

		graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, x, y, 0.0f, 0.0f, logoW, logoH, LOGO_W, LOGO_H, LOGO_W, LOGO_H);

		Minecraft mc = Minecraft.getInstance();
		String currentVer = UpdateChecker.currentVersion();
		if (currentVer == null)
			currentVer = "Unknown";

		UpdateInfo pendingUpdate = EdenModClient.instance().getPendingUpdate();
		String updateText = (pendingUpdate != null) ? "Update Available: " + pendingUpdate.version() : "Up to date";

		String text1 = "EdenMod v" + currentVer;
		String text2 = updateText;

		int textX1 = screenWidth - mc.font.width(text1) - 5;
		int textX2 = screenWidth - mc.font.width(text2) - 5;

		graphics.drawString(mc.font, text1, textX1, y + logoSize + 5, 0xFFAAAAAA);
		graphics.drawString(mc.font, text2, textX2, y + logoSize + 15, pendingUpdate != null ? 0xFF55FF55 : 0xFFAAAAAA);
	}
}
