package tel.eden.mod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Shared centered dialog layout rendered inside a fixed reference GUI space. */
final class EdenPanelLayout {
	private static final int SCREEN_MARGIN = 12;
	private static final float REFERENCE_GUI_SCALE = 3.0f;

	private final int screenWidth;
	private final int screenHeight;
	private final float scale;
	private final int panelX;
	private final int panelY;
	private final int panelWidth;
	private final int panelHeight;

	private EdenPanelLayout(int screenWidth, int screenHeight, float scale, int panelX, int panelY, int panelWidth, int panelHeight) {
		this.screenWidth = screenWidth;
		this.screenHeight = screenHeight;
		this.scale = scale;
		this.panelX = panelX;
		this.panelY = panelY;
		this.panelWidth = panelWidth;
		this.panelHeight = panelHeight;
	}

	static EdenPanelLayout centered(int screenWidth, int screenHeight, int baseWidth, int baseHeight) {
		float widthScale = (screenWidth - (SCREEN_MARGIN * 2f)) / baseWidth;
		float heightScale = (screenHeight - (SCREEN_MARGIN * 2f)) / baseHeight;
		float scale = Math.min(1.0f, Math.min(widthScale, heightScale));
		scale = Math.max(0.55f, scale);
		int panelWidth = Math.max(1, Math.round(baseWidth * scale));
		int panelHeight = Math.max(1, Math.round(baseHeight * scale));
		int panelX = (screenWidth - panelWidth) / 2;
		int panelY = (screenHeight - panelHeight) / 2;
		return new EdenPanelLayout(screenWidth, screenHeight, scale, panelX, panelY, panelWidth, panelHeight);
	}

	static float referenceScaleFactor() {
		double currentScale = Minecraft.getInstance().getWindow().getGuiScale();
		if (currentScale <= 0.0d) {
			return 1.0f;
		}
		return REFERENCE_GUI_SCALE / (float) currentScale;
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

	void drawBackground(GuiGraphics g) {
		g.fill(0, 0, screenWidth, screenHeight, 0xC0000000);
	}

	void drawPanel(GuiGraphics g) {
		g.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xE0282828);
		g.fill(panelX, panelY, panelX + panelWidth, panelY + 1, 0xFF8E8E8E);
		g.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, 0xFF5C5C5C);
		g.fill(panelX, panelY, panelX + 1, panelY + panelHeight, 0xFF8E8E8E);
		g.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, 0xFF5C5C5C);
		g.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + 2, 0xFFC6C6C6);
		g.fill(panelX + 1, panelY + panelHeight - 2, panelX + panelWidth - 1, panelY + panelHeight - 1, 0xFF3E3E3E);
		g.fill(panelX + 1, panelY + 1, panelX + 2, panelY + panelHeight - 1, 0xFFC6C6C6);
		g.fill(panelX + panelWidth - 2, panelY + 1, panelX + panelWidth - 1, panelY + panelHeight - 1, 0xFF3E3E3E);
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
