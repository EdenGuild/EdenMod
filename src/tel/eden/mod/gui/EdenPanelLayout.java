package tel.eden.mod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Shared centered dialog layout rendered inside a fixed reference GUI space. */
final class EdenPanelLayout {
	private static final int SCREEN_MARGIN = 12;
	/** When the screen is too small for the panel the panel runs nearly edge to edge, since every unit of margin costs text size. */
	private static final int TIGHT_MARGIN = 2;
	/**
	 * Last resort when even the smallest {@link #MIN_UI_SCALE} cannot fit a panel. Shrinking only the
	 * widgets leaves their text full-size, so labels get cut off and overlap; prefer shrinking the
	 * whole screen, which {@link #fittingScaleFactor} does first.
	 */
	private static final float MIN_PANEL_SCALE = 0.55f;
	/** How small a panel may be laid out, relative to its design size, before the screen is shrunk instead. */
	private static final float SNAP_MIN_PANEL_SCALE = 0.75f;
	/** Below this the whole screen is too small to read, so we stop shrinking and let it overflow. */
	private static final float MIN_UI_SCALE = 0.5f;

	private final float scale;
	private final int panelX;
	private final int panelY;
	private final int panelWidth;
	private final int panelHeight;

	private EdenPanelLayout(float scale, int panelX, int panelY, int panelWidth, int panelHeight) {
		this.scale = scale;
		this.panelX = panelX;
		this.panelY = panelY;
		this.panelWidth = panelWidth;
		this.panelHeight = panelHeight;
	}

	static EdenPanelLayout centered(int screenWidth, int screenHeight, int baseWidth, int baseHeight) {
		float widthScale = (screenWidth - (marginFor(screenWidth, baseWidth) * 2f)) / baseWidth;
		float heightScale = (screenHeight - (marginFor(screenHeight, baseHeight) * 2f)) / baseHeight;
		float scale = Math.min(1.0f, Math.min(widthScale, heightScale));
		scale = Math.max(MIN_PANEL_SCALE, scale);
		int panelWidth = Math.max(1, Math.round(baseWidth * scale));
		int panelHeight = Math.max(1, Math.round(baseHeight * scale));
		int panelX = (screenWidth - panelWidth) / 2;
		int panelY = (screenHeight - panelHeight) / 2;
		return new EdenPanelLayout(scale, panelX, panelY, panelWidth, panelHeight);
	}

	/**
	 * The scale to draw a reference-space screen at, relative to the player's GUI scale, so these
	 * screens are never bigger than the rest of the GUI at the setting the player chose.
	 *
	 * <p>When the panel does not fit, the whole screen is drawn smaller, text and widgets together.
	 * The result is snapped to a whole number of screen pixels per GUI unit: Minecraft's bitmap font
	 * is only sharp when each font pixel covers a whole number of screen pixels, and a fractional
	 * scale (0.52x, say) makes the text blurry and jagged. To keep the snap from throwing away too
	 * much size, the panel may be laid out down to {@link #SNAP_MIN_PANEL_SCALE} of its design size
	 * first; below that the labels stop fitting their buttons.
	 */
	static float fittingScaleFactor(int screenWidth, int screenHeight, int baseWidth, int baseHeight, int guiScale) {
		float fitWidth = screenWidth / (baseWidth * SNAP_MIN_PANEL_SCALE + TIGHT_MARGIN * 2f);
		float fitHeight = screenHeight / (baseHeight * SNAP_MIN_PANEL_SCALE + TIGHT_MARGIN * 2f);
		float pixelsPerUnit = guiScale * Math.min(1.0f, Math.min(fitWidth, fitHeight));
		int whole = (int) Math.floor(pixelsPerUnit + 1.0e-3f);
		if (whole >= 1) {
			return whole / (float) guiScale;
		}
		// The window is too small for even one screen pixel per unit: fit it, blurry but visible.
		return Math.max(MIN_UI_SCALE, pixelsPerUnit / guiScale);
	}

	/** The usual margin, or the tight one when the panel only fits at full size without the usual margin. */
	private static int marginFor(int screenSize, int baseSize) {
		return screenSize - SCREEN_MARGIN * 2 >= baseSize ? SCREEN_MARGIN : TIGHT_MARGIN;
	}

	int x(int baseOffset) {
		return panelX + Math.round(baseOffset * scale);
	}

	int y(int baseOffset) {
		return panelY + Math.round(baseOffset * scale);
	}

	int w(int baseSize) {
		return Math.max(1, Math.round(baseSize * scale));
	}

	int h(int baseSize) {
		return Math.max(1, Math.round(baseSize * scale));
	}

	int panelX() {
		return panelX;
	}

	int panelWidth() {
		return panelWidth;
	}

	int panelHeight() {
		return panelHeight;
	}

	int centerX() {
		return panelX + (panelWidth / 2);
	}

	void drawPanel(GuiGraphics g) {
		drawPanel(g, panelX, panelY, panelWidth, panelHeight);
	}

	/** The gray beveled panel every EdenMod screen sits on, at any rectangle. */
	static void drawPanel(GuiGraphics g, int x, int y, int width, int height) {
		g.fill(x, y, x + width, y + height, 0xE0282828);
		g.fill(x, y, x + width, y + 1, 0xFF8E8E8E);
		g.fill(x, y + height - 1, x + width, y + height, 0xFF5C5C5C);
		g.fill(x, y, x + 1, y + height, 0xFF8E8E8E);
		g.fill(x + width - 1, y, x + width, y + height, 0xFF5C5C5C);
		g.fill(x + 1, y + 1, x + width - 1, y + 2, 0xFFC6C6C6);
		g.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, 0xFF3E3E3E);
		g.fill(x + 1, y + 1, x + 2, y + height - 1, 0xFFC6C6C6);
		g.fill(x + width - 2, y + 1, x + width - 1, y + height - 1, 0xFF3E3E3E);
	}

	void drawScrollbar(GuiGraphics g, int x, int y, int width, int height, int visibleItems, int totalItems, int offset) {
		g.fill(x, y, x + width, y + height, 0x2A000000);
		g.fill(x, y, x + width, y + 1, 0xFF4A4A4A);
		g.fill(x, y, x + 1, y + height, 0xFF4A4A4A);
		g.fill(x + width - 1, y, x + width, y + height, 0xFF1E1E1E);
		g.fill(x, y + height - 1, x + width, y + height, 0xFF1E1E1E);
		if (totalItems <= visibleItems || totalItems <= 0) {
			return;
		}

		int thumbHeight = Math.max(h(18), Math.round(height * (visibleItems / (float) totalItems)));
		int travel = Math.max(1, height - thumbHeight);
		int maxOffset = totalItems - visibleItems;
		int thumbY = y + Math.round((offset / (float) maxOffset) * travel);

		g.fill(x + 1, thumbY, x + width - 1, thumbY + thumbHeight, 0xFF8A8A8A);
		g.fill(x + 1, thumbY, x + width - 1, thumbY + 1, 0xFFD8D8D8);
		g.fill(x + 1, thumbY + thumbHeight - 1, x + width - 1, thumbY + thumbHeight, 0xFF444444);
	}
}
