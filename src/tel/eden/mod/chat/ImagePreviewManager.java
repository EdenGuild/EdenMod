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

	// The source image's own pixel dimensions, kept to normalise the blit UVs and to
	// derive the live on-screen size (see renderSize) as the image is scaled down.
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
			return renderSize(url)[0];
		} else if (state == PreviewState.DOWNLOADING || state == PreviewState.ERROR) {
			return 100; // placeholder width
		}
		return 0; // Not requested yet
	}

	public static int getHeight(String url) {
		PreviewState state = states.get(url);
		if (state == PreviewState.LOADED) {
			return renderSize(url)[1];
		} else if (state == PreviewState.DOWNLOADING || state == PreviewState.ERROR) {
			return 16;
		}
		return 0;
	}

	/**
	 * The on-screen ``{width, height}`` for a loaded preview, computed live from the
	 * source dimensions, the current window size, and the current Image Preview Size
	 * config — so moving the slider resizes previews immediately, cached ones included.
	 */
	private static int[] renderSize(String url) {
		int srcW = sourceWidths.getOrDefault(url, 0);
		int srcH = sourceHeights.getOrDefault(url, 0);
		if (srcW <= 0 || srcH <= 0) {
			return new int[]{100, 16};
		}
		int pct = tel.eden.mod.EdenModClient.instance().config().imagePreviewSize;
		com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
		int maxWidth = (int) (window.getGuiScaledWidth() * pct / 100.0);
		int maxHeight = (int) (window.getGuiScaledHeight() * pct / 100.0);
		float scale = 1.0f;
		if (srcW > maxWidth || srcH > maxHeight) {
			scale = Math.min((float) maxWidth / srcW, (float) maxHeight / srcH);
		}
		return new int[]{Math.max(1, (int) (srcW * scale)), Math.max(1, (int) (srcH * scale))};
	}

	public static void renderPreview(GuiGraphics guiGraphics, String url, int x, int y) {
		PreviewState state = states.computeIfAbsent(url, k -> {
			downloadImage(url);
			return PreviewState.DOWNLOADING;
		});

		if (state == PreviewState.DOWNLOADING && pendingImages.containsKey(url)) {
			NativeImage img = pendingImages.remove(url);
			if (img != null) {
				// Keep only the source dimensions; the on-screen size is computed live
				// in renderSize() so the config slider takes effect immediately.
				sourceWidths.put(url, img.getWidth());
				sourceHeights.put(url, img.getHeight());

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
				int[] size = renderSize(url);
				int srcWidth = sourceWidths.getOrDefault(url, size[0]);
				int srcHeight = sourceHeights.getOrDefault(url, size[1]);
				guiGraphics.blit(RenderPipelines.GUI_TEXTURED, loc, x, y, 0.0f, 0.0f, size[0], size[1], srcWidth, srcHeight, srcWidth, srcHeight);
			}
		} else if (state == PreviewState.DOWNLOADING) {
			guiGraphics.drawString(Minecraft.getInstance().font, "Loading preview...", x, y, 0xFFFFFFFF);
		} else if (state == PreviewState.ERROR) {
			guiGraphics.drawString(Minecraft.getInstance().font, "Failed to load image", x, y, 0xFFFF5555);
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
			LOGGER.warn("Refusing image preview from untrusted host: {}", urlString);
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
					NativeImage img = readImage(urlString, is);
					pendingImages.put(urlString, img);
				}
			} catch (Exception e) {
				LOGGER.error("Failed to download image preview for {}", urlString, e);
				states.put(urlString, PreviewState.ERROR);
			}
		});
	}

	/**
	 * Decode the stream to a {@link NativeImage}. STB (used by NativeImage) handles
	 * PNG, JPEG, BMP, and static GIF. For formats it can't decode (animated GIF,
	 * WebP, etc.) we buffer the bytes and fall back to Java's ImageIO, re-encoding
	 * as PNG for NativeImage to consume.
	 */
	private static NativeImage readImage(String urlString, InputStream is) throws java.io.IOException {
		// Buffer the entire stream so we can retry with a second decoder if the
		// first one fails — InputStream is not resettable in general.
		byte[] data = is.readAllBytes();

		// Fast path: NativeImage (STB) handles PNG, JPEG, BMP natively.
		try {
			return NativeImage.read(new java.io.ByteArrayInputStream(data));
		} catch (Exception ignored) {
			// STB couldn't decode it (GIF, WebP, etc.) — try ImageIO next.
		}

		// Fallback: ImageIO supports GIF (first frame) and any format a JRE
		// plugin provides. Re-encode the decoded frame as PNG for NativeImage.
		java.awt.image.BufferedImage frame = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
		if (frame == null) {
			throw new java.io.IOException("No decoder could read image: " + urlString);
		}
		java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(frame, "png", png);
		return NativeImage.read(new java.io.ByteArrayInputStream(png.toByteArray()));
	}

	/**
	 * Trusted image hosts whose URLs are safe to download in the background.
	 * Anything not in this list stays a plain clickable link so a crafted URL in
	 * chat can't make every hovering player fetch an attacker-controlled address.
	 */
	private static final java.util.Set<String> TRUSTED_HOSTS = java.util.Set.of(
				// Discord
				"cdn.discordapp.com", "media.discordapp.net",
				// Imgur
				"i.imgur.com", "imgur.com",
				// Tenor (Discord GIF picker)
				"media.tenor.com", "media1.tenor.com", "c.tenor.com",
				// Giphy
				"media.giphy.com", "i.giphy.com", "media0.giphy.com", "media1.giphy.com", "media2.giphy.com", "media3.giphy.com", "media4.giphy.com",
				// Wynncraft
				"cdn.wynncraft.com");

	/** True for trusted image CDNs; everything else is refused to prevent IP leaks. */
	public static boolean isAllowedHost(String urlString) {
		try {
			String host = URI.create(urlString).getHost();
			if (host == null) {
				return false;
			}
			host = host.toLowerCase(Locale.ROOT);
			if (TRUSTED_HOSTS.contains(host)) {
				return true;
			}
			// Allow any subdomain of Discord's CDN (e.g. images-ext-1.discordapp.net).
			return host.endsWith(".discordapp.com") || host.endsWith(".discordapp.net");
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
