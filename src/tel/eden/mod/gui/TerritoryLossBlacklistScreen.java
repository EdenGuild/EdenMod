package tel.eden.mod.gui;

import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.config.BridgeConfig;

/**
 * Screen for configuring the blacklist of territories that suppress the anvil sound on capture.
 */
public final class TerritoryLossBlacklistScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 420;
	private static final int BASE_PANEL_HEIGHT = 300;
	private static final int PANEL_PADDING = 15;
	private static final int LIST_X = 15;
	private static final int LIST_Y = 36;
	private static final int LIST_WIDTH = 390;
	private static final int ROW_HEIGHT = 26;
	private static final int ROW_X = 17;
	private static final int ROW_WIDTH = 374;
	private static final int ROW_INNER_HEIGHT = 22;
	private static final int ROW_BUTTON_HEIGHT = 18;
	private static final int ROW_BUTTON_TOP_OFFSET = (ROW_INNER_HEIGHT - ROW_BUTTON_HEIGHT) / 2;
	private static final int VISIBLE_ROWS = 7;
	private static final int NAME_X = 23;
	private static final int REMOVE_X = 325;
	private static final int REMOVE_WIDTH = 64;
	private static final int SCROLLBAR_X = 393;

	private static final int INPUT_Y = 226;
	private static final int INPUT_WIDTH = 295;
	private static final int ADD_BUTTON_WIDTH = 85;

	private static final int FOOTER_Y = 256;
	private static final int FOOTER_BUTTON_WIDTH = 190;
	private static final int FOOTER_GAP = 10;

	private final Screen parent;
	private final BridgeConfig config;

	private EdenPanelLayout layout;
	private EditBox addBox;
	private int scrollOffset;
	private boolean draggingScrollbar;

	public TerritoryLossBlacklistScreen(Screen parent, BridgeConfig config) {
		this(parent, config, 0);
	}

	public TerritoryLossBlacklistScreen(Screen parent, BridgeConfig config, int scrollOffset) {
		super(Component.literal("Territory Loss Blacklist"));
		this.parent = parent;
		this.config = config;
		this.scrollOffset = scrollOffset;
	}

	@Override
	protected void init() {
		super.init();
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		addBox = new EditBox(this.font, layout.x(PANEL_PADDING), layout.y(INPUT_Y), layout.w(INPUT_WIDTH), layout.h(20), Component.literal("Territory name"));
		addBox.setMaxLength(100);
		addBox.setHint(Component.literal("Add territory name..."));
		this.addRenderableWidget(addBox);

		this.addRenderableWidget(Button.builder(Component.literal("Add"), button -> onAdd()).bounds(layout.x(PANEL_PADDING + INPUT_WIDTH + 10), layout.y(INPUT_Y), layout.w(ADD_BUTTON_WIDTH), layout.h(20)).build());

		this.addRenderableWidget(Button.builder(Component.literal("Reset to Defaults"), button -> onReset()).bounds(layout.x(PANEL_PADDING), layout.y(FOOTER_Y), layout.w(FOOTER_BUTTON_WIDTH), layout.h(20)).build());

		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose()).bounds(layout.x(PANEL_PADDING + FOOTER_BUTTON_WIDTH + FOOTER_GAP), layout.y(FOOTER_Y), layout.w(FOOTER_BUTTON_WIDTH), layout.h(20)).build());

		if (config.territoryLossBlacklist == null) {
			config.territoryLossBlacklist = new ArrayList<>(BridgeConfig.DEFAULT_TERRITORY_LOSS_BLACKLIST);
		}

		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScrollOffset()));
	}

	private void onAdd() {
		String text = addBox.getValue().trim();
		if (text.isEmpty()) {
			return;
		}
		boolean exists = config.territoryLossBlacklist.stream().anyMatch(t -> t.equalsIgnoreCase(text));
		if (!exists) {
			config.territoryLossBlacklist.add(0, text);
			config.save();
			addBox.setValue("");
			scrollOffset = 0;
		}
	}

	private void onDelete(String territory) {
		config.territoryLossBlacklist.remove(territory);
		config.save();
		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScrollOffset()));
	}

	private void onReset() {
		config.territoryLossBlacklist = new ArrayList<>(BridgeConfig.DEFAULT_TERRITORY_LOSS_BLACKLIST);
		config.save();
		scrollOffset = 0;
	}

	private int maxScrollOffset() {
		return Math.max(0, config.territoryLossBlacklist.size() - VISIBLE_ROWS);
	}

	private void drawRemoveButton(GuiGraphics g, int x, int y, int width, int height, boolean hovered) {
		int fill = hovered ? 0xFF777777 : 0xFF5A5A5A;
		g.fill(x, y, x + width, y + height, fill);
		g.fill(x, y, x + width, y + 1, 0xFFD8D8D8);
		g.fill(x, y, x + 1, y + height, 0xFFD8D8D8);
		g.fill(x + width - 1, y, x + width, y + height, 0xFF222222);
		g.fill(x, y + height - 1, x + width, y + height, 0xFF222222);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(graphics);
		layout.drawBackground(graphics);
		layout.drawPanel(graphics);

		int listLeft = layout.x(LIST_X);
		int listTop = layout.y(LIST_Y);
		int listWidth = layout.w(LIST_WIDTH);
		int listHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS + 4);
		graphics.fill(listLeft, listTop, listLeft + listWidth, listTop + listHeight, 0x22000000);

		for (int visible = 0; visible < VISIBLE_ROWS; visible++) {
			int index = scrollOffset + visible;
			if (index >= config.territoryLossBlacklist.size()) {
				break;
			}

			String territory = config.territoryLossBlacklist.get(index);
			int rowTop = layout.y(LIST_Y + 2 + visible * ROW_HEIGHT);
			graphics.fill(layout.x(ROW_X), rowTop, layout.x(ROW_X + ROW_WIDTH), rowTop + layout.h(ROW_INNER_HEIGHT), 0x44282828);

			int removeLeft = layout.x(REMOVE_X);
			int removeTop = rowTop + layout.h(ROW_BUTTON_TOP_OFFSET);
			int removeWidth = layout.w(REMOVE_WIDTH);
			int removeHeight = layout.h(ROW_BUTTON_HEIGHT);
			boolean hovered = isRemoveHovered(scaledMouseX, scaledMouseY, index);
			drawRemoveButton(graphics, removeLeft, removeTop, removeWidth, removeHeight, hovered);
			graphics.drawCenteredString(this.font, "Remove", removeLeft + (removeWidth / 2), removeTop + ((removeHeight - 9) / 2) + 1, 0xFFFFFFFF);

			int textWidth = removeLeft - layout.x(NAME_X) - layout.w(8);
			graphics.drawString(this.font, trimToWidth(territory, textWidth), layout.x(NAME_X), rowTop + layout.h(7), 0xFFFFFFFF);
		}

		layout.drawScrollbar(graphics, layout.x(SCROLLBAR_X), listTop, layout.w(8), listHeight, VISIBLE_ROWS, config.territoryLossBlacklist.size(), scrollOffset);
		super.render(graphics, scaledMouseX, scaledMouseY, delta);
		graphics.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(12), 0xFFFFFFFF);
		if (config.territoryLossBlacklist.isEmpty()) {
			graphics.drawCenteredString(this.font, "Blacklist is empty (all territories will play sound)", layout.centerX(), layout.y(110), 0xFFAAAAAA);
		}
		popReferencePose(graphics);
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
		if (scaled.button() == 0) {
			if (isOverScrollbar(scaled.x(), scaled.y())) {
				draggingScrollbar = true;
				updateScrollFromMouse(scaled.y());
				return true;
			}

			// Hit-test the drawn Remove boxes directly; they're drawn by hand, so play the click ourselves.
			int end = Math.min(config.territoryLossBlacklist.size(), scrollOffset + VISIBLE_ROWS);
			for (int index = scrollOffset; index < end; index++) {
				if (isRemoveHovered(scaled.x(), scaled.y(), index)) {
					this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
					onDelete(config.territoryLossBlacklist.get(index));
					return true;
				}
			}
		}
		if (scaled.button() == 0 && addBox != null) {
			if (addBox.isMouseOver(scaled.x(), scaled.y())) {
				this.setFocused(addBox);
				addBox.setFocused(true);
			} else {
				addBox.setFocused(false);
			}
		}
		return super.mouseClicked(scaled, doubleClick);
	}

	private boolean isRemoveHovered(double mouseX, double mouseY, int index) {
		int visibleRow = index - scrollOffset;
		if (visibleRow < 0 || visibleRow >= VISIBLE_ROWS) {
			return false;
		}
		int rowTop = layout.y(LIST_Y + 2 + visibleRow * ROW_HEIGHT);
		int removeLeft = layout.x(REMOVE_X);
		int removeTop = rowTop + layout.h(ROW_BUTTON_TOP_OFFSET);
		return mouseX >= removeLeft && mouseX <= removeLeft + layout.w(REMOVE_WIDTH) && mouseY >= removeTop && mouseY <= removeTop + layout.h(ROW_BUTTON_HEIGHT);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (addBox != null && addBox.isFocused()) {
			if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
				onAdd();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				addBox.setFocused(false);
				this.setFocused(null);
				return true;
			}
			if (addBox.keyPressed(event)) {
				return true;
			}
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (addBox != null && addBox.isFocused()) {
			if (addBox.charTyped(event)) {
				return true;
			}
		}
		return super.charTyped(event);
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
		return true;
	}

	private boolean isOverList(double mouseX, double mouseY) {
		int left = layout.x(LIST_X);
		int top = layout.y(LIST_Y);
		return mouseX >= left && mouseX <= left + layout.w(LIST_WIDTH) && mouseY >= top && mouseY <= top + layout.h(ROW_HEIGHT * VISIBLE_ROWS + 4);
	}

	private boolean isOverScrollbar(double mouseX, double mouseY) {
		int barX = layout.x(SCROLLBAR_X);
		int top = layout.y(LIST_Y);
		return mouseX >= barX && mouseX <= barX + layout.w(8) && mouseY >= top && mouseY <= top + layout.h(ROW_HEIGHT * VISIBLE_ROWS + 4);
	}

	private void updateScrollFromMouse(double mouseY) {
		int maxOffset = maxScrollOffset();
		if (maxOffset == 0) {
			scrollOffset = 0;
			return;
		}

		int trackTop = layout.y(LIST_Y);
		int trackHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS + 4);
		int thumbHeight = Math.max(layout.h(18), Math.round(trackHeight * (VISIBLE_ROWS / (float) config.territoryLossBlacklist.size())));
		double relative = mouseY - trackTop - thumbHeight / 2.0;
		double range = Math.max(1, trackHeight - thumbHeight);
		double percent = Math.max(0.0, Math.min(1.0, relative / range));
		scrollOffset = (int) Math.round(percent * maxOffset);
	}

	@Override
	public void onClose() {
		config.save();
		this.minecraft.setScreen(parent);
	}
}
