package tel.eden.mod.gui;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;

/** Scrollable command-keybind editor with captured keyboard and mouse input. */
public final class CommandKeybindScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 360;
	private static final int BASE_PANEL_HEIGHT = 310;
	private static final int ROW_HEIGHT = 28;
	private static final int VISIBLE_ROWS = 7;
	private static final long DOUBLE_CLICK_MS = 300L;
	private static final String COMMAND_EXAMPLE = "/party list";
	private static final String KEYBIND_EXAMPLE = "Press key";
	private static final String CAPTURE_TEXT = "Press input";
	private static final int LIST_X = 15;
	private static final int LIST_Y = 42;
	private static final int LIST_WIDTH = 330;
	private static final int ROW_X = 17;
	private static final int ROW_WIDTH = 322;
	private static final int ROW_INNER_HEIGHT = 24;
	private static final int COMMAND_WIDTH = 220;
	private static final int DIVIDER_X = 237;
	private static final int KEYBIND_X = 239;
	private static final int KEYBIND_WIDTH = 98;
	private static final int SCROLLBAR_X = 337;
	private static final Component EDIT_HINT = Component.literal("Double click a cell to edit");
	private static final long ADD_HINT_MS = 5_000L;

	private final EdenModClient mod;
	private final List<KeybindRow> rows = new ArrayList<>();

	private EdenPanelLayout layout;
	private Button deleteButton;
	private EditBox inlineEditor;

	private int scrollOffset;
	private int selectedIndex = -1;
	private long lastClickAt;
	private long showHintUntil;
	private int lastClickIndex = -1;
	private Cell lastClickCell = Cell.COMMAND;
	private Cell editingCell = Cell.NONE;
	private boolean draggingScrollbar;
	private boolean capturingKeybind;

	public CommandKeybindScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Command Keybinds"), parent);
		this.mod = mod;
	}

	@Override
	protected void init() {
		layout = centeredLayout(BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		this.addRenderableWidget(Button.builder(Component.literal("Add"), button -> onAdd()).bounds(layout.x(15), layout.y(252), layout.w(160), layout.h(20)).build());
		deleteButton = this.addRenderableWidget(Button.builder(Component.literal("Delete"), button -> onDelete()).bounds(layout.x(185), layout.y(252), layout.w(160), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose()).bounds(layout.x(15), layout.y(278), layout.w(330), layout.h(20)).build());

		inlineEditor = new EditBox(this.font, 0, 0, 0, 0, Component.empty());
		inlineEditor.setMaxLength(256);
		inlineEditor.setVisible(false);
		inlineEditor.setCanLoseFocus(true);
		this.addRenderableWidget(inlineEditor);

		loadRows();
		refreshButtons();
	}

	private void loadRows() {
		rows.clear();
		for (BridgeConfig.CommandKeybind keybind : mod.commandKeybinds()) {
			rows.add(new KeybindRow(keybind.command, keybind.input));
		}
	}

	private void onAdd() {
		commitInlineEdit();
		stopCapturingKeybind();
		rows.add(0, new KeybindRow("", ""));
		selectedIndex = 0;
		scrollOffset = 0;
		showHintUntil = System.currentTimeMillis() + ADD_HINT_MS;
		refreshButtons();
	}

	private void onDelete() {
		if (selectedIndex < 0 || selectedIndex >= rows.size()) {
			return;
		}
		commitInlineEdit();
		stopCapturingKeybind();
		rows.remove(selectedIndex);
		persistRows();
		selectedIndex = -1;
		scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - VISIBLE_ROWS)));
		refreshButtons();
	}

	private boolean persistRows() {
		List<BridgeConfig.CommandKeybind> keybinds = new ArrayList<>();
		for (KeybindRow row : rows) {
			keybinds.add(new BridgeConfig.CommandKeybind(row.input, row.command));
		}
		return mod.replaceCommandKeybinds(keybinds);
	}

	private void refreshButtons() {
		deleteButton.active = selectedIndex >= 0 && selectedIndex < rows.size();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		MouseButtonEvent scaled = rescale(event);
		double mouseX = scaled.x();
		double mouseY = scaled.y();

		if (capturingKeybind) {
			captureMouseBinding(scaled.button());
			return true;
		}

		if (scaled.button() == 0) {
			if (isOverScrollbar(mouseX, mouseY)) {
				commitInlineEdit();
				draggingScrollbar = true;
				updateScrollFromMouse(mouseY);
				return true;
			}

			int clickedRow = rowAt(mouseX, mouseY);
			if (clickedRow >= 0) {
				Cell clickedCell = cellAt(mouseX);
				long now = System.currentTimeMillis();
				boolean doubleClick = clickedRow == lastClickIndex && clickedCell == lastClickCell && (now - lastClickAt) <= DOUBLE_CLICK_MS;
				lastClickAt = now;
				lastClickIndex = clickedRow;
				lastClickCell = clickedCell;

				if (selectedIndex == clickedRow && !doubleClick) {
					commitInlineEdit();
					selectedIndex = -1;
					stopCapturingKeybind();
					hideInlineEditor();
				} else {
					commitInlineEdit();
					selectedIndex = clickedRow;
					stopCapturingKeybind();
					hideInlineEditor();
					if (doubleClick) {
						if (clickedCell == Cell.KEYBIND) {
							beginKeybindCapture();
						} else {
							beginInlineEdit(clickedRow);
						}
					}
				}

				refreshButtons();
				return true;
			}

			if (!inlineEditor.isMouseOver(mouseX, mouseY)) {
				commitInlineEdit();
				stopCapturingKeybind();
				hideInlineEditor();
			}
		}

		return super.mouseClicked(scaled, bl);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingScrollbar = false;
		return super.mouseReleased(rescale(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double d, double e) {
		MouseButtonEvent scaled = rescale(event);
		if (draggingScrollbar) {
			updateScrollFromMouse(scaled.y());
			return true;
		}
		return super.mouseDragged(scaled, d / uiScale, e / uiScale);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double d, double e) {
		double scaledMouseX = mouseX / uiScale;
		double scaledMouseY = mouseY / uiScale;
		if (!isOverList(scaledMouseX, scaledMouseY) && !isOverScrollbar(scaledMouseX, scaledMouseY)) {
			return super.mouseScrolled(scaledMouseX, scaledMouseY, d, e);
		}
		commitInlineEdit();
		stopCapturingKeybind();
		int maxOffset = Math.max(0, rows.size() - VISIBLE_ROWS);
		if (maxOffset == 0) {
			return true;
		}
		scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(e)));
		repositionInlineEditor();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (capturingKeybind) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				stopCapturingKeybind();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_BACKSPACE || event.key() == GLFW.GLFW_KEY_DELETE) {
				clearCapturedKeybind();
				return true;
			}
			String captured = captureKeyboardBinding(event);
			if (captured != null) {
				applyCapturedKeybind(captured);
			}
			return true;
		}
		if (editingCell == Cell.COMMAND && event.key() == GLFW.GLFW_KEY_ENTER) {
			commitInlineEdit();
			hideInlineEditor();
			return true;
		}
		if (editingCell == Cell.COMMAND && event.key() == GLFW.GLFW_KEY_ESCAPE) {
			cancelInlineEdit();
			hideInlineEditor();
			return true;
		}
		return super.keyPressed(event);
	}

	private void beginInlineEdit(int index) {
		if (index < 0 || index >= rows.size()) {
			return;
		}
		stopCapturingKeybind();
		editingCell = Cell.COMMAND;
		KeybindRow row = rows.get(index);
		inlineEditor.setMaxLength(256);
		inlineEditor.setValue(row.command);
		inlineEditor.moveCursorToStart(false);
		repositionInlineEditor();
		inlineEditor.setVisible(true);
		inlineEditor.setFocused(true);
		setFocused(inlineEditor);
	}

	private void beginKeybindCapture() {
		if (selectedIndex < 0 || selectedIndex >= rows.size()) {
			return;
		}
		commitInlineEdit();
		hideInlineEditor();
		setFocused(null);
		editingCell = Cell.KEYBIND;
		capturingKeybind = true;
	}

	private void stopCapturingKeybind() {
		capturingKeybind = false;
		if (editingCell == Cell.KEYBIND) {
			editingCell = Cell.NONE;
		}
	}

	private void clearCapturedKeybind() {
		if (selectedIndex < 0 || selectedIndex >= rows.size()) {
			return;
		}
		KeybindRow row = rows.get(selectedIndex);
		String oldInput = row.input;
		row.input = "";
		if (!row.command.isEmpty() && !persistRows()) {
			row.input = oldInput;
		}
		stopCapturingKeybind();
	}

	private void captureMouseBinding(int button) {
		InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(button);
		applyCapturedKeybind(key.getName());
	}

	private String captureKeyboardBinding(KeyEvent event) {
		if (event.key() != GLFW.GLFW_KEY_UNKNOWN) {
			return InputConstants.Type.KEYSYM.getOrCreate(event.key()).getName();
		}
		if (event.scancode() > 0) {
			return InputConstants.Type.SCANCODE.getOrCreate(event.scancode()).getName();
		}
		return null;
	}

	private void applyCapturedKeybind(String input) {
		if (selectedIndex < 0 || selectedIndex >= rows.size()) {
			stopCapturingKeybind();
			return;
		}
		String normalizedInput = EdenModClient.normalizeKeybindInput(input);
		if (normalizedInput == null) {
			stopCapturingKeybind();
			return;
		}
		KeybindRow row = rows.get(selectedIndex);
		String oldInput = row.input;
		row.input = normalizedInput;
		if (!row.command.isEmpty() && !row.input.isEmpty() && !persistRows()) {
			row.input = oldInput;
		}
		stopCapturingKeybind();
	}

	private void repositionInlineEditor() {
		if (selectedIndex < scrollOffset || selectedIndex >= scrollOffset + VISIBLE_ROWS || editingCell != Cell.COMMAND) {
			hideInlineEditor();
			return;
		}

		int rowTop = layout.y(44 + (selectedIndex - scrollOffset) * ROW_HEIGHT);
		inlineEditor.setX(layout.x(ROW_X));
		inlineEditor.setY(rowTop);
		inlineEditor.setWidth(layout.w(COMMAND_WIDTH));
		inlineEditor.setHeight(layout.h(ROW_INNER_HEIGHT));
	}

	private void hideInlineEditor() {
		inlineEditor.setVisible(false);
		inlineEditor.setFocused(false);
		if (getFocused() == inlineEditor) {
			setFocused(null);
		}
		if (editingCell == Cell.COMMAND) {
			editingCell = Cell.NONE;
		}
	}

	private void commitInlineEdit() {
		if (editingCell != Cell.COMMAND || selectedIndex < 0 || selectedIndex >= rows.size()) {
			return;
		}
		KeybindRow row = rows.get(selectedIndex);
		String newValue = inlineEditor.getValue().trim();
		String oldCommand = row.command;
		row.command = newValue.isEmpty() ? "" : normalizeCommand(newValue);

		if (!newValue.isEmpty() && !row.command.isEmpty() && !row.input.isEmpty() && !persistRows()) {
			row.command = oldCommand;
		}
	}

	private void cancelInlineEdit() {
		if (editingCell != Cell.COMMAND || selectedIndex < 0 || selectedIndex >= rows.size()) {
			return;
		}
		inlineEditor.setValue(rows.get(selectedIndex).command);
	}

	private boolean isOverList(double mouseX, double mouseY) {
		return mouseX >= layout.x(LIST_X) && mouseX <= layout.x(LIST_X + LIST_WIDTH) && mouseY >= layout.y(LIST_Y) && mouseY <= layout.y(LIST_Y + ROW_HEIGHT * VISIBLE_ROWS);
	}

	private int rowAt(double mouseX, double mouseY) {
		if (!isOverList(mouseX, mouseY)) {
			return -1;
		}
		int row = ((int) mouseY - layout.y(LIST_Y)) / layout.h(ROW_HEIGHT);
		int index = scrollOffset + row;
		return index >= 0 && index < rows.size() ? index : -1;
	}

	private Cell cellAt(double mouseX) {
		return mouseX < layout.x(DIVIDER_X) ? Cell.COMMAND : Cell.KEYBIND;
	}

	private boolean isOverScrollbar(double mouseX, double mouseY) {
		return mouseX >= layout.x(SCROLLBAR_X) && mouseX <= layout.x(LIST_X + LIST_WIDTH) && mouseY >= layout.y(LIST_Y) && mouseY <= layout.y(LIST_Y + ROW_HEIGHT * VISIBLE_ROWS);
	}

	private void updateScrollFromMouse(double mouseY) {
		int maxOffset = Math.max(0, rows.size() - VISIBLE_ROWS);
		if (maxOffset == 0) {
			scrollOffset = 0;
			return;
		}
		int trackTop = layout.y(LIST_Y);
		int trackHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		int thumbHeight = Math.max(layout.h(18), Math.round(trackHeight * (VISIBLE_ROWS / (float) rows.size())));
		double relative = mouseY - trackTop - (thumbHeight / 2.0);
		double range = Math.max(1, trackHeight - thumbHeight);
		double percent = Math.max(0.0, Math.min(1.0, relative / range));
		scrollOffset = (int) Math.round(percent * maxOffset);
		repositionInlineEditor();
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(g);
		layout.drawBackground(g);
		layout.drawPanel(g);

		int listLeft = layout.x(LIST_X);
		int listTop = layout.y(LIST_Y);
		int listWidth = layout.w(LIST_WIDTH);
		int listHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		int rowWidth = layout.w(ROW_WIDTH);
		int commandWidth = layout.w(COMMAND_WIDTH);
		int keybindWidth = layout.w(KEYBIND_WIDTH);
		int dividerX = layout.x(DIVIDER_X);

		g.fill(listLeft, listTop, listLeft + listWidth, listTop + listHeight, 0x22000000);

		for (int visible = 0; visible < VISIBLE_ROWS; visible++) {
			int index = scrollOffset + visible;
			int rowTop = layout.y(44 + visible * ROW_HEIGHT);
			if (index >= rows.size()) {
				continue;
			}
			boolean selected = index == selectedIndex;
			int bg = selected ? 0x664e4e4e : 0x44282828;
			g.fill(layout.x(ROW_X), rowTop, layout.x(ROW_X) + rowWidth, rowTop + layout.h(ROW_INNER_HEIGHT), bg);
			g.fill(dividerX, rowTop, dividerX + 1, rowTop + layout.h(ROW_INNER_HEIGHT), 0x55000000);

			KeybindRow row = rows.get(index);
			if (!(selected && editingCell == Cell.COMMAND)) {
				g.drawCenteredString(this.font, Component.literal(trimToWidth(displayCommand(row.command), commandWidth - layout.w(12))), layout.x(ROW_X) + (commandWidth / 2), rowTop + layout.h(8), row.command.isEmpty() ? 0xFF888888 : 0xFFFFFFFF);
			}
			String keybindText = selected && capturingKeybind ? CAPTURE_TEXT : displayKeybind(row.input);
			int keybindColor = selected && capturingKeybind ? 0xFF55FFFF : (row.input.isEmpty() ? 0xFF888888 : 0xFFFFFFFF);
			g.drawString(this.font, clipToWidth(keybindText, keybindWidth - layout.w(10)), layout.x(KEYBIND_X) + layout.w(5), rowTop + layout.h(8), keybindColor);
		}

		layout.drawScrollbar(g, layout.x(SCROLLBAR_X), listTop, layout.w(8), listHeight, VISIBLE_ROWS, rows.size(), scrollOffset);
		repositionInlineEditor();
		super.render(g, scaledMouseX, scaledMouseY, delta);

		g.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(12), 0xFFFFFFFF);
		g.drawString(this.font, "Command", layout.x(15), layout.y(30), 0xFFA0A0A0);
		g.drawString(this.font, "Keybind", layout.x(245), layout.y(30), 0xFFA0A0A0);
		if ((rowAt(scaledMouseX, scaledMouseY) >= 0 || System.currentTimeMillis() <= showHintUntil) && editingCell == Cell.NONE && !capturingKeybind) {
			g.drawCenteredString(this.font, EDIT_HINT, layout.centerX(), layout.y(236), 0xFFA0A0A0);
		}
		popReferencePose(g);
	}

	private String displayCommand(String value) {
		return value == null || value.isBlank() ? COMMAND_EXAMPLE : normalizeForDisplay(value);
	}

	private String displayKeybind(String value) {
		return value == null || value.isBlank() ? KEYBIND_EXAMPLE : EdenModClient.describeKeybindInput(value);
	}

	private String normalizeForDisplay(String value) {
		return normalizeCommand(value);
	}

	private String normalizeCommand(String value) {
		String trimmed = value.trim();
		return trimmed.isEmpty() ? "/" : (trimmed.startsWith("/") ? trimmed : "/" + trimmed);
	}

	private String trimToWidth(String text, int width) {
		if (this.font.width(text) <= width) {
			return text;
		}
		return this.font.plainSubstrByWidth(text, Math.max(0, width - this.font.width("..."))) + "...";
	}

	private String clipToWidth(String text, int width) {
		return this.font.width(text) <= width ? text : this.font.plainSubstrByWidth(text, Math.max(0, width));
	}

	@Override
	protected void beforeClose() {
		commitInlineEdit();
		stopCapturingKeybind();
	}

	private static final class KeybindRow {
		private String command;
		private String input;

		private KeybindRow(String command, String input) {
			this.command = command;
			this.input = input;
		}
	}

	private enum Cell {
		NONE, COMMAND, KEYBIND
	}
}
