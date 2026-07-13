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

	static {
		// ImageIO finds plugins via ServiceLoader on the *system* classloader, but
		// Fabric loads our bundled jars on its own classloader, so the plugins are
		// invisible to the default scan. Register their SPIs by hand. Reflection keeps
		// this a graceful no-op (logged) if the optional dependency is ever absent.
		registerImageIoPlugin("com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi");
		registerImageIoPlugin("com.twelvemonkeys.imageio.plugins.jpeg.JPEGImageReaderSpi");
	}

	private static void registerImageIoPlugin(String spiClassName) {
		try {
			Class<?> spiClass = Class.forName(spiClassName);
			Object spi = spiClass.getDeclaredConstructor().newInstance();
			javax.imageio.spi.IIORegistry.getDefaultInstance().registerServiceProvider(spi);
			LOGGER.info("Registered ImageIO plugin {}", spiClassName);
		} catch (Throwable t) {
			LOGGER.warn("Could not register ImageIO plugin {}: {}", spiClassName, t.toString());
		}
	}

	/**
	 * Sentinel prefixed onto an image link's hover text so the hover-render mixin can
	 * recognise it and swap the tooltip for an inline preview. Shared here so the
	 * producer ({@link DiscordChatFormatter}) and the consumer
	 * ({@code GuiGraphicsMixin}) can never drift apart.
	 */
	public static final String HOVER_MARKER = "[EDEN_IMG]";

	// Only download previews from the trusted CDNs in TRUSTED_HOSTS. Anything else is
	// left as a plain link so a crafted "…​.png" in chat can't make every hovering
	// player fetch an attacker-controlled URL (which would leak their IP).
	private static final int CONNECT_TIMEOUT_MS = 10_000;
	private static final int READ_TIMEOUT_MS = 10_000;

	// Cap a single preview's download: we buffer the whole image in memory to decode
	// it, and even trusted CDNs occasionally serve very large files. Rejected past
	// this whether the server advertises the size or not (see readBounded).
	private static final long MAX_IMAGE_BYTES = 12L * 1024 * 1024; // 12 MiB

	// Cap the HTML fetched to resolve a Tenor/Klipy share page down to its actual
	// media URL — the og:image tag is always near the top of <head>, so this is
	// generous while still bounding a malicious/huge page.
	private static final long MAX_SHARE_PAGE_BYTES = 1L * 1024 * 1024; // 1 MiB

	// Animated GIFs decode every frame up front; cap the frame count so a
	// pathological GIF can't blow up memory or the texture manager.
	private static final int MAX_GIF_FRAMES = 60;
	// Fallback per-frame delay (ms) for frames whose GIF metadata declares 0, which
	// most viewers (browsers included) treat as "use a sane default" rather than 0.
	private static final int DEFAULT_FRAME_DELAY_MS = 100;

	// Cap the number of decoded previews kept in GPU memory for a session; the
	// oldest is evicted (and its textures released) once the cap is exceeded.
	private static final int MAX_CACHED = 8;

	private static final ConcurrentHashMap<String, PreviewState> states = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Identifier[]> textures = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Frames> pendingImages = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, int[]> frameDelays = new ConcurrentHashMap<>();

	// The source image's own pixel dimensions, kept to normalise the blit UVs and to
	// derive the live on-screen size (see renderSize) as the image is scaled down.
	private static final ConcurrentHashMap<String, Integer> sourceWidths = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Integer> sourceHeights = new ConcurrentHashMap<>();

	// Insertion order of loaded previews, for oldest-first LRU eviction.
	private static final ConcurrentLinkedDeque<String> loadOrder = new ConcurrentLinkedDeque<>();

	private enum PreviewState {
		DOWNLOADING, LOADED, ERROR
	}

	/** One decoded preview: every GIF frame (a single element for non-animated images) plus its delay. */
	private static final class Frames {
		final NativeImage[] images;
		final int[] delaysMs;

		Frames(NativeImage[] images, int[] delaysMs) {
			this.images = images;
			this.delaysMs = delaysMs;
		}

		static Frames single(NativeImage image) {
			return new Frames(new NativeImage[]{image}, new int[]{0});
		}
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
			Frames frames = pendingImages.remove(url);
			if (frames != null) {
				// Keep only the source dimensions; the on-screen size is computed live
				// in renderSize() so the config slider takes effect immediately.
				NativeImage first = frames.images[0];
				sourceWidths.put(url, first.getWidth());
				sourceHeights.put(url, first.getHeight());

				Identifier[] locs = new Identifier[frames.images.length];
				for (int i = 0; i < frames.images.length; i++) {
					DynamicTexture texture = new DynamicTexture(() -> "edenmod", frames.images[i]);
					Identifier loc = Identifier.parse("edenmod:preview_" + Math.abs(url.hashCode()) + "_" + i);
					Minecraft.getInstance().getTextureManager().register(loc, texture);
					locs[i] = loc;
				}
				textures.put(url, locs);
				frameDelays.put(url, frames.delaysMs);
				states.put(url, PreviewState.LOADED);
				state = PreviewState.LOADED;
				trackAndEvict(url);
			}
		}

		if (state == PreviewState.LOADED) {
			Identifier[] locs = textures.get(url);
			if (locs != null && locs.length > 0) {
				int[] size = renderSize(url);
				int srcWidth = sourceWidths.getOrDefault(url, size[0]);
				int srcHeight = sourceHeights.getOrDefault(url, size[1]);
				Identifier loc = locs[currentFrameIndex(url, locs.length)];
				guiGraphics.blit(RenderPipelines.GUI_TEXTURED, loc, x, y, 0.0f, 0.0f, size[0], size[1], srcWidth, srcHeight, srcWidth, srcHeight);
			}
		} else if (state == PreviewState.DOWNLOADING) {
			guiGraphics.drawString(Minecraft.getInstance().font, "Loading preview...", x, y, 0xFFFFFFFF);
		} else if (state == PreviewState.ERROR) {
			guiGraphics.drawString(Minecraft.getInstance().font, "Failed to load image", x, y, 0xFFFF5555);
		}
	}

	/** Which of a GIF's frames is "now", looping on wall-clock time (no per-viewer state needed). */
	private static int currentFrameIndex(String url, int frameCount) {
		if (frameCount <= 1) {
			return 0;
		}
		int[] delays = frameDelays.get(url);
		if (delays == null || delays.length != frameCount) {
			return 0;
		}
		int total = 0;
		for (int delay : delays) {
			total += delay;
		}
		if (total <= 0) {
			return 0;
		}
		long elapsed = System.currentTimeMillis() % total;
		int acc = 0;
		for (int i = 0; i < delays.length; i++) {
			acc += delays[i];
			if (elapsed < acc) {
				return i;
			}
		}
		return delays.length - 1;
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
		frameDelays.remove(url);
		Identifier[] locs = textures.remove(url);
		if (locs != null) {
			for (Identifier loc : locs) {
				Minecraft.getInstance().getTextureManager().release(loc);
			}
		}
	}

	private static void downloadImage(String urlString) {
		if (!isPreviewable(urlString)) {
			LOGGER.warn("Refusing image preview from untrusted host: {}", urlString);
			states.put(urlString, PreviewState.ERROR);
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				String fetchUrl = urlString;
				if (isShareHost(urlString)) {
					String resolved = resolveShareImageUrl(urlString);
					if (resolved == null || !isResolvedImageHostSafe(resolved)) {
						LOGGER.warn("Could not resolve a trusted media URL from share page: {}", urlString);
						states.put(urlString, PreviewState.ERROR);
						return;
					}
					fetchUrl = resolved;
				}
				URL url = URI.create(fetchUrl).toURL();
				java.net.URLConnection conn = url.openConnection();
				conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
				conn.setReadTimeout(READ_TIMEOUT_MS);
				conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
				long declared = conn.getContentLengthLong();
				if (declared > MAX_IMAGE_BYTES) {
					LOGGER.warn("Refusing oversized image preview ({} bytes): {}", declared, urlString);
					states.put(urlString, PreviewState.ERROR);
					return;
				}
				try (InputStream is = conn.getInputStream()) {
					Frames frames = readImage(urlString, is);
					pendingImages.put(urlString, frames);
				}
			} catch (Exception e) {
				LOGGER.error("Failed to download image preview for {}", urlString, e);
				states.put(urlString, PreviewState.ERROR);
			}
		});
	}

	/**
	 * Fetch a Tenor/Klipy share page and pull the underlying media URL out of its
	 * Open Graph {@code og:image} meta tag — those links point at an HTML page, not
	 * image bytes, so they can't be downloaded directly like a CDN link. Returns
	 * {@code null} if the page couldn't be fetched or had no recognisable tag.
	 */
	private static String resolveShareImageUrl(String pageUrl) {
		try {
			URL url = URI.create(pageUrl).toURL();
			java.net.URLConnection conn = url.openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
			String html;
			try (InputStream is = conn.getInputStream()) {
				html = new String(readBounded(is, MAX_SHARE_PAGE_BYTES), java.nio.charset.StandardCharsets.UTF_8);
			}
			java.util.regex.Matcher m = OG_IMAGE_PATTERN.matcher(html);
			if (m.find()) {
				String candidate = m.group(1) != null ? m.group(1) : m.group(2);
				if (candidate != null && !candidate.isBlank()) {
					return candidate;
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to resolve share page {}: {}", pageUrl, e.toString());
		}
		return null;
	}

	// Matches <meta property="og:image" content="..."> in either attribute order.
	private static final java.util.regex.Pattern OG_IMAGE_PATTERN = java.util.regex.Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']" + "|<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+property=[\"']og:image[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * Decode the stream to {@link Frames}. Animated GIFs get every frame decoded (see
	 * {@link #decodeAnimatedGif}); everything else — including static GIF — keeps the
	 * single-frame path: STB (used by NativeImage) handles PNG, JPEG, BMP, and static
	 * GIF directly, and anything it can't decode (WebP, CMYK/progressive JPEG, …) falls
	 * back to ImageIO — trying every registered reader, including the bundled
	 * TwelveMonkeys WebP/JPEG plugins — then re-encodes the decoded frame as PNG for
	 * NativeImage to consume.
	 */
	private static Frames readImage(String urlString, InputStream is) throws java.io.IOException {
		// Buffer the stream (bounded) so we can retry with a second decoder if the
		// first one fails — InputStream is not resettable in general.
		byte[] data = readBounded(is, MAX_IMAGE_BYTES);

		if (isGif(data)) {
			Frames animated = decodeAnimatedGif(data);
			if (animated != null) {
				return animated;
			}
			// Fall through to the single-frame paths below (e.g. a 1-frame GIF, or a
			// decode failure in the animated path).
		}

		// Fast path: NativeImage (STB) handles PNG, JPEG, BMP, and (first-frame-only)
		// static GIF natively.
		try {
			return Frames.single(NativeImage.read(new java.io.ByteArrayInputStream(data)));
		} catch (Exception ignored) {
			// STB couldn't decode it (WebP, CMYK JPEG, …) — try ImageIO next.
		}

		// Fallback: try every ImageIO reader that claims the bytes until one decodes
		// a frame. This tolerates readers that recognise the format but choke on a
		// specific variant (e.g. the stock JPEG reader vs. a CMYK JPEG), and picks up
		// the bundled WebP/JPEG plugins registered in the static initializer.
		java.awt.image.BufferedImage frame = decodeWithImageIo(data);
		if (frame == null) {
			throw new java.io.IOException("No decoder could read image: " + urlString);
		}
		return Frames.single(toNativeImage(frame));
	}

	/** True if {@code data} starts with the GIF87a/GIF89a magic bytes. */
	private static boolean isGif(byte[] data) {
		return data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a';
	}

	/**
	 * Decode every frame of an animated GIF (up to {@link #MAX_GIF_FRAMES}) with its
	 * per-frame delay, via the JDK's built-in GIF reader. Returns {@code null} (rather
	 * than throwing) if the GIF only has one frame or a frame fails to decode, so the
	 * caller can fall back to the ordinary single-frame path.
	 */
	private static Frames decodeAnimatedGif(byte[] data) {
		try (javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
			if (iis == null) {
				return null;
			}
			java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReadersByFormatName("gif");
			if (!readers.hasNext()) {
				return null;
			}
			javax.imageio.ImageReader reader = readers.next();
			try {
				reader.setInput(iis, false, false);
				int frameCount = Math.min(reader.getNumImages(true), MAX_GIF_FRAMES);
				if (frameCount <= 1) {
					return null; // not actually animated — let the single-frame path handle it
				}
				NativeImage[] images = new NativeImage[frameCount];
				int[] delays = new int[frameCount];
				for (int i = 0; i < frameCount; i++) {
					images[i] = toNativeImage(reader.read(i));
					delays[i] = frameDelayMs(reader, i);
				}
				return new Frames(images, delays);
			} finally {
				reader.dispose();
			}
		} catch (Exception e) {
			LOGGER.debug("Animated GIF decode failed, falling back to a static frame: {}", e.toString());
			return null;
		}
	}

	/** The declared delay (ms) for GIF frame {@code index}, or a sane default if unset/zero. */
	private static int frameDelayMs(javax.imageio.ImageReader reader, int index) {
		try {
			javax.imageio.metadata.IIOMetadata metadata = reader.getImageMetadata(index);
			org.w3c.dom.Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
			org.w3c.dom.NodeList children = root.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				org.w3c.dom.Node node = children.item(i);
				if ("GraphicControlExtension".equals(node.getNodeName())) {
					org.w3c.dom.Node delayAttr = node.getAttributes().getNamedItem("delayTime");
					if (delayAttr != null) {
						int centiseconds = Integer.parseInt(delayAttr.getNodeValue());
						return centiseconds <= 0 ? DEFAULT_FRAME_DELAY_MS : centiseconds * 10;
					}
				}
			}
		} catch (Exception ignored) {
			// No usable metadata for this frame — use the default below.
		}
		return DEFAULT_FRAME_DELAY_MS;
	}

	/** Re-encode a decoded frame as PNG so {@link NativeImage} can consume it uniformly. */
	private static NativeImage toNativeImage(java.awt.image.BufferedImage frame) throws java.io.IOException {
		java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(frame, "png", png);
		return NativeImage.read(new java.io.ByteArrayInputStream(png.toByteArray()));
	}

	/** Read up to {@code max} bytes, throwing if the stream exceeds it (size guard). */
	private static byte[] readBounded(InputStream is, long max) throws java.io.IOException {
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		byte[] chunk = new byte[16 * 1024];
		long total = 0;
		int n;
		while ((n = is.read(chunk)) != -1) {
			total += n;
			if (total > max) {
				throw new java.io.IOException("Image exceeds " + max + "-byte preview limit");
			}
			buf.write(chunk, 0, n);
		}
		return buf.toByteArray();
	}

	/** Decode with the first ImageIO reader that succeeds, or {@code null} if none can. */
	private static java.awt.image.BufferedImage decodeWithImageIo(byte[] data) throws java.io.IOException {
		try (javax.imageio.stream.ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
			if (iis == null) {
				return null;
			}
			java.util.Iterator<javax.imageio.ImageReader> readers = javax.imageio.ImageIO.getImageReaders(iis);
			while (readers.hasNext()) {
				javax.imageio.ImageReader reader = readers.next();
				try {
					iis.seek(0);
					reader.setInput(iis, true, true);
					return reader.read(0);
				} catch (Exception e) {
					LOGGER.debug("ImageIO reader {} failed: {}", reader.getClass().getSimpleName(), e.toString());
				} finally {
					reader.dispose();
				}
			}
		}
		return null;
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

	/**
	 * Share-page hosts whose links point at an HTML page rather than image bytes
	 * (e.g. {@code tenor.com/view/...}) — {@link #resolveShareImageUrl} fetches the
	 * page and pulls the real media URL out of its {@code og:image} tag before the
	 * normal download path runs.
	 */
	private static final java.util.Set<String> SHARE_HOSTS = java.util.Set.of("tenor.com", "www.tenor.com", "klipy.com", "www.klipy.com");

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

	private static boolean isShareHost(String urlString) {
		try {
			String host = URI.create(urlString).getHost();
			return host != null && SHARE_HOSTS.contains(host.toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Whether a resolved (post-share-page) media URL is safe to actually download:
	 * either one of the already-trusted CDNs, or a same-site subdomain of the share
	 * page it came from (e.g. a Klipy CDN host under klipy.com). Keeps the same
	 * anti-SSRF discipline as {@link #isAllowedHost} instead of trusting whatever an
	 * og:image tag happens to contain.
	 */
	private static boolean isResolvedImageHostSafe(String resolvedUrl) {
		if (isAllowedHost(resolvedUrl)) {
			return true;
		}
		try {
			String host = URI.create(resolvedUrl).getHost();
			if (host == null) {
				return false;
			}
			host = host.toLowerCase(Locale.ROOT);
			for (String shareHost : SHARE_HOSTS) {
				String bare = shareHost.startsWith("www.") ? shareHost.substring(4) : shareHost;
				if (host.equals(bare) || host.endsWith("." + bare)) {
					return true;
				}
			}
		} catch (IllegalArgumentException ignored) {
			// fall through to false
		}
		return false;
	}

	/**
	 * Whether {@code urlString} can become an inline preview at all: either a direct
	 * image/gif link from a trusted CDN, or a Tenor/Klipy share-page link (resolved
	 * to its real media URL at download time, see {@link #resolveShareImageUrl}).
	 */
	public static boolean isPreviewable(String urlString) {
		if (isShareHost(urlString)) {
			return true;
		}
		return isAllowedHost(urlString) && urlString.matches("(?i).*\\.(png|jpe?g|gif|webp|bmp)(\\?.*)?$");
	}

	/**
	 * Whether {@code urlString} is a gif rather than a still image — either a direct
	 * {@code .gif} link, or a Tenor/Klipy share link (those sites are gif-only).
	 * Decided from the URL alone (no download needed) so the chat label can be picked
	 * immediately, before the async fetch even starts.
	 */
	public static boolean isGifUrl(String urlString) {
		if (isShareHost(urlString)) {
			return true;
		}
		return urlString.matches("(?i).*\\.gif(\\?.*)?$");
	}
}
