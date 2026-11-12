package tel.eden.mod.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import tel.eden.mod.config.BridgeConfig;

/** Scrollable list of dropped-item visibility rules, each editable in a detail screen. */
public final class GroundItemVisibilityScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 420;
	private static final int BASE_PANEL_HEIGHT = 300;
	private static final int PANEL_PADDING = 15;
	private static final int LIST_X = 15;
	private static final int LIST_Y = 36;
	private static final int LIST_WIDTH = 390;
	private static final int ROW_HEIGHT = 30;
	private static final int ROW_X = 17;
	private static final int ROW_WIDTH = 374;
	private static final int ROW_INNER_HEIGHT = 26;
	private static final int ROW_BUTTON_HEIGHT = 20;
	private static final int ROW_BUTTON_TOP_OFFSET = (ROW_INNER_HEIGHT - ROW_BUTTON_HEIGHT) / 2;
	private static final int VISIBLE_ROWS = 7;
	private static final int SUMMARY_X = 23;
	private static final int EDIT_X = 263;
	private static final int EDIT_WIDTH = 56;
	private static final int REMOVE_X = 325;
	private static final int REMOVE_WIDTH = 64;
	private static final int SCROLLBAR_X = 393;
	private static final int FOOTER_Y = 266;
	private static final int FOOTER_GAP = 10;
	private static final int FOOTER_BUTTON_WIDTH = (BASE_PANEL_WIDTH - PANEL_PADDING * 2 - FOOTER_GAP) / 2;

	private final BridgeConfig config;
	private final List<RuleRow> rows = new ArrayList<>();

	private EdenPanelLayout layout;
	private int scrollOffset;
	private boolean draggingScrollbar;

	public GroundItemVisibilityScreen(Screen parent, BridgeConfig config) {
		super(Component.literal("Dropped Item Rules"), parent);
		this.config = config;
	}

	@Override
	protected void init() {
		super.init();
		layout = centeredLayout(BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);
		rows.clear();

		this.addRenderableWidget(Button.builder(Component.literal("Add"), button -> onAdd()).bounds(layout.x(PANEL_PADDING), layout.y(FOOTER_Y), layout.w(FOOTER_BUTTON_WIDTH), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose()).bounds(layout.x(PANEL_PADDING + FOOTER_BUTTON_WIDTH + FOOTER_GAP), layout.y(FOOTER_Y), layout.w(FOOTER_BUTTON_WIDTH), layout.h(20)).build());

		if (config.groundItemVisibilityRules == null) {
			config.groundItemVisibilityRules = new ArrayList<>();
		}
		for (BridgeConfig.GroundItemVisibilityRule rule : config.groundItemVisibilityRules) {
			if (rule == null) {
				continue;
			}
			rule.sanitize();
			rows.add(createRow(rule));
		}
		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScrollOffset()));
		layoutRows();
	}

	private RuleRow createRow(BridgeConfig.GroundItemVisibilityRule rule) {
		Button editButton = Button.builder(Component.literal("Edit"), button -> this.minecraft.setScreen(new GroundItemVisibilityRuleScreen(this, config, rule))).bounds(0, 0, layout.w(EDIT_WIDTH), layout.h(ROW_BUTTON_HEIGHT)).build();
		Button removeButton = Button.builder(Component.literal("Remove"), button -> onDelete(rule)).bounds(0, 0, layout.w(REMOVE_WIDTH), layout.h(ROW_BUTTON_HEIGHT)).build();
		this.addWidget(editButton);
		this.addWidget(removeButton);
		return new RuleRow(rule, editButton, removeButton);
	}

	private void onAdd() {
		BridgeConfig.GroundItemVisibilityRule rule = new BridgeConfig.GroundItemVisibilityRule("", 1.0f);
		config.groundItemVisibilityRules.add(0, rule);
		config.save();
		this.minecraft.setScreen(new GroundItemVisibilityRuleScreen(this, config, rule));
	}

	private void onDelete(BridgeConfig.GroundItemVisibilityRule rule) {
		config.groundItemVisibilityRules.remove(rule);
		config.save();
		this.minecraft.setScreen(new GroundItemVisibilityScreen(parent, config));
	}

	private int maxScrollOffset() {
		return Math.max(0, rows.size() - VISIBLE_ROWS);
	}

	private void layoutRows() {
		for (int index = 0; index < rows.size(); index++) {
			RuleRow row = rows.get(index);
			int visibleIndex = index - scrollOffset;
			boolean inView = visibleIndex >= 0 && visibleIndex < VISIBLE_ROWS;
			row.editButton.visible = inView;
			row.editButton.active = inView;
			row.removeButton.visible = inView;
			row.removeButton.active = inView;
			if (!inView) {
				continue;
			}

			int rowTop = layout.y(38 + visibleIndex * ROW_HEIGHT);
			position(row.editButton, EDIT_X, rowTop + layout.h(ROW_BUTTON_TOP_OFFSET), EDIT_WIDTH);
			position(row.removeButton, REMOVE_X, rowTop + layout.h(ROW_BUTTON_TOP_OFFSET), REMOVE_WIDTH);
		}
	}

	private void position(Button button, int baseX, int y, int baseWidth) {
		button.setX(layout.x(baseX));
		button.setY(y);
		button.setWidth(layout.w(baseWidth));
		button.setHeight(layout.h(ROW_BUTTON_HEIGHT));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(graphics);
		layout.drawPanel(graphics);

		int listLeft = layout.x(LIST_X);
		int listTop = layout.y(LIST_Y);
		int listWidth = layout.w(LIST_WIDTH);
		int listHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		graphics.fill(listLeft, listTop, listLeft + listWidth, listTop + listHeight, 0x22000000);

		layoutRows();
		graphics.enableScissor(listLeft, listTop, listLeft + listWidth, listTop + listHeight);
		for (int visible = 0; visible < VISIBLE_ROWS; visible++) {
			int index = scrollOffset + visible;
			if (index >= rows.size()) {
				break;
			}

			RuleRow row = rows.get(index);
			int rowTop = layout.y(38 + visible * ROW_HEIGHT);
			graphics.fill(layout.x(ROW_X), rowTop, layout.x(ROW_X + ROW_WIDTH), rowTop + layout.h(ROW_INNER_HEIGHT), 0x44282828);

			int textWidth = layout.x(EDIT_X) - layout.x(SUMMARY_X) - layout.w(8);
			graphics.drawString(this.font, trimToWidth(summaryLine(row.rule), textWidth), layout.x(SUMMARY_X), rowTop + layout.h(4), 0xFFFFFFFF);
			graphics.drawString(this.font, trimToWidth(actionLine(row.rule), textWidth), layout.x(SUMMARY_X), rowTop + layout.h(14), 0xFFA0A0A0);
			row.editButton.render(graphics, scaledMouseX, scaledMouseY, delta);
			row.removeButton.render(graphics, scaledMouseX, scaledMouseY, delta);
		}
		graphics.disableScissor();

		layout.drawScrollbar(graphics, layout.x(SCROLLBAR_X), listTop, layout.w(8), listHeight, VISIBLE_ROWS, rows.size(), scrollOffset);
		super.render(graphics, scaledMouseX, scaledMouseY, delta);
		graphics.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(12), 0xFFFFFFFF);
		if (rows.isEmpty()) {
			graphics.drawCenteredString(this.font, "No item rules yet", layout.centerX(), layout.y(136), 0xFFAAAAAA);
		}
		popReferencePose(graphics);
	}

	private String summaryLine(BridgeConfig.GroundItemVisibilityRule rule) {
		return rule.nameContains.isBlank() ? "Any name" : rule.nameContains;
	}

	private String actionLine(BridgeConfig.GroundItemVisibilityRule rule) {
		return "Scale " + String.format(Locale.ROOT, "%.1fx", rule.size);
	}

	private String trimToWidth(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("..."))) + "...";
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		MouseButtonEvent scaled = rescale(event);
		if (scaled.button() == 0 && isOverScrollbar(scaled.x(), scaled.y())) {
			draggingScrollbar = true;
			updateScrollFromMouse(scaled.y());
			return true;
		}
		return super.mouseClicked(scaled, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingScrollbar = false;
		return super.mouseReleased(rescale(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		MouseButtonEvent scaled = rescale(event);
		if (draggingScrollbar) {
			updateScrollFromMouse(scaled.y());
			return true;
		}
		return super.mouseDragged(scaled, dragX / uiScale, dragY / uiScale);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		double scaledMouseX = mouseX / uiScale;
		double scaledMouseY = mouseY / uiScale;
		if (!isOverList(scaledMouseX, scaledMouseY) && !isOverScrollbar(scaledMouseX, scaledMouseY)) {
			return super.mouseScrolled(scaledMouseX, scaledMouseY, scrollX, scrollY);
		}
		if (maxScrollOffset() == 0) {
			return true;
		}
		scrollOffset = Math.max(0, Math.min(maxScrollOffset(), scrollOffset - (int) Math.signum(scrollY)));
		layoutRows();
		return true;
	}

	private boolean isOverList(double mouseX, double mouseY) {
		return mouseX >= layout.x(LIST_X) && mouseX <= layout.x(LIST_X + LIST_WIDTH) && mouseY >= layout.y(LIST_Y) && mouseY <= layout.y(LIST_Y + ROW_HEIGHT * VISIBLE_ROWS);
	}

	private boolean isOverScrollbar(double mouseX, double mouseY) {
		return mouseX >= layout.x(SCROLLBAR_X) && mouseX <= layout.x(LIST_X + LIST_WIDTH) && mouseY >= layout.y(LIST_Y) && mouseY <= layout.y(LIST_Y + ROW_HEIGHT * VISIBLE_ROWS);
	}

	private void updateScrollFromMouse(double mouseY) {
		if (maxScrollOffset() == 0) {
			scrollOffset = 0;
			return;
		}

		int trackTop = layout.y(LIST_Y);
		int trackHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		int thumbHeight = Math.max(layout.h(18), Math.round(trackHeight * (VISIBLE_ROWS / (float) rows.size())));
		double relative = mouseY - trackTop - thumbHeight / 2.0;
		double range = Math.max(1, trackHeight - thumbHeight);
		double percent = Math.max(0.0, Math.min(1.0, relative / range));
		scrollOffset = (int) Math.round(percent * maxScrollOffset());
		layoutRows();
	}

	@Override
	protected void beforeClose() {
		config.save();
	}

	private static final class RuleRow {
		private final BridgeConfig.GroundItemVisibilityRule rule;
		private final Button editButton;
		private final Button removeButton;

		private RuleRow(BridgeConfig.GroundItemVisibilityRule rule, Button editButton, Button removeButton) {
			this.rule = rule;
			this.editButton = editButton;
			this.removeButton = removeButton;
		}
	}
}
