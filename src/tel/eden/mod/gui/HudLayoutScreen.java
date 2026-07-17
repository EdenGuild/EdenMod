package tel.eden.mod.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudLayout;
import tel.eden.mod.hud.HudPanels;

/**
 * A drag-and-scale editor for the war-suite HUD panels. Each panel is shown as a
 * labelled box at its current position/scale; drag a box to move it, scroll over it to
 * resize it. Changes write straight to {@link BridgeConfig#hudPositions} /
 * {@link BridgeConfig#hudScales} and persist on release. Opened from the config screen.
 */
public final class HudLayoutScreen extends Screen {
	private static final int BOX_BG = 0x804060A0;
	private static final int BOX_BG_HOVER = 0xA05080C0;
	private static final int BORDER = 0xFFFFFFFF;

	private final Screen parent;
	private final BridgeConfig config;

	private String dragging;
	private int dragOffsetX;
	private int dragOffsetY;

	public HudLayoutScreen(Screen parent, BridgeConfig config) {
		super(Component.literal("HUD Layout"));
		this.parent = parent;
		this.config = config;
	}

	@Override
	protected void init() {
		this.addRenderableWidget(Button.builder(Component.literal("Reset positions"), b -> {
			config.hudPositions.clear();
			config.hudScales.clear();
			config.save();
		}).bounds(this.width / 2 - 155, this.height - 28, 150, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(this.width / 2 + 5, this.height - 28, 150, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawCenteredString(this.font, Component.literal("Drag to move · scroll over a panel to resize"), this.width / 2, 12, 0xFFFFFFFF);
		for (HudPanels panel : HudPanels.ALL) {
			int[] rect = rectOf(panel);
			boolean hovered = panel.name().equals(dragging) || (mouseX >= rect[0] && mouseX <= rect[2] && mouseY >= rect[1] && mouseY <= rect[3]);
			graphics.fill(rect[0], rect[1], rect[2], rect[3], hovered ? BOX_BG_HOVER : BOX_BG);
			graphics.renderOutline(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1], BORDER);
			float scale = HudLayout.scale(config, panel.name());
			graphics.drawString(this.font, panel.label(), rect[0] + 3, rect[1] + 3, 0xFFFFFFFF);
			graphics.drawString(this.font, String.format("x%.2f", scale), rect[0] + 3, rect[1] + 14, 0xFFC0C0C0);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (HudPanels panel : HudPanels.ALL) {
			int[] rect = rectOf(panel);
			if (event.x() >= rect[0] && event.x() <= rect[2] && event.y() >= rect[1] && event.y() <= rect[3]) {
				dragging = panel.name();
				dragOffsetX = (int) event.x() - rect[0];
				dragOffsetY = (int) event.y() - rect[1];
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (dragging != null) {
			HudPanels panel = panelByName(dragging);
			if (panel != null) {
				float scale = HudLayout.scale(config, dragging);
				int w = Math.round(panel.previewWidth() * scale);
				int h = Math.round(panel.previewHeight() * scale);
				int newX = clamp((int) event.x() - dragOffsetX, this.width - w);
				int newY = clamp((int) event.y() - dragOffsetY, this.height - h);
				HudLayout.save(config, dragging, newX, newY);
			}
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			config.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		for (HudPanels panel : HudPanels.ALL) {
			int[] rect = rectOf(panel);
			if (mouseX >= rect[0] && mouseX <= rect[2] && mouseY >= rect[1] && mouseY <= rect[3]) {
				float next = HudLayout.scale(config, panel.name()) + (scrollY > 0 ? 0.1f : -0.1f);
				HudLayout.setScale(config, panel.name(), next);
				config.save();
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** The panel's on-screen box [x1, y1, x2, y2] at its current position and scale. */
	private int[] rectOf(HudPanels panel) {
		float scale = HudLayout.scale(config, panel.name());
		int w = Math.round(panel.previewWidth() * scale);
		int h = Math.round(panel.previewHeight() * scale);
		int x = clamp(HudLayout.x(config, panel.name(), panel.defaultX(), panel.defaultY()), this.width - w);
		int y = clamp(HudLayout.y(config, panel.name(), panel.defaultX(), panel.defaultY()), this.height - h);
		return new int[]{x, y, x + w, y + h};
	}

	private static int clamp(int value, int max) {
		return Math.max(0, Math.min(value, Math.max(0, max)));
	}

	private static HudPanels panelByName(String name) {
		for (HudPanels panel : HudPanels.ALL) {
			if (panel.name().equals(name)) {
				return panel;
			}
		}
		return null;
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
