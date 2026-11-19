package tel.eden.mod.item;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Renders a shared item into a PNG card styled like Wynncraft's own item tooltip:
 * rarity frame and gradient, header tags, base stats, skill point requirements,
 * identifications and lore, all in Minecraft's pixel font.
 */
public final class ItemCardRenderer {
	private static final int WIDTH = 480;
	private static final int PAD = 28;
	private static final int RIGHT = WIDTH - PAD;
	private static final int CONTENT_W = RIGHT - PAD;
	/** Pixel-font scale for body text: 8px glyphs drawn at 16px. */
	private static final int S = 2;
	private static final int LINE = 8 * S + 6;

	private static final Color WHITE = new Color(0xFCFCFC);
	private static final Color MUTED = new Color(0xAEAEAE);
	private static final Color FAINT = new Color(0x5C5C5C);
	private static final Color LORE = new Color(0xAAAAAA);
	private static final Color GOOD = new Color(0x83F7C6);
	private static final Color BAD = new Color(0xF78383);
	private static final Color SHINY = new Color(0xFFF08A);
	private static final String[] SKILL_LABELS = {"STR", "DEX", "INT", "DEF", "AGI"};
	/** Scale for Wynncraft's tooltip art, matching the body text's 2x pixels. */
	private static final int ART = 2;
	private static final int[] DEFAULT_BORDER = {12, 20, 12, 20};
	private static final int ICON = 24;
	private static final int EMBLEM_W = 42 * ART;
	private static final int EMBLEM_H = 34 * ART;
	private static final List<String> ATTRIBUTE_ORDER = List.of("earth", "thunder", "water", "fire", "air", "neutral");
	private static final List<String> SPRITE_ORDER = List.of("helmet", "chestplate", "leggings", "boots", "bow", "dagger", "wand", "relik", "spear", "", "", "", "", "ring", "bracelet", "necklace");

	private ItemCardRenderer() {
	}

	/** Render the item to PNG bytes. */
	public static byte[] render(DecodedItem item) throws IOException {
		Theme theme = Theme.of(item);
		// Lay out once on a throwaway canvas to learn the height, then draw for real, so
		// measuring and drawing can never disagree.
		BufferedImage probe = new BufferedImage(WIDTH, 1, BufferedImage.TYPE_INT_RGB);
		Graphics2D pg = probe.createGraphics();
		int height;
		try {
			height = paint(pg, item, theme);
		} finally {
			pg.dispose();
		}

		BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			drawBackground(g, item, theme, height);
			paint(g, item, theme);
		} finally {
			g.dispose();
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	/** Draw every section top to bottom; returns the total card height. */
	private static int paint(Graphics2D g, DecodedItem item, Theme theme) {
		DecodedItem.GearDetails gear = item.gear();
		int y = 26;
		y = drawHeader(g, item, theme, y);
		if (gear != null) {
			y = drawBase(g, gear, theme, y);
		}
		y = separator(g, theme, y);
		int afterSeparator = y;

		if (!item.weightings().isEmpty()) {
			y = drawWeightings(g, item, y);
			y = afterSeparator = separator(g, theme, y);
		}

		if (gear != null) {
			y = drawSkills(g, item, theme, y);
		}
		y = drawMeta(g, item, gear, y);

		if (item.hasShinyTracker() || !item.identifications().isEmpty()) {
			// Skip the rule when nothing was drawn since the last one.
			y = y == afterSeparator ? y : separator(g, theme, y);
			y = drawTracker(g, item, y);
			y = drawIdentifications(g, item, y);
		}
		y = drawMajorIds(g, item, theme, y);
		if (item.powderSlots() > 0) {
			y += 6;
			PixelFont.draw(g, "Powder Slots", PAD, y, S, MUTED);
			int x = PAD + PixelFont.width("Powder Slots", S) + 10;
			BufferedImage socket = TooltipArt.texture("font/tooltip/socket.png");
			g.setColor(MUTED);
			for (int i = 0; i < item.powderSlots(); i++) {
				if (!cell(g, socket, 10, 10, 0, 0, x + i * 22, y - 2, 10 * ART, 10 * ART)) {
					g.drawRect(x + i * 14, y + 3, 9, 9);
				}
			}
			y += LINE;
		}
		if (gear != null && !gear.lore().isBlank()) {
			y = separator(g, theme, y);
			y = drawWrapped(g, gear.lore(), PAD, y, LORE);
		}
		return y + 20;
	}

	/** Draw a nine-slice texture over the whole card at {@link #ART} scale, stretching edges and centre. */
	private static void nineSlice(Graphics2D g, BufferedImage img, int[] border, int height) {
		int sw = img.getWidth();
		int sh = img.getHeight();
		int[] sx = {0, border[0], sw - border[2], sw};
		int[] sy = {0, border[1], sh - border[3], sh};
		int[] dx = {0, border[0] * ART, WIDTH - border[2] * ART, WIDTH};
		int[] dy = {0, border[1] * ART, height - border[3] * ART, height};
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				g.drawImage(img, dx[i], dy[j], dx[i + 1], dy[j + 1], sx[i], sy[j], sx[i + 1], sy[j + 1], null);
			}
		}
	}

	/** Draw cell (col, row) of a sprite sheet into the rectangle (x, y, w, h); false when the sheet is missing. */
	private static boolean cell(Graphics2D g, BufferedImage sheet, int cellW, int cellH, int col, int row, int x, int y, int w, int h) {
		if (sheet == null || (col + 1) * cellW > sheet.getWidth() || (row + 1) * cellH > sheet.getHeight()) {
			return false;
		}
		g.drawImage(sheet, x, y, x + w, y + h, col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH, null);
		return true;
	}

	/** A copy of a white sprite multiplied by {@code color}, as Minecraft tints font glyphs. */
	private static BufferedImage tint(BufferedImage img, Color color) {
		if (img == null) {
			return null;
		}
		BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				int p = img.getRGB(x, y);
				int r = (p >> 16 & 0xFF) * color.getRed() / 255;
				int gr = (p >> 8 & 0xFF) * color.getGreen() / 255;
				int b = (p & 0xFF) * color.getBlue() / 255;
				out.setRGB(x, y, p & 0xFF000000 | r << 16 | gr << 8 | b);
			}
		}
		return out;
	}

	private static void drawBackground(Graphics2D g, DecodedItem item, Theme theme, int height) {
		g.setColor(new Color(0x0D0D0D));
		g.fillRect(0, 0, WIDTH, height);
		String tier = item.tier().toLowerCase(Locale.ROOT).trim();
		String background = "gui/sprites/tooltip/" + tier + "_background.png";
		String frame = "gui/sprites/tooltip/" + tier + "_frame.png";
		BufferedImage bgArt = TooltipArt.texture(background);
		BufferedImage frameArt = TooltipArt.texture(frame);
		if (bgArt != null && frameArt != null) {
			nineSlice(g, bgArt, TooltipArt.nineSliceBorder(background, DEFAULT_BORDER), height);
			nineSlice(g, frameArt, TooltipArt.nineSliceBorder(frame, DEFAULT_BORDER), height);
			return;
		}
		g.setPaint(new GradientPaint(0, 0, new Color(0x0D0D0D), 0, height, theme.bg));
		g.fillRect(4, 4, WIDTH - 8, height - 8);

		// Pixel-art frame: a thick rarity border, a faint inner line and corner studs.
		g.setColor(theme.color);
		g.setStroke(new BasicStroke(3));
		g.drawRect(3, 3, WIDTH - 7, height - 7);
		g.setStroke(new BasicStroke(1));
		g.setColor(withAlpha(theme.color, 90));
		g.drawRect(9, 9, WIDTH - 19, height - 19);
		g.setColor(theme.light);
		for (int[] corner : new int[][]{{0, 0}, {WIDTH - 8, 0}, {0, height - 8}, {WIDTH - 8, height - 8}}) {
			g.fillRect(corner[0], corner[1], 8, 8);
		}
	}

	private static int drawHeader(Graphics2D g, DecodedItem item, Theme theme, int y) {
		int top = y;
		int left = drawEmblem(g, item, PAD, y) ? PAD + EMBLEM_W + 10 : PAD;
		int width = RIGHT - left;
		int nameScale = 3;
		String pct = item.hasOverall() ? String.format(Locale.ROOT, " [%.1f%%]", item.overallPercent()) : "";
		int pctW = PixelFont.width(pct, S);
		List<String> nameLines = wrap(item.name(), nameScale, width);
		boolean pctInline = nameLines.size() == 1 && PixelFont.width(item.name(), nameScale) + pctW <= width;
		for (String line : nameLines) {
			PixelFont.draw(g, line, left, y, nameScale, theme.color);
			if (pctInline) {
				PixelFont.draw(g, pct, left + PixelFont.width(line, nameScale), y + 8 * (nameScale - S), S, rollColor(item.overallPercent()));
			}
			y += 8 * nameScale + 6;
		}
		if (!pct.isEmpty() && !pctInline) {
			PixelFont.draw(g, pct.trim(), left, y, S, rollColor(item.overallPercent()));
			y += LINE;
		}

		y += 2;
		int x = left;
		if (!item.tier().isEmpty()) {
			x = drawTag(g, item.tier().toLowerCase(Locale.ROOT), x, y, theme.color) + 6;
		}
		drawTag(g, (item.type().isEmpty() ? "item" : item.type()).toLowerCase(Locale.ROOT), x, y, theme.light);
		y += 26;

		DecodedItem.GearDetails gear = item.gear();
		if (gear != null && !gear.elements().isEmpty()) {
			int w = 8 + gear.elements().size() * 18;
			String label = gear.elements().size() == 1 ? gear.elements().get(0).toLowerCase(Locale.ROOT) : "";
			if (!label.isEmpty()) {
				w += PixelFont.width(label, S) + 4;
			}
			g.setColor(theme.light);
			g.fillRect(left, y, w, 20);
			int ex = left + 6;
			BufferedImage icons = TooltipArt.texture("font/tooltip/attribute/sprite.png");
			for (String element : gear.elements()) {
				if (!cell(g, icons, 16, 16, ATTRIBUTE_ORDER.indexOf(element.toLowerCase(Locale.ROOT)), 0, ex - 1, y + 2, 16, 16)) {
					diamond(g, ex + 6, y + 10, 6, elementColor(element), Color.BLACK);
				}
				ex += 18;
			}
			if (!label.isEmpty()) {
				PixelFont.draw(g, label, ex, y + 2, S, Color.BLACK, false);
			}
			y += 26;
		}
		return Math.max(y, left > PAD ? top + EMBLEM_H : y) + 4;
	}

	/** Wynncraft's item emblem (rarity frame shape + gear type sprite); false when unavailable. */
	private static boolean drawEmblem(Graphics2D g, DecodedItem item, int x, int y) {
		DecodedItem.GearDetails gear = item.gear();
		int sprite = SPRITE_ORDER.indexOf(item.type().toLowerCase(Locale.ROOT));
		if (gear == null || gear.emblemShape() < 0 || gear.emblemVariant() < 0 || sprite < 0) {
			return false;
		}
		BufferedImage frames = TooltipArt.texture("font/tooltip/emblem/frame.png");
		BufferedImage sprites = TooltipArt.texture("font/tooltip/emblem/sprite.png");
		if (frames == null || sprites == null) {
			return false;
		}
		// The 64px frame cells keep their art within x 11-53, y 15-49; offset so it starts at (x, y).
		int fx = x - 11 * ART;
		int fy = y - 15 * ART;
		cell(g, frames, 64, 64, gear.emblemShape(), gear.emblemVariant(), fx, fy, 64 * ART, 64 * ART);
		// In game the 32px sprite cell sits 16px in and 17px down from the frame cell (font ascents 31 vs 14).
		cell(g, sprites, 32, 32, sprite % 9, sprite / 9, fx + 16 * ART, fy + 17 * ART, 32 * ART, 32 * ART);
		return true;
	}

	/** A flat rarity tag with black lowercase text; returns its right edge. */
	private static int drawTag(Graphics2D g, String text, int x, int y, Color bg) {
		PixelFont font = PixelFont.five();
		int w = font.measure(text, S) + 12;
		g.setColor(bg);
		g.fillRect(x, y, w, 20);
		font.render(g, text, x + 6, y + 2, S, Color.BLACK, false);
		return x + w;
	}

	private static int drawBase(Graphics2D g, DecodedItem.GearDetails gear, Theme theme, int y) {
		if (gear.averageDps() > 0) {
			y = drawBigStat(g, String.valueOf(gear.averageDps()), "DPS", theme, y);
		} else if (gear.health() != 0) {
			y = drawBigStat(g, (gear.health() > 0 ? "+" : "") + gear.health(), "Health", theme, y);
		}

		if (!gear.attackSpeed().isEmpty()) {
			PixelFont.draw(g, gear.attackSpeed() + " Attack Speed", PAD, y, S, MUTED);
			if (gear.hitsPerSecond() > 0) {
				String hits = String.format(Locale.ROOT, " (%s hits/s)", trimNumber(gear.hitsPerSecond()));
				PixelFont.draw(g, hits, PAD + PixelFont.width(gear.attackSpeed() + " Attack Speed", S), y, S, FAINT);
			}
			y += LINE;
		}
		if (!gear.damages().isEmpty()) {
			List<String> texts = new ArrayList<>();
			List<String> elements = new ArrayList<>();
			for (DecodedItem.Damage damage : gear.damages()) {
				texts.add(damage.low() == damage.high() ? String.valueOf(damage.low()) : damage.low() + "-" + damage.high());
				elements.add(damage.element());
			}
			y = drawMarkedRow(g, texts, elements, y);
		}
		if (!gear.defences().isEmpty()) {
			PixelFont.draw(g, "Elemental Defences", PAD, y, S, MUTED);
			y += LINE;
			List<String> texts = new ArrayList<>();
			List<String> elements = new ArrayList<>();
			for (DecodedItem.ElementValue defence : gear.defences()) {
				texts.add((defence.value() > 0 ? "+" : "") + defence.value());
				elements.add(defence.element());
			}
			y = drawMarkedRow(g, texts, elements, y);
		}
		return y;
	}

	private static int drawBigStat(Graphics2D g, String value, String unit, Theme theme, int y) {
		int big = 4;
		PixelFont.draw(g, value, PAD, y, big, theme.light);
		PixelFont.draw(g, unit, PAD + PixelFont.width(value, big) + 8, y + 8 * (big - S), S, WHITE);
		return y + 8 * big + 10;
	}

	/** A wrapping row of element icons each followed by muted text. */
	private static int drawMarkedRow(Graphics2D g, List<String> texts, List<String> elements, int y) {
		BufferedImage icons = TooltipArt.texture("font/tooltip/attribute/sprite.png");
		int x = PAD;
		for (int i = 0; i < texts.size(); i++) {
			int w = 26 + PixelFont.width(texts.get(i), S);
			if (x > PAD && x + w > RIGHT) {
				x = PAD;
				y += LINE + 4;
			}
			String element = elements.get(i);
			if (!cell(g, icons, 16, 16, ATTRIBUTE_ORDER.indexOf(element.toLowerCase(Locale.ROOT)), 0, x - 2, y - 4, ICON, ICON)) {
				diamond(g, x + 7, y + 7, 7, elementColor(element), null);
			}
			PixelFont.draw(g, texts.get(i), x + 26, y, S, MUTED);
			x += w + 18;
		}
		return y + LINE + 4;
	}

	private static int drawWeightings(Graphics2D g, DecodedItem item, int y) {
		String currentSource = null;
		for (DecodedItem.Weighting weighting : item.weightings()) {
			if (!weighting.source().equals(currentSource)) {
				currentSource = weighting.source();
				PixelFont.draw(g, currentSource, PAD, y, S, weightingSourceColor(currentSource));
				y += LINE;
			}
			PixelFont.draw(g, weighting.scaleName() + " Scale", PAD, y, S, WHITE);
			String percent = String.format(Locale.ROOT, "[%.1f%%]", weighting.percentage());
			PixelFont.draw(g, percent, RIGHT - PixelFont.width(percent, S), y, S, rollColor(weighting.percentage()));
			y += LINE;
		}
		return y;
	}

	private static int drawSkills(Graphics2D g, DecodedItem item, Theme theme, int y) {
		DecodedItem.GearDetails gear = item.gear();
		int column = CONTENT_W / SKILL_LABELS.length;
		int half = 24;
		BufferedImage frames = TooltipArt.texture("font/tooltip/requirement/frame.png");
		BufferedImage sprites = TooltipArt.texture("font/tooltip/requirement/sprite.png");
		BufferedImage marks = TooltipArt.texture("font/tooltip/requirement/linear.png");
		for (int i = 0; i < SKILL_LABELS.length; i++) {
			int value = i < gear.skills().size() ? gear.skills().get(i) : 0;
			boolean active = value > 0;
			int cx = PAD + column * i + column / 2;
			int cy = y + half + 2;
			if (frames != null && sprites != null) {
				int left = cx - 12 * ART;
				int top = cy - 12 * ART;
				cell(g, frames, 24, 24, active ? requirementFrame(item.tier()) : 7, 0, left, top, 24 * ART, 24 * ART);
				cell(g, sprites, 24, 24, i, active ? 0 : 1, left, top, 24 * ART, 24 * ART);
			} else {
				diamond(g, cx, cy, half, active ? theme.bg.brighter() : new Color(0x2A2A2A), active ? theme.color : new Color(0x4A4A4A));
				String label = SKILL_LABELS[i];
				PixelFont.draw(g, label, cx - PixelFont.width(label, S) / 2, cy - 8, S, active ? WHITE : FAINT);
			}

			String text = String.valueOf(value);
			int rowW = 16 + PixelFont.width(text, S);
			int rx = cx - rowW / 2;
			int ry = y + half * 2 + 10;
			if (!cell(g, marks, 10, 10, active ? 1 : 0, 0, rx - 6, ry - 2, 10 * ART, 10 * ART)) {
				if (active) {
					check(g, rx, ry + 2, GOOD);
				} else {
					g.setColor(new Color(0x3A3A3A));
					g.fillRect(rx + 1, ry + 3, 10, 10);
				}
			}
			PixelFont.draw(g, text, rx + 18, ry, S, active ? GOOD : FAINT);
		}
		return y + half * 2 + 10 + LINE + 6;
	}

	/** Column of {@code requirement/frame.png} for a rarity; column 7 is the disabled frame. */
	private static int requirementFrame(String tier) {
		return switch (tier.toLowerCase(Locale.ROOT).trim()) {
			case "unique" -> 1;
			case "rare" -> 2;
			case "legendary" -> 3;
			case "fabled" -> 4;
			case "mythic" -> 5;
			case "set", "crafted" -> 6;
			default -> 0;
		};
	}

	private static int drawMeta(Graphics2D g, DecodedItem item, DecodedItem.GearDetails gear, int y) {
		if (gear != null && !gear.classReq().isEmpty()) {
			y = metaRow(g, "Class Req", gear.classReq(), y);
		}
		if (gear != null && gear.level() > 0) {
			y = metaRow(g, "Combat Level", String.valueOf(gear.level()), y);
		}
		if (item.hasRerollCount() && item.rerollCount() > 0) {
			y = metaRow(g, "Rerolls", String.valueOf(item.rerollCount()), y);
		}
		return y;
	}

	private static int metaRow(Graphics2D g, String label, String value, int y) {
		if (!cell(g, TooltipArt.texture("font/tooltip/requirement/linear.png"), 10, 10, 1, 0, PAD - 4, y - 2, 10 * ART, 10 * ART)) {
			check(g, PAD, y + 2, GOOD);
		}
		PixelFont.draw(g, label, PAD + 22, y, S, WHITE);
		PixelFont.draw(g, value, RIGHT - PixelFont.width(value, S), y, S, MUTED);
		return y + LINE;
	}

	private static int drawTracker(Graphics2D g, DecodedItem item, int y) {
		if (!item.hasShinyTracker()) {
			return y;
		}
		DecodedItem.ShinyTracker tracker = item.shinyTracker();
		String value = tracker.valueText().replace("+", "");
		PixelFont.draw(g, fit(tracker.name(), CONTENT_W - PixelFont.width(value, S) - 12), PAD, y, S, SHINY);
		PixelFont.draw(g, value, RIGHT - PixelFont.width(value, S), y, S, SHINY);
		return y + LINE + 6;
	}

	private static int drawIdentifications(Graphics2D g, DecodedItem item, int y) {
		for (DecodedItem.Identification id : item.identifications()) {
			String value = id.valueText();
			String roll = id.hasRoll() ? String.format(Locale.ROOT, " [%.1f%%]", id.rollPercent()) : "";
			int rollW = PixelFont.width(roll, S);
			int valueW = PixelFont.width(value, S);
			PixelFont.draw(g, fit(id.name(), CONTENT_W - rollW - valueW - 12), PAD, y, S, WHITE);
			PixelFont.draw(g, value, RIGHT - rollW - valueW, y, S, id.positive() ? GOOD : BAD);
			if (id.hasRoll()) {
				PixelFont.draw(g, roll, RIGHT - rollW, y, S, rollColor(id.rollPercent()));
			}
			y += LINE;
		}
		return y;
	}

	private static int drawMajorIds(Graphics2D g, DecodedItem item, Theme theme, int y) {
		for (DecodedItem.MajorIdentification major : item.majorIds()) {
			y += 8;
			BufferedImage mark = tint(TooltipArt.texture("font/tooltip/identification/major.png"), theme.light);
			if (!cell(g, mark, 10, 10, 0, 0, PAD - 4, y - 2, 10 * ART, 10 * ART)) {
				diamond(g, PAD + 6, y + 7, 6, theme.light, null);
			}
			PixelFont.draw(g, major.name() + ":", PAD + 22, y, S, theme.light);
			y += LINE;
			y = drawWrapped(g, major.description(), PAD, y, LORE);
		}
		return y;
	}

	private static int drawWrapped(Graphics2D g, String text, int x, int y, Color color) {
		for (String line : wrap(text, S, RIGHT - x)) {
			PixelFont.draw(g, line, x, y, S, color);
			y += LINE;
		}
		return y;
	}

	/** A centred rule with a small diamond in the middle; returns the y below it. */
	private static int separator(Graphics2D g, Theme theme, int y) {
		int mid = y + 12;
		int cx = WIDTH / 2;
		BufferedImage line = tint(TooltipArt.texture("font/tooltip/divider/line.png"), theme.light);
		if (line != null) {
			// The divider's art fills the top 5 of its 16 rows; centre those on the rule.
			int w = line.getWidth() * ART;
			g.drawImage(line, cx - w / 2, mid - 5, w, line.getHeight() * ART, null);
			return y + 28;
		}
		g.setColor(withAlpha(theme.light, 150));
		g.fillRect(cx - 150, mid - 1, 140, 2);
		g.fillRect(cx + 10, mid - 1, 140, 2);
		diamond(g, cx, mid, 6, null, theme.light);
		return y + 28;
	}

	private static void diamond(Graphics2D g, int cx, int cy, int half, Color fill, Color outline) {
		Polygon p = new Polygon(new int[]{cx, cx + half, cx, cx - half}, new int[]{cy - half, cy, cy + half, cy}, 4);
		if (fill != null) {
			g.setColor(fill);
			g.fillPolygon(p);
		}
		if (outline != null) {
			g.setColor(outline);
			g.setStroke(new BasicStroke(2));
			g.drawPolygon(p);
			g.setStroke(new BasicStroke(1));
		}
	}

	/** A small pixel check mark whose top-left is (x, y). */
	private static void check(Graphics2D g, int x, int y, Color color) {
		g.setColor(color);
		int[][] pixels = {{0, 6}, {1, 7}, {2, 8}, {3, 9}, {4, 8}, {5, 7}, {6, 6}, {7, 5}, {8, 4}, {9, 3}, {10, 2}, {11, 1}};
		for (int[] p : pixels) {
			g.fillRect(x + p[0], y + p[1], 2, 2);
		}
	}

	/** Shorten text with ".." so it fits {@code maxWidth}. */
	private static String fit(String text, int maxWidth) {
		if (PixelFont.width(text, S) <= maxWidth) {
			return text;
		}
		String cut = text;
		while (!cut.isEmpty() && PixelFont.width(cut + "..", S) > maxWidth) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut.stripTrailing() + "..";
	}

	private static List<String> wrap(String text, int scale, int maxWidth) {
		List<String> lines = new ArrayList<>();
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return lines;
		}
		StringBuilder line = new StringBuilder();
		for (String word : normalized.split("\\s+")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (!line.isEmpty() && PixelFont.width(candidate, scale) > maxWidth) {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			} else {
				line.setLength(0);
				line.append(candidate);
			}
		}
		lines.add(line.toString());
		return lines;
	}

	private static String trimNumber(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
	}

	private static Color withAlpha(Color c, int alpha) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
	}

	private static Color elementColor(String element) {
		return switch (element.toLowerCase(Locale.ROOT)) {
			case "earth" -> new Color(0x00AA00);
			case "thunder" -> new Color(0xFFFF55);
			case "water" -> new Color(0x55FFFF);
			case "fire" -> new Color(0xFF5555);
			case "air" -> new Color(0xFFFFFF);
			default -> new Color(0xFFAA00);
		};
	}

	private static Color weightingSourceColor(String source) {
		return switch (source.toLowerCase(Locale.ROOT)) {
			case "nori" -> new Color(0x55, 0xFF, 0xFF);
			case "wynnpool" -> new Color(0xFF, 0xAA, 0x55);
			default -> WHITE;
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

	/** Wynncraft's tooltip palette per rarity: name colour, light accent, gradient end. */
	private record Theme(Color color, Color light, Color bg) {
		static Theme of(DecodedItem item) {
			return switch (item.tier().toLowerCase(Locale.ROOT).trim()) {
				case "unique" -> new Theme(new Color(0xFFFF55), new Color(0xFDF2B6), new Color(0x3E2D1A));
				case "rare" -> new Theme(new Color(0xFF55FF), new Color(0xFEC3F1), new Color(0x311A5E));
				case "legendary" -> new Theme(new Color(0x55FFFF), new Color(0xBEF6F6), new Color(0x081D5A));
				case "fabled" -> new Theme(new Color(0xFF5555), new Color(0xFFC4C4), new Color(0x4D0F20));
				case "mythic" -> new Theme(new Color(0xC80DB1), new Color(0xEDB7E7), new Color(0x53075C));
				case "set" -> new Theme(new Color(0x55FF55), new Color(0x7FEB8E), new Color(0x143714));
				case "crafted" -> new Theme(new Color(0x00BCD4), new Color(0xA8E6EF), new Color(0x142637));
				case "normal" -> new Theme(Color.WHITE, new Color(0xCCCCCC), new Color(0x303030));
				default -> new Theme(new Color(item.tierColor()), new Color(0xCCCCCC), new Color(0x303030));
			};
		}
	}

	/**
	 * A Minecraft-style bitmap font: a 16x16 grid of glyphs with printable ASCII at its
	 * usual code points. Other characters, or a missing sheet, fall back to AWT.
	 */
	static final class PixelFont {
		private static final PixelFont ASCII = new PixelFont(loadAscii());
		private static final Font FALLBACK = new Font("SansSerif", Font.PLAIN, 9);
		private static volatile PixelFont five;

		private final BufferedImage sheet;
		private final int cell;
		private final int[] widths;

		private PixelFont(BufferedImage sheet) {
			this.sheet = sheet;
			this.cell = sheet == null ? 8 : sheet.getWidth() / 16;
			this.widths = glyphWidths(sheet, cell);
		}

		/** Whether Minecraft's default font loaded from the game jar. */
		static boolean available() {
			return ASCII.sheet != null;
		}

		/** Wynncraft's small "five" font used for tooltip tags, or the default font without the pack. */
		static PixelFont five() {
			BufferedImage sheet = TooltipArt.texture("font/language/five.png");
			if (sheet == null) {
				return ASCII;
			}
			PixelFont cached = five;
			if (cached == null || cached.sheet != sheet) {
				cached = new PixelFont(sheet);
				five = cached;
			}
			return cached;
		}

		static int width(String text, int scale) {
			return ASCII.measure(text, scale);
		}

		static void draw(Graphics2D g, String text, int x, int top, int scale, Color color) {
			ASCII.render(g, text, x, top, scale, color, true);
		}

		static void draw(Graphics2D g, String text, int x, int top, int scale, Color color, boolean shadow) {
			ASCII.render(g, text, x, top, scale, color, shadow);
		}

		int measure(String text, int scale) {
			int w = 0;
			for (int i = 0; i < text.length(); i++) {
				w += advance(text.charAt(i), scale);
			}
			return w;
		}

		/** Draw text whose glyph cell top is {@code top}, optionally with Minecraft's drop shadow. */
		void render(Graphics2D g, String text, int x, int top, int scale, Color color, boolean shadow) {
			if (shadow) {
				renderPlain(g, text, x + scale, top + scale, scale, new Color(color.getRed() / 4, color.getGreen() / 4, color.getBlue() / 4));
			}
			renderPlain(g, text, x, top, scale, color);
		}

		private void renderPlain(Graphics2D g, String text, int x, int top, int scale, Color color) {
			g.setColor(color);
			int unit = cell / 8;
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				if (isBitmap(c)) {
					int sx = (c % 16) * cell;
					int sy = (c / 16) * cell;
					for (int py = 0; py < 8; py++) {
						for (int px = 0; px < 8; px++) {
							if ((sheet.getRGB(sx + px * unit, sy + py * unit) >>> 24) > 127) {
								g.fillRect(x + px * scale, top + py * scale, scale, scale);
							}
						}
					}
				} else if (c != ' ') {
					g.setFont(FALLBACK.deriveFont(9f * scale));
					g.drawString(String.valueOf(c), x, top + 7 * scale);
				}
				x += advance(c, scale);
			}
		}

		private int advance(char c, int scale) {
			if (c == ' ') {
				return 4 * scale;
			}
			if (isBitmap(c)) {
				return (widths[c] + 1) * scale;
			}
			BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = scratch.createGraphics();
			try {
				return g.getFontMetrics(FALLBACK.deriveFont(9f * scale)).charWidth(c) + scale;
			} finally {
				g.dispose();
			}
		}

		private boolean isBitmap(char c) {
			return sheet != null && c > ' ' && c < 0x7F && widths[c] > 0;
		}

		private static BufferedImage loadAscii() {
			try (InputStream in = ItemCardRenderer.class.getClassLoader().getResourceAsStream("assets/minecraft/textures/font/ascii.png")) {
				return in == null ? null : ImageIO.read(in);
			} catch (IOException | RuntimeException e) {
				return null;
			}
		}

		/** Each glyph's width in font pixels: its rightmost filled column plus one. */
		private static int[] glyphWidths(BufferedImage sheet, int cell) {
			int[] widths = new int[128];
			if (sheet == null) {
				return widths;
			}
			int unit = cell / 8;
			for (int c = 0; c < 128; c++) {
				int sx = (c % 16) * cell;
				int sy = (c / 16) * cell;
				for (int px = 7; px >= 0 && widths[c] == 0; px--) {
					for (int py = 0; py < 8; py++) {
						if ((sheet.getRGB(sx + px * unit, sy + py * unit) >>> 24) > 127) {
							widths[c] = px + 1;
							break;
						}
					}
				}
			}
			return widths;
		}
	}
}
