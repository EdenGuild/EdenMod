package tel.eden.mod.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class LenientPngTest {
	@Test
	void decodesPngsWithBrokenChecksumsLikeWynncraftsPack() throws IOException {
		BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = {0xFF112233, 0x80445566, 0x00000000, 0xFFFFFFFF, 0xFF00FF00, 0x7F0000FF};
		source.setRGB(0, 0, 3, 2, pixels, 0, 3);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(source, "png", out);
		byte[] png = out.toByteArray();
		// Break every chunk CRC and the IDAT's Adler-32 the way the pack does.
		for (int i = 8; i + 8 <= png.length;) {
			int len = (png[i] & 0xFF) << 24 | (png[i + 1] & 0xFF) << 16 | (png[i + 2] & 0xFF) << 8 | png[i + 3] & 0xFF;
			String type = new String(png, i + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
			if (type.equals("IDAT")) {
				png[i + 8 + len - 1] ^= 0x5A;
			}
			png[i + 8 + len] ^= 0x5A;
			i += 12 + len;
		}

		BufferedImage decoded = LenientPng.decode(png);
		assertEquals(3, decoded.getWidth());
		assertEquals(2, decoded.getHeight());
		for (int y = 0; y < 2; y++) {
			for (int x = 0; x < 3; x++) {
				int expected = pixels[y * 3 + x];
				int actual = decoded.getRGB(x, y);
				// Fully transparent pixels may come back with any colour.
				assertEquals(expected >>> 24 == 0 ? 0 : expected, expected >>> 24 == 0 ? actual >>> 24 : actual);
			}
		}
	}

	@Test
	void rejectsNonPngInput() {
		assertThrows(IOException.class, () -> LenientPng.decode(new byte[]{1, 2, 3}));
	}
}
