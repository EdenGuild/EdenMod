package tel.eden.mod.item;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Renders a {@link DecodedItem} to a compact PNG card (the image2 layout): the item
 * name with overall roll %, a rarity pill, each identification with its value + roll
 * %, the powder slots, and a rarity footer. Pure offscreen {@link BufferedImage}
 * drawing — no game thread or display required.
 */
public final class ItemCardRenderer {
    private static final int WIDTH = 360;
    private static final int PAD = 18;
    private static final Color BG = new Color(0x16, 0x16, 0x1C);
    private static final Color VALUE_POS = new Color(0x55, 0xFF, 0x55);
    private static final Color VALUE_NEG = new Color(0xFF, 0x55, 0x55);
    private static final Color STAT_NAME = new Color(0xAA, 0xAA, 0xAA);
    private static final Color MUTED = new Color(0x88, 0x88, 0x88);

    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 22);
    private static final Font PILL_FONT = new Font("SansSerif", Font.BOLD, 13);
    private static final Font STAT_FONT = new Font("SansSerif", Font.PLAIN, 15);
    private static final Font FOOT_FONT = new Font("SansSerif", Font.BOLD, 14);

    private static final int NAME_H = 34;
    private static final int PILL_H = 30;
    private static final int ROW_H = 22;
    private static final int GAP = 12;

    private ItemCardRenderer() {}

    /** Render the item to PNG bytes. */
    public static byte[] render(DecodedItem item) throws IOException {
        int rows = item.identifications().size();
        int height = PAD
                + NAME_H
                + PILL_H
                + GAP
                + rows * ROW_H
                + GAP
                + ROW_H // powder slots
                + GAP
                + ROW_H // footer
                + PAD;

        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BG);
            g.fillRect(0, 0, WIDTH, height);

            int y = PAD + 22;
            y = drawName(g, item, y);
            y = drawPill(g, item, y + 4);
            y += GAP;
            y = drawIdentifications(g, item, y);
            y += GAP - 6;
            y = drawPowderSlots(g, item, y);
            y += GAP - 6;
            drawFooter(g, item, y);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static int drawName(Graphics2D g, DecodedItem item, int y) {
        String label = item.name();
        if (item.hasOverall()) {
            label += String.format("  [%.2f%%]", item.overallPercent());
        }
        g.setFont(NAME_FONT);
        g.setColor(new Color(item.tierColor()));
        drawCentered(g, label, y);
        return y + NAME_H - 18;
    }

    private static int drawPill(Graphics2D g, DecodedItem item, int y) {
        String text = item.tier().isEmpty() ? "Item" : item.tier() + " Item";
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

    private static int drawIdentifications(Graphics2D g, DecodedItem item, int y) {
        g.setFont(STAT_FONT);
        for (DecodedItem.Identification id : item.identifications()) {
            // Left: value (coloured) + stat name.
            int x = PAD;
            g.setColor(id.positive() ? VALUE_POS : VALUE_NEG);
            g.drawString(id.valueText(), x, y);
            x += g.getFontMetrics().stringWidth(id.valueText()) + 6;
            g.setColor(STAT_NAME);
            g.drawString(id.name(), x, y);

            // Right: roll % in a quality colour.
            if (id.hasRoll()) {
                String roll = String.format("[%.2f%%]", id.rollPercent());
                g.setColor(rollColor(id.rollPercent()));
                int rollW = g.getFontMetrics().stringWidth(roll);
                g.drawString(roll, WIDTH - PAD - rollW, y);
            }
            y += ROW_H;
        }
        return y;
    }

    private static int drawPowderSlots(Graphics2D g, DecodedItem item, int y) {
        g.setFont(STAT_FONT);
        g.setColor(MUTED);
        StringBuilder slots = new StringBuilder("Powder Slots [");
        slots.append("○".repeat(Math.max(0, item.powderSlots())));
        slots.append(']');
        g.drawString(slots.toString(), PAD, y);
        return y + ROW_H;
    }

    private static void drawFooter(Graphics2D g, DecodedItem item, int y) {
        g.setFont(FOOT_FONT);
        g.setColor(new Color(item.tierColor()));
        String text = item.tier().isEmpty() ? "Item" : item.tier() + " Item";
        g.drawString(text, PAD, y);
    }

    private static void drawCentered(Graphics2D g, String text, int y) {
        int w = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (WIDTH - w) / 2, y);
    }

    private static Color rollColor(float percent) {
        if (percent < 30f) {
            return new Color(0xFF, 0x55, 0x55);
        }
        if (percent < 70f) {
            return new Color(0xFF, 0xC0, 0x40);
        }
        if (percent < 90f) {
            return new Color(0x55, 0xFF, 0x55);
        }
        return new Color(0x55, 0xFF, 0xFF);
    }
}
