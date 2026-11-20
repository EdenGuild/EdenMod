package tel.eden.mod.item;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A small PNG decoder that ignores chunk CRCs and the zlib checksum. Wynncraft's
 * resource pack ships PNGs with those deliberately broken; Minecraft's own reader
 * accepts them, but {@code ImageIO} does not. Supports every non-interlaced colour
 * type and bit depth the pack uses.
 */
final class LenientPng {
	private static final int MAX_PIXELS = 4096 * 4096;

	private LenientPng() {
	}

	static BufferedImage decode(byte[] b) throws IOException {
		if (b.length < 8 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') {
			throw new IOException("not a PNG");
		}
		int width = 0;
		int height = 0;
		int depth = 0;
		int color = 0;
		byte[] palette = new byte[0];
		byte[] alpha = new byte[0];
		ByteArrayOutputStream idat = new ByteArrayOutputStream();
		int i = 8;
		while (i + 8 <= b.length) {
			int len = readInt(b, i);
			String type = new String(b, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
			int start = i + 8;
			if (len < 0 || start + len > b.length) {
				throw new IOException("truncated chunk " + type);
			}
			switch (type) {
				case "IHDR" -> {
					width = readInt(b, start);
					height = readInt(b, start + 4);
					depth = b[start + 8] & 0xFF;
					color = b[start + 9] & 0xFF;
					if (b[start + 12] != 0) {
						throw new IOException("interlaced PNGs are not supported");
					}
				}
				case "PLTE" -> palette = java.util.Arrays.copyOfRange(b, start, start + len);
				case "tRNS" -> alpha = java.util.Arrays.copyOfRange(b, start, start + len);
				case "IDAT" -> idat.write(b, start, len);
				default -> {
				}
			}
			if (type.equals("IEND")) {
				break;
			}
			i = start + len + 4;
		}
		if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
			throw new IOException("bad PNG size " + width + "x" + height);
		}

		int channels = switch (color) {
			case 0, 3 -> 1;
			case 2 -> 3;
			case 4 -> 2;
			case 6 -> 4;
			default -> throw new IOException("bad colour type " + color);
		};
		int bitsPerPixel = channels * depth;
		int stride = (width * bitsPerPixel + 7) / 8;
		int filterStep = Math.max(1, bitsPerPixel / 8);
		byte[] raw = inflate(idat.toByteArray(), height * (stride + 1));

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		byte[] prev = new byte[stride];
		byte[] row = new byte[stride];
		int maxSample = (1 << Math.min(depth, 8)) - 1;
		for (int y = 0; y < height; y++) {
			int base = y * (stride + 1);
			unfilter(raw[base], raw, base + 1, row, prev, filterStep);
			for (int x = 0; x < width; x++) {
				int argb;
				if (color == 3) {
					int index = sample(row, x * channels, depth);
					int p = index * 3;
					int r = p + 2 < palette.length ? palette[p] & 0xFF : 0;
					int g = p + 2 < palette.length ? palette[p + 1] & 0xFF : 0;
					int bl = p + 2 < palette.length ? palette[p + 2] & 0xFF : 0;
					int a = index < alpha.length ? alpha[index] & 0xFF : 255;
					argb = a << 24 | r << 16 | g << 8 | bl;
				} else {
					int[] s = new int[channels];
					for (int c = 0; c < channels; c++) {
						s[c] = sample(row, x * channels + c, depth) * 255 / maxSample;
					}
					argb = switch (color) {
						case 0 -> 0xFF000000 | s[0] << 16 | s[0] << 8 | s[0];
						case 2 -> 0xFF000000 | s[0] << 16 | s[1] << 8 | s[2];
						case 4 -> s[1] << 24 | s[0] << 16 | s[0] << 8 | s[0];
						default -> s[3] << 24 | s[0] << 16 | s[1] << 8 | s[2];
					};
				}
				image.setRGB(x, y, argb);
			}
			byte[] swap = prev;
			prev = row;
			row = swap;
		}
		return image;
	}

	private static byte[] inflate(byte[] zlib, int expected) throws IOException {
		if (zlib.length < 2) {
			throw new IOException("empty image data");
		}
		// Skip the 2-byte zlib header and never check the trailing Adler-32.
		Inflater inflater = new Inflater(true);
		try {
			inflater.setInput(zlib, 2, zlib.length - 2);
			byte[] out = new byte[expected];
			int off = 0;
			while (off < expected && !inflater.finished() && !inflater.needsInput()) {
				off += inflater.inflate(out, off, expected - off);
			}
			if (off < expected) {
				throw new IOException("image data too short");
			}
			return out;
		} catch (DataFormatException e) {
			throw new IOException("bad image data", e);
		} finally {
			inflater.end();
		}
	}

	private static void unfilter(byte filter, byte[] raw, int from, byte[] row, byte[] prev, int step) throws IOException {
		for (int x = 0; x < row.length; x++) {
			int cur = raw[from + x] & 0xFF;
			int a = x >= step ? row[x - step] & 0xFF : 0;
			int up = prev[x] & 0xFF;
			int c = x >= step ? prev[x - step] & 0xFF : 0;
			int v = switch (filter) {
				case 0 -> cur;
				case 1 -> cur + a;
				case 2 -> cur + up;
				case 3 -> cur + ((a + up) >> 1);
				case 4 -> cur + paeth(a, up, c);
				default -> throw new IOException("bad filter " + filter);
			};
			row[x] = (byte) v;
		}
	}

	private static int paeth(int a, int b, int c) {
		int p = a + b - c;
		int pa = Math.abs(p - a);
		int pb = Math.abs(p - b);
		int pc = Math.abs(p - c);
		return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
	}

	/** The {@code index}-th sample of a row, for any bit depth (16-bit keeps the high byte). */
	private static int sample(byte[] row, int index, int depth) {
		return switch (depth) {
			case 8 -> row[index] & 0xFF;
			case 16 -> row[index * 2] & 0xFF;
			default -> {
				int bit = index * depth;
				yield (row[bit / 8] & 0xFF) >> (8 - depth - bit % 8) & ((1 << depth) - 1);
			}
		};
	}

	private static int readInt(byte[] b, int i) {
		return (b[i] & 0xFF) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | b[i + 3] & 0xFF;
	}
}
