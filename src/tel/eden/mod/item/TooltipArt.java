package tel.eden.mod.item;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Wynncraft's tooltip art (rarity frames, requirement diamonds, icons), read at render
 * time from the resource pack the server sent. Nothing is bundled with EdenMod; when the
 * pack isn't loaded each lookup returns null and the card falls back to drawn shapes.
 */
final class TooltipArt {
	/** Opens a path under {@code assets/minecraft/}, or returns null when it doesn't exist. */
	interface Opener {
		InputStream open(String path) throws IOException;
	}

	private static final Pattern BORDER = Pattern.compile("\"(left|top|right|bottom)\"\\s*:\\s*(\\d+)");
	private static final Map<String, BufferedImage> CACHE = new ConcurrentHashMap<>();
	private static volatile Opener opener = TooltipArt::openFromGame;

	private TooltipArt() {
	}

	/** The texture at {@code textures/<path>}, or null. Only successful loads are cached. */
	static BufferedImage texture(String path) {
		BufferedImage cached = CACHE.get(path);
		if (cached != null) {
			return cached;
		}
		try (InputStream in = opener.open("textures/" + path)) {
			if (in == null) {
				return null;
			}
			BufferedImage image = LenientPng.decode(in.readAllBytes());
			CACHE.put(path, image);
			return image;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Nine-slice borders (left, top, right, bottom) from a texture's {@code .mcmeta}, or the given default. */
	static int[] nineSliceBorder(String path, int[] fallback) {
		try (InputStream in = opener.open("textures/" + path + ".mcmeta")) {
			if (in == null) {
				return fallback;
			}
			int[] border = fallback.clone();
			Matcher m = BORDER.matcher(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			while (m.find()) {
				int value = Integer.parseInt(m.group(2));
				switch (m.group(1)) {
					case "left" -> border[0] = value;
					case "top" -> border[1] = value;
					case "right" -> border[2] = value;
					default -> border[3] = value;
				}
			}
			return border;
		} catch (IOException | RuntimeException e) {
			return fallback;
		}
	}

	static void useOpenerForTesting(Opener testOpener) {
		opener = testOpener;
		CACHE.clear();
	}

	private static InputStream openFromGame(String path) throws IOException {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return null;
		}
		var resource = mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath("minecraft", path));
		return resource.isPresent() ? resource.get().open() : null;
	}
}
