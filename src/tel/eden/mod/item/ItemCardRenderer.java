package tel.eden.mod.item;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Renders a shared item into a lightweight PNG card. The goal is not pixel-perfect
 * Wynncraft reproduction; it is to preserve the tooltip's reading order clearly in
 * Discord and bridge contexts.
 */
public final class ItemCardRenderer {
	private static final int WIDTH = 360;
	private static final int PAD = 18;
	private static final int NAME_LINE_H = 26;
	private static final int PILL_H = 30;
	private static final int ROW_H = 22;
	private static final int DETAIL_ROW_H = 18;
	private static final int GAP = 12;
	private static final int SECTION_SUFFIX_GAP = 4;
	private static final int SECTION_SUFFIX_Y_ADJUST = 1;

	private static final Color BG = new Color(0x16, 0x16, 0x1C);
	private static final Color VALUE_POS = new Color(0x55, 0xFF, 0x55);
	private static final Color VALUE_NEG = new Color(0xFF, 0x55, 0x55);
	private static final Color STAT_NAME = new Color(0xD0, 0xD0, 0xD0);
	private static final Color SHINY = new Color(0xFF, 0xF0, 0x8A);

	private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 22);
	private static final Font PILL_FONT = new Font("SansSerif", Font.BOLD, 13);
	private static final Font STAT_FONT = new Font("SansSerif", Font.PLAIN, 15);
	private static final Font SECTION_FONT = new Font("SansSerif", Font.BOLD, 14);
	private static final Font DETAIL_FONT = new Font("SansSerif", Font.PLAIN, 14);

	private ItemCardRenderer() {
	}

	/** Render the item to PNG bytes. */
	public static byte[] render(DecodedItem item) throws IOException {
		BufferedImage image = new BufferedImage(WIDTH, measureHeight(item), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(BG);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());

			int y = PAD + 22;
			y = drawName(g, item, y);
			y = drawPill(g, item, y + 4);
			y += GAP - 2;

			// Keep the section order close to Wynncraft's tooltip: top summary, weights,
			// tracker/stats, then major IDs at the bottom. Powder rendering is intentionally
			// omitted for now: Wynntils' current chat-share decode path exposes the slot count
			// but not the actual socket contents in the items we tested, which made the image
			// imply accuracy it did not have.
			y = drawWeightings(g, item, y);
			y = addGapIfNeeded(y, !item.weightings().isEmpty(), hasTrackerSection(item) || !item.identifications().isEmpty());
			y = drawTrackerSection(g, item, y);
			y = addGapIfNeeded(y, hasTrackerSection(item), hasStatsSection(item));
			y = drawIdentifications(g, item, y);
			y = addGapIfNeeded(y, !item.identifications().isEmpty(), !item.majorIds().isEmpty());
			drawMajorIds(g, item, y);
		} finally {
			g.dispose();
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static int drawName(Graphics2D g, DecodedItem item, int y) {
		g.setFont(NAME_FONT);
		g.setColor(new Color(item.tierColor()));
		if (!item.hasOverall()) {
			for (String line : wrap(g.getFontMetrics(), item.name(), WIDTH - PAD * 2)) {
				drawCentered(g, line, y);
				y += NAME_LINE_H;
			}
			return y - 10;
		}

		String namePart = item.name();
		String percentPart = String.format("  [%.2f%%]", item.overallPercent());
		FontMetrics metrics = g.getFontMetrics();
		if (metrics.stringWidth(namePart + percentPart) <= WIDTH - PAD * 2) {
			int startX = (WIDTH - metrics.stringWidth(namePart + percentPart)) / 2;
			g.drawString(namePart, startX, y);
			g.setColor(rollColor(item.overallPercent()));
			g.drawString(percentPart, startX + metrics.stringWidth(namePart), y);
			return y + NAME_LINE_H - 10;
		}

		// Longer item names are split across centered lines so they stay readable instead
		// of shrinking or clipping against the card edge.
		for (String line : wrap(metrics, namePart, WIDTH - PAD * 2)) {
			drawCentered(g, line, y);
			y += NAME_LINE_H;
		}
		g.setColor(rollColor(item.overallPercent()));
		drawCentered(g, percentPart.trim(), y);
		return y + NAME_LINE_H - 10;
	}

	private static int drawPill(Graphics2D g, DecodedItem item, int y) {
		String typeLabel = item.type().isEmpty() ? "Item" : item.type();
		String text = item.tier().isEmpty() ? typeLabel : item.tier() + " " + typeLabel;
		g.setFont(PILL_FONT);

		int textW = g.getFontMetrics().stringWidth(text);
		int pillW = textW + 24;
		int pillX = (WIDTH - pillW) / 2;
		Color tier = new Color(item.tierColor());

		g.setColor(new Color(tier.getRed(), tier.getGreen(), tier.getBlue(), 40));
		g.fillRoundRect(pillX, y, pillW, 22, 22, 22);
		g.setColor(tier);
		g.drawRoundRect(pillX, y, pillW, 22, 22, 22);
		g.drawString(text, pillX + 12, y + 16);
		return y + PILL_H;
	}

	private static int drawWeightings(Graphics2D g, DecodedItem item, int y) {
		if (item.weightings().isEmpty()) {
			return y;
		}

		String currentSource = null;
		for (DecodedItem.Weighting weighting : item.weightings()) {
			if (!weighting.source().equals(currentSource)) {
				currentSource = weighting.source();
				g.setFont(SECTION_FONT);
				g.setColor(weightingSourceColor(currentSource));
				g.drawString(currentSource, PAD, y);
				y += ROW_H - 4;
			}

			g.setFont(STAT_FONT);
			g.setColor(STAT_NAME);
			g.drawString(weighting.scaleName() + " Scale", PAD, y);

			String percent = String.format("[%.1f%%]", weighting.percentage());
			g.setColor(rollColor(weighting.percentage()));
			g.drawString(percent, WIDTH - PAD - g.getFontMetrics().stringWidth(percent), y);
			y += ROW_H;
		}
		return y;
	}

	private static int drawIdentifications(Graphics2D g, DecodedItem item, int y) {
		if (hasStatsSection(item)) {
			drawSectionHeader(g, "Stats", item, PAD, y, new Color(item.tierColor()));
			y += ROW_H - 4;
		}

		g.setFont(STAT_FONT);
		for (DecodedItem.Identification id : item.identifications()) {
			g.setColor(STAT_NAME);
			g.drawString(id.name(), PAD, y);

			String value = id.valueText();
			String roll = id.hasRoll() ? String.format(" [%.1f%%]", id.rollPercent()) : "";
			int rollW = id.hasRoll() ? g.getFontMetrics().stringWidth(roll) : 0;
			int valueW = g.getFontMetrics().stringWidth(value);
			int valueX = WIDTH - PAD - rollW - valueW;

			g.setColor(id.positive() ? VALUE_POS : VALUE_NEG);
			g.drawString(value, valueX, y);
			if (id.hasRoll()) {
				g.setColor(rollColor(id.rollPercent()));
				g.drawString(roll, WIDTH - PAD - rollW, y);
			}
			y += ROW_H;
		}
		return y;
	}

	private static int drawTrackerSection(Graphics2D g, DecodedItem item, int y) {
		if (!hasTrackerSection(item)) {
			return y;
		}

		drawSectionHeader(g, "Tracker", item, PAD, y, SHINY);
		y += ROW_H - 4;

		DecodedItem.ShinyTracker tracker = item.shinyTracker();
		g.setFont(STAT_FONT);
		g.setColor(SHINY);
		g.drawString(tracker.name(), PAD, y);

		String value = tracker.valueText().replace("+", "");
		g.drawString(value, WIDTH - PAD - g.getFontMetrics().stringWidth(value), y);
		y += ROW_H;
		return y;
	}

	private static int drawMajorIds(Graphics2D g, DecodedItem item, int y) {
		if (item.majorIds().isEmpty()) {
			return y;
		}

		for (DecodedItem.MajorIdentification major : item.majorIds()) {
			g.setFont(SECTION_FONT);
			g.setColor(new Color(item.tierColor()));
			g.drawString(major.name(), PAD, y);
			y += ROW_H - 4;

			g.setFont(DETAIL_FONT);
			g.setColor(STAT_NAME);
			for (String line : wrap(g.getFontMetrics(), major.description(), WIDTH - PAD * 2)) {
				g.drawString(line, PAD, y);
				y += DETAIL_ROW_H;
			}
			y += 4;
		}
		return y;
	}

	private static int addGapIfNeeded(int y, boolean beforeSectionPresent, boolean afterSectionPresent) {
		return beforeSectionPresent && afterSectionPresent ? y + GAP : y;
	}

	private static boolean hasTrackerSection(DecodedItem item) {
		return item.hasShinyTracker();
	}

	private static int measureHeight(DecodedItem item) {
		// Mirror render() top-to-bottom so layout tweaks do not desync measurement from
		// drawing and create clipped cards.
		int height = PAD + measureNameHeight(item) + PILL_H + GAP - 2;
		height += measureWeightingsHeight(item);
		if (!item.weightings().isEmpty() && (hasTrackerSection(item) || hasStatsSection(item))) {
			height += GAP;
		}
		if (hasTrackerSection(item)) {
			height += ROW_H - 4;
			height += ROW_H;
		}
		if (hasStatsSection(item)) {
			height += ROW_H - 4;
		}
		if (hasTrackerSection(item) && !item.identifications().isEmpty()) {
			height += GAP;
		}
		height += item.identifications().size() * ROW_H;
		if (!item.identifications().isEmpty() && !item.majorIds().isEmpty()) {
			height += GAP;
		}
		height += measureMajorIdsHeight(item);
		return height + PAD;
	}

	private static int measureWeightingsHeight(DecodedItem item) {
		if (item.weightings().isEmpty()) {
			return 0;
		}
		int height = 0;
		String currentSource = null;
		for (DecodedItem.Weighting weighting : item.weightings()) {
			if (!weighting.source().equals(currentSource)) {
				currentSource = weighting.source();
				height += ROW_H - 4;
			}
			height += ROW_H;
		}
		return height;
	}

	private static int measureMajorIdsHeight(DecodedItem item) {
		int height = 0;
		for (DecodedItem.MajorIdentification major : item.majorIds()) {
			height += ROW_H - 4;
			height += wrappedLineCount(DETAIL_FONT, major.description()) * DETAIL_ROW_H;
			height += 4;
		}
		return height;
	}

	private static void drawSectionHeader(Graphics2D g, String title, DecodedItem item, int x, int y, Color color) {
		g.setColor(color);
		g.setFont(SECTION_FONT);
		g.drawString(title, x, y);
		if (!hasVisibleRerolls(item)) {
			return;
		}

		// Draw the reroll suffix separately so its baseline can be tuned independently;
		// the font renders bracket-heavy suffixes slightly off when the whole header is
		// drawn as a single string.
		Font suffixFont = SECTION_FONT.deriveFont(Font.PLAIN);
		g.setFont(suffixFont);
		FontMetrics titleMetrics = g.getFontMetrics(SECTION_FONT);
		String suffix = " [" + item.rerollCount() + "]";
		int suffixX = x + titleMetrics.stringWidth(title) + SECTION_SUFFIX_GAP;
		int suffixY = y + SECTION_SUFFIX_Y_ADJUST;
		g.drawString(suffix, suffixX, suffixY);
	}

	private static boolean hasStatsSection(DecodedItem item) {
		return !item.identifications().isEmpty();
	}

	private static boolean hasVisibleRerolls(DecodedItem item) {
		return item.hasRerollCount() && item.rerollCount() > 0;
	}

	private static int measureNameHeight(DecodedItem item) {
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setFont(NAME_FONT);
			FontMetrics metrics = g.getFontMetrics();
			int lineCount = wrap(metrics, item.name(), WIDTH - PAD * 2).size();
			if (lineCount == 0) {
				lineCount = 1;
			}
			boolean stackedPercent = item.hasOverall() && metrics.stringWidth(item.name() + String.format("  [%.2f%%]", item.overallPercent())) > WIDTH - PAD * 2;
			return lineCount * NAME_LINE_H + (stackedPercent ? NAME_LINE_H : 0) - 10;
		} finally {
			g.dispose();
		}
	}

	private static int wrappedLineCount(Font font, String text) {
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setFont(font);
			return wrap(g.getFontMetrics(), text, WIDTH - PAD * 2).size();
		} finally {
			g.dispose();
		}
	}

	private static List<String> wrap(FontMetrics metrics, String text, int maxWidth) {
		List<String> lines = new ArrayList<>();
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return lines;
		}

		StringBuilder line = new StringBuilder();
		for (String word : normalized.split("\\s+")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (!line.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			} else {
				line.setLength(0);
				line.append(candidate);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}

	private static void drawCentered(Graphics2D g, String text, int y) {
		g.drawString(text, (WIDTH - g.getFontMetrics().stringWidth(text)) / 2, y);
	}

	private static Color weightingSourceColor(String source) {
		return switch (source.toLowerCase(Locale.ROOT)) {
			case "nori" -> new Color(0x55, 0xFF, 0xFF);
			case "wynnpool" -> new Color(0xFF, 0xAA, 0x55);
			default -> STAT_NAME;
		};
	}

	private static Color rollColor(float percent) {
		if (percent <= 20f) {
			return new Color(0xFF, 0x55, 0x55);
		}
		if (percent < 80f) {
			return new Color(0xFF, 0xFF, 0x55);
		}
		if (percent < 95f) {
			return new Color(0x55, 0xFF, 0x55);
		}
		return new Color(0x55, 0xFF, 0xFF);
	}
}
