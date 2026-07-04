package tel.eden.mod.chat;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImagePreviewManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");
	private static final ConcurrentHashMap<String, PreviewState> states = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Identifier> textures = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, NativeImage> pendingImages = new ConcurrentHashMap<>();

	private enum PreviewState {
		DOWNLOADING, LOADED, ERROR
	}

	public static void renderPreview(GuiGraphics guiGraphics, String url, int mouseX, int mouseY) {
		PreviewState state = states.computeIfAbsent(url, k -> {
			downloadImage(url);
			return PreviewState.DOWNLOADING;
		});

		if (state == PreviewState.DOWNLOADING && pendingImages.containsKey(url)) {
			NativeImage img = pendingImages.remove(url);
			if (img != null) {
				DynamicTexture texture = new DynamicTexture(() -> "edenmod", img);
				Identifier loc = Identifier.parse("edenmod:preview_" + Math.abs(url.hashCode()));
				Minecraft.getInstance().getTextureManager().register(loc, texture);
				textures.put(url, loc);
				states.put(url, PreviewState.LOADED);
				state = PreviewState.LOADED;
			}
		}

		if (state == PreviewState.LOADED) {
			Identifier loc = textures.get(url);
			if (loc != null) {
				AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(loc);
				if (texture instanceof DynamicTexture dyn) {
					NativeImage img = dyn.getPixels();
					if (img != null) {
						int imgWidth = img.getWidth();
						int imgHeight = img.getHeight();

						int maxWidth = 300;
						int maxHeight = 300;
						float scale = 1.0f;
						if (imgWidth > maxWidth || imgHeight > maxHeight) {
							scale = Math.min((float) maxWidth / imgWidth, (float) maxHeight / imgHeight);
						}

						int renderWidth = (int) (imgWidth * scale);
						int renderHeight = (int) (imgHeight * scale);

						int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
						int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

						int x = mouseX + 12;
						int y = mouseY - 12;
						if (x + renderWidth > screenWidth) {
							x = mouseX - renderWidth - 8;
						}
						if (y + renderHeight > screenHeight) {
							y = mouseY - renderHeight - 8;
						}

						guiGraphics.fill(x - 2, y - 2, x + renderWidth + 2, y + renderHeight + 2, 0xAA000000);
						guiGraphics.blit(loc, x, y, 0, 0, renderWidth, renderHeight, renderWidth, renderHeight);
					}
				}
			}
		} else if (state == PreviewState.DOWNLOADING) {
			guiGraphics.drawCenteredString(Minecraft.getInstance().font, "Loading preview...", mouseX + 12, mouseY - 12, 0xFFFFFF);
		} else if (state == PreviewState.ERROR) {
			guiGraphics.drawCenteredString(Minecraft.getInstance().font, "Failed to load image", mouseX + 12, mouseY - 12, 0xFF5555);
		}
	}

	private static void downloadImage(String urlString) {
		CompletableFuture.runAsync(() -> {
			try {
				URL url = URI.create(urlString).toURL();
				java.net.URLConnection conn = url.openConnection();
				conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
				try (InputStream is = conn.getInputStream()) {
					NativeImage img = NativeImage.read(is);
					pendingImages.put(urlString, img);
				}
			} catch (Exception e) {
				LOGGER.error("Failed to download image preview for {}", urlString, e);
				states.put(urlString, PreviewState.ERROR);
			}
		});
	}
}
