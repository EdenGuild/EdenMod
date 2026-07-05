package tel.eden.mod.chat;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ImagePreviewManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("edenmod");

	/**
	 * Sentinel prefixed onto an image link's hover text so the hover-render mixin can
	 * recognise it and swap the tooltip for an inline preview. Shared here so the
	 * producer ({@link DiscordChatFormatter}) and the consumer
	 * ({@code GuiGraphicsMixin}) can never drift apart.
	 */
	public static final String HOVER_MARKER = "[EDEN_IMG]";

	// Only download previews from Discord's own CDN. Anything else is left as a
	// plain link so a crafted "…​.png" in chat can't make every hovering player
	// fetch an attacker-controlled URL (which would leak their IP).
	private static final int CONNECT_TIMEOUT_MS = 10_000;
	private static final int READ_TIMEOUT_MS = 10_000;

	// Cap the number of decoded previews kept in GPU memory for a session; the
	// oldest is evicted (and its texture released) once the cap is exceeded.
	private static final int MAX_CACHED = 30;

	private static final ConcurrentHashMap<String, PreviewState> states = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Identifier> textures = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, NativeImage> pendingImages = new ConcurrentHashMap<>();

	private static final ConcurrentHashMap<String, Integer> renderWidths = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Integer> renderHeights = new ConcurrentHashMap<>();

	// The source image's own pixel dimensions, kept to normalise the blit UVs so
	// the full image is sampled and scaled into the (smaller) render rectangle.
	private static final ConcurrentHashMap<String, Integer> sourceWidths = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Integer> sourceHeights = new ConcurrentHashMap<>();

	// Insertion order of loaded previews, for oldest-first LRU eviction.
	private static final ConcurrentLinkedDeque<String> loadOrder = new ConcurrentLinkedDeque<>();

	private enum PreviewState {
		DOWNLOADING, LOADED, ERROR
	}

	public static int getWidth(String url) {
		PreviewState state = states.get(url);
		if (state == PreviewState.LOADED) {
			return renderWidths.getOrDefault(url, 100);
		} else if (state == PreviewState.DOWNLOADING || state == PreviewState.ERROR) {
			return 100; // placeholder width
		}
		return 0; // Not requested yet
	}

	public static int getHeight(String url) {
		PreviewState state = states.get(url);
		if (state == PreviewState.LOADED) {
			return renderHeights.getOrDefault(url, 16);
		} else if (state == PreviewState.DOWNLOADING || state == PreviewState.ERROR) {
			return 16;
		}
		return 0;
	}

	public static void renderPreview(GuiGraphics guiGraphics, String url, int x, int y) {
		PreviewState state = states.computeIfAbsent(url, k -> {
			downloadImage(url);
			return PreviewState.DOWNLOADING;
		});

		if (state == PreviewState.DOWNLOADING && pendingImages.containsKey(url)) {
			NativeImage img = pendingImages.remove(url);
			if (img != null) {
				int imgWidth = img.getWidth();
				int imgHeight = img.getHeight();

				int pct = tel.eden.mod.EdenModClient.instance().config().imagePreviewSize;
				com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
				int maxWidth = (int) (window.getGuiScaledWidth() * pct / 100.0);
				int maxHeight = (int) (window.getGuiScaledHeight() * pct / 100.0);
				float scale = 1.0f;
				if (imgWidth > maxWidth || imgHeight > maxHeight) {
					scale = Math.min((float) maxWidth / imgWidth, (float) maxHeight / imgHeight);
				}

				renderWidths.put(url, (int) (imgWidth * scale));
				renderHeights.put(url, (int) (imgHeight * scale));
				sourceWidths.put(url, imgWidth);
				sourceHeights.put(url, imgHeight);

				DynamicTexture texture = new DynamicTexture(() -> "edenmod", img);
				Identifier loc = Identifier.parse("edenmod:preview_" + Math.abs(url.hashCode()));
				Minecraft.getInstance().getTextureManager().register(loc, texture);
				textures.put(url, loc);
				states.put(url, PreviewState.LOADED);
				state = PreviewState.LOADED;
				trackAndEvict(url);
			}
		}

		if (state == PreviewState.LOADED) {
			Identifier loc = textures.get(url);
			if (loc != null) {
				int renderWidth = renderWidths.getOrDefault(url, 100);
				int renderHeight = renderHeights.getOrDefault(url, 100);
				int srcWidth = sourceWidths.getOrDefault(url, renderWidth);
				int srcHeight = sourceHeights.getOrDefault(url, renderHeight);
				guiGraphics.blit(RenderPipelines.GUI_TEXTURED, loc, x, y, 0.0f, 0.0f, renderWidth, renderHeight, srcWidth, srcHeight, srcWidth, srcHeight);
			}
		} else if (state == PreviewState.DOWNLOADING) {
			guiGraphics.drawString(Minecraft.getInstance().font, "Loading preview...", x, y, 0xFFFFFF);
		} else if (state == PreviewState.ERROR) {
			guiGraphics.drawString(Minecraft.getInstance().font, "Failed to load image", x, y, 0xFF5555);
		}
	}

	/** Record a freshly loaded preview and evict the oldest ones past the cap (render thread). */
	private static void trackAndEvict(String url) {
		loadOrder.addLast(url);
		while (loadOrder.size() > MAX_CACHED) {
			String oldest = loadOrder.pollFirst();
			if (oldest == null || oldest.equals(url)) {
				break;
			}
			evict(oldest);
		}
	}

	private static void evict(String url) {
		states.remove(url);
		renderWidths.remove(url);
		renderHeights.remove(url);
		sourceWidths.remove(url);
		sourceHeights.remove(url);
		pendingImages.remove(url);
		Identifier loc = textures.remove(url);
		if (loc != null) {
			Minecraft.getInstance().getTextureManager().release(loc);
		}
	}

	private static void downloadImage(String urlString) {
		if (!isAllowedHost(urlString)) {
			LOGGER.warn("Refusing image preview from non-Discord host: {}", urlString);
			states.put(urlString, PreviewState.ERROR);
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				URL url = URI.create(urlString).toURL();
				java.net.URLConnection conn = url.openConnection();
				conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
				conn.setReadTimeout(READ_TIMEOUT_MS);
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

	/** True only for Discord's CDN hosts; everything else is refused (see field comment). */
	public static boolean isAllowedHost(String urlString) {
		try {
			String host = URI.create(urlString).getHost();
			if (host == null) {
				return false;
			}
			host = host.toLowerCase(Locale.ROOT);
			return host.equals("cdn.discordapp.com") || host.equals("media.discordapp.net") || host.endsWith(".discordapp.com") || host.endsWith(".discordapp.net");
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
