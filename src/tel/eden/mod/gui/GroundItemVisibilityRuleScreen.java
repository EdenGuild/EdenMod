package tel.eden.mod.gui;

import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import tel.eden.mod.config.BridgeConfig;

/** Detail editor for one dropped-item visibility rule. */
public final class GroundItemVisibilityRuleScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 360;
	private static final int BASE_PANEL_HEIGHT = 170;
	private static final int PANEL_PADDING = 15;
	private static final int FORM_TOP = 36;
	private static final int FORM_BOTTOM = 104;
	private static final int LABEL_X = 25;
	private static final int CONTROL_X = 140;
	private static final int CONTROL_WIDTH = 195;
	private static final int FIRST_ROW_Y = 40;
	private static final int ROW_PITCH = 30;
	private static final int CONTROL_HEIGHT = 20;
	private static final int DONE_Y = BASE_PANEL_HEIGHT - PANEL_PADDING - CONTROL_HEIGHT;

	private final BridgeConfig config;
	private final BridgeConfig.GroundItemVisibilityRule rule;

	private EdenPanelLayout layout;

	public GroundItemVisibilityRuleScreen(Screen parent, BridgeConfig config, BridgeConfig.GroundItemVisibilityRule rule) {
		super(Component.literal("Dropped Item Scale Rule"), parent);
		this.config = config;
		this.rule = rule;
	}

	@Override
	protected void init() {
		super.init();
		layout = centeredLayout(BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);
		rule.sanitize();

		EditBox nameBox = new EditBox(this.font, layout.x(CONTROL_X), layout.y(rowY(0)), layout.w(CONTROL_WIDTH), layout.h(CONTROL_HEIGHT), Component.literal("Name contains"));
		nameBox.setMaxLength(128);
		nameBox.setValue(rule.nameContains);
		nameBox.setResponder(value -> {
			rule.nameContains = BridgeConfig.sanitizeGroundItemName(value);
			config.save();
		});
		this.addRenderableWidget(nameBox);

		this.addRenderableWidget(new SizeSlider(layout.x(CONTROL_X), layout.y(rowY(1)), layout.w(CONTROL_WIDTH), layout.h(CONTROL_HEIGHT)));

		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose()).bounds(layout.x(PANEL_PADDING), layout.y(DONE_Y), layout.w(BASE_PANEL_WIDTH - PANEL_PADDING * 2), layout.h(CONTROL_HEIGHT)).build());
	}

	private int rowY(int row) {
		return FIRST_ROW_Y + row * ROW_PITCH;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(graphics);
		layout.drawBackground(graphics);
		layout.drawPanel(graphics);
		graphics.fill(layout.x(PANEL_PADDING), layout.y(FORM_TOP), layout.x(BASE_PANEL_WIDTH - PANEL_PADDING), layout.y(FORM_BOTTOM), 0x22000000);
		super.render(graphics, scaledMouseX, scaledMouseY, delta);

		graphics.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(12), 0xFFFFFFFF);
		String[] labels = {"Name contains", "Size"};
		for (int row = 0; row < labels.length; row++) {
			graphics.drawString(this.font, labels[row], layout.x(LABEL_X), layout.y(rowY(row) + 6), 0xFFA0A0A0);
		}

		popReferencePose(graphics);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return super.mouseClicked(rescale(event), doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(rescale(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		return super.mouseDragged(rescale(event), dragX / uiScale, dragY / uiScale);
	}

	@Override
	protected void beforeClose() {
		rule.sanitize();
		config.save();
	}

	private final class SizeSlider extends AbstractSliderButton {
		private SizeSlider(int x, int y, int width, int height) {
			super(x, y, width, height, Component.empty(), 0.0d);
			this.value = (rule.size - BridgeConfig.GROUND_ITEM_MIN_SCALE) / (BridgeConfig.GROUND_ITEM_MAX_SCALE - BridgeConfig.GROUND_ITEM_MIN_SCALE);
			this.value = Math.max(0.0d, Math.min(1.0d, this.value));
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(String.format(Locale.ROOT, "%.1fx", rule.size)));
		}

		@Override
		protected void applyValue() {
			float range = BridgeConfig.GROUND_ITEM_MAX_SCALE - BridgeConfig.GROUND_ITEM_MIN_SCALE;
			float snapped = BridgeConfig.GROUND_ITEM_MIN_SCALE + (float) Math.round(this.value * range * 10.0d) / 10.0f;
			float newSize = Math.max(BridgeConfig.GROUND_ITEM_MIN_SCALE, Math.min(BridgeConfig.GROUND_ITEM_MAX_SCALE, snapped));
			if (newSize != rule.size) {
				rule.size = newSize;
				config.save();
			}
			updateMessage();
		}
	}
}
