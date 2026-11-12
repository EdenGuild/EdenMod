package tel.eden.mod.gui;

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

/** Scrollable command-alias editor with inline autosave. */
public final class CommandAliasScreen extends EdenReferenceScreen {
	private static final int BASE_PANEL_WIDTH = 360;
	private static final int BASE_PANEL_HEIGHT = 310;
	private static final int ROW_HEIGHT = 28;
	private static final int VISIBLE_ROWS = 7;
	private static final long DOUBLE_CLICK_MS = 300L;
	private static final String COMMAND_EXAMPLE = "/gamemode creative";
	private static final String ALIAS_EXAMPLE = "/gc";
	private static final int LIST_X = 15;
	private static final int LIST_Y = 42;
	private static final int LIST_WIDTH = 330;
	private static final int ROW_X = 17;
	private static final int ROW_WIDTH = 322;
	private static final int ROW_INNER_HEIGHT = 24;
	private static final int COMMAND_WIDTH = 220;
	private static final int DIVIDER_X = 237;
	private static final int ALIAS_X = 239;
	private static final int ALIAS_WIDTH = 98;
	private static final int SCROLLBAR_X = 337;
	private static final Component EDIT_HINT = Component.literal("Double click a cell to edit");
	private static final long ADD_HINT_MS = 5_000L;

	private final EdenModClient mod;
	private final List<AliasRow> rows = new ArrayList<>();

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

	public CommandAliasScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("Command Aliases"), parent);
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
		for (BridgeConfig.CommandAlias alias : mod.commandAliases()) {
			rows.add(new AliasRow(alias.command, alias.alias));
		}
	}

	private void onAdd() {
		commitInlineEdit();
		rows.add(0, new AliasRow("", ""));
		selectedIndex = 0;
		editingCell = Cell.NONE;
		scrollOffset = 0;
		showHintUntil = System.currentTimeMillis() + ADD_HINT_MS;
		refreshButtons();
	}

	private void onDelete() {
		if (selectedIndex < 0 || selectedIndex >= rows.size()) {
			return;
		}
		commitInlineEdit();
		rows.remove(selectedIndex);
		persistRows();
		selectedIndex = -1;
		scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, rows.size() - VISIBLE_ROWS)));
		refreshButtons();
	}

	private boolean persistRows() {
		List<BridgeConfig.CommandAlias> aliases = new ArrayList<>();
		for (AliasRow row : rows) {
			aliases.add(new BridgeConfig.CommandAlias(row.alias, row.command));
		}
		return mod.replaceCommandAliases(aliases);
	}

	private void refreshButtons() {
		deleteButton.active = selectedIndex >= 0 && selectedIndex < rows.size();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		MouseButtonEvent scaled = rescale(event);
		double mouseX = scaled.x();
		double mouseY = scaled.y();

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
					editingCell = Cell.NONE;
					hideInlineEditor();
				} else {
					commitInlineEdit();
					selectedIndex = clickedRow;
					editingCell = Cell.NONE;
					hideInlineEditor();
					if (doubleClick) {
						beginInlineEdit(clickedRow, clickedCell);
					}
				}
				refreshButtons();
				return true;
			}

			if (!inlineEditor.isMouseOver(mouseX, mouseY)) {
				commitInlineEdit();
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
		if (editingCell != Cell.NONE && event.key() == GLFW.GLFW_KEY_ENTER) {
			commitInlineEdit();
			hideInlineEditor();
			return true;
		}
		if (editingCell != Cell.NONE && event.key() == GLFW.GLFW_KEY_ESCAPE) {
			cancelInlineEdit();
			hideInlineEditor();
			return true;
		}
		return super.keyPressed(event);
	}

	private void beginInlineEdit(int index, Cell cell) {
		if (index < 0 || index >= rows.size()) {
			return;
		}
		editingCell = cell;
		AliasRow row = rows.get(index);
		if (cell == Cell.COMMAND) {
			inlineEditor.setMaxLength(256);
			inlineEditor.setValue(row.command);
		} else {
			inlineEditor.setMaxLength(100);
			inlineEditor.setValue(row.alias);
		}
		inlineEditor.moveCursorToStart(false);
		repositionInlineEditor();
		inlineEditor.setVisible(true);
		inlineEditor.setFocused(true);
		setFocused(inlineEditor);
	}

	private void repositionInlineEditor() {
		if (selectedIndex < scrollOffset || selectedIndex >= scrollOffset + VISIBLE_ROWS || editingCell == Cell.NONE) {
			hideInlineEditor();
			return;
		}

		int rowTop = layout.y(44 + (selectedIndex - scrollOffset) * ROW_HEIGHT);
		if (editingCell == Cell.COMMAND) {
			inlineEditor.setX(layout.x(ROW_X));
			inlineEditor.setY(rowTop);
			inlineEditor.setWidth(layout.w(COMMAND_WIDTH));
			inlineEditor.setHeight(layout.h(ROW_INNER_HEIGHT));
		} else {
			inlineEditor.setX(layout.x(ALIAS_X));
			inlineEditor.setY(rowTop);
			inlineEditor.setWidth(layout.w(ALIAS_WIDTH));
			inlineEditor.setHeight(layout.h(ROW_INNER_HEIGHT));
		}
	}

	private void hideInlineEditor() {
		inlineEditor.setVisible(false);
		inlineEditor.setFocused(false);
		if (editingCell != Cell.NONE) {
			editingCell = Cell.NONE;
		}
	}

	private void commitInlineEdit() {
		if (editingCell == Cell.NONE || selectedIndex < 0 || selectedIndex >= rows.size()) {
			return;
		}
		AliasRow row = rows.get(selectedIndex);
		String newValue = inlineEditor.getValue().trim();
		String oldCommand = row.command;
		String oldAlias = row.alias;

		if (editingCell == Cell.COMMAND) {
			row.command = newValue.isEmpty() ? "" : normalizeCommand(newValue);
		} else {
			row.alias = newValue;
		}

		if (!newValue.isEmpty() && !row.command.isEmpty() && !row.alias.isEmpty() && !persistRows()) {
			row.command = oldCommand;
			row.alias = oldAlias;
		}
	}

	private void cancelInlineEdit() {
		if (editingCell == Cell.NONE || selectedIndex < 0 || selectedIndex >= rows.size()) {
			return;
		}
		AliasRow row = rows.get(selectedIndex);
		inlineEditor.setValue(editingCell == Cell.COMMAND ? row.command : row.alias);
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
		return mouseX < layout.x(DIVIDER_X) ? Cell.COMMAND : Cell.ALIAS;
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
		layout.drawPanel(g);

		int listLeft = layout.x(LIST_X);
		int listTop = layout.y(LIST_Y);
		int listWidth = layout.w(LIST_WIDTH);
		int listHeight = layout.h(ROW_HEIGHT * VISIBLE_ROWS);
		int rowWidth = layout.w(ROW_WIDTH);
		int commandWidth = layout.w(COMMAND_WIDTH);
		int aliasWidth = layout.w(ALIAS_WIDTH);
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

			AliasRow row = rows.get(index);
			if (!(selected && editingCell == Cell.COMMAND)) {
				g.drawCenteredString(this.font, Component.literal(trimToWidth(displayCommand(row.command), commandWidth - layout.w(12))), layout.x(ROW_X) + (commandWidth / 2), rowTop + layout.h(8), row.command.isEmpty() ? 0xFF888888 : 0xFFFFFFFF);
			}
			if (!(selected && editingCell == Cell.ALIAS)) {
				g.drawCenteredString(this.font, Component.literal(trimToWidth(displayAlias(row.alias), aliasWidth - layout.w(12))), layout.x(ALIAS_X) + (aliasWidth / 2), rowTop + layout.h(8), row.alias.isEmpty() ? 0xFF888888 : 0xFFFFFFFF);
			}
		}

		layout.drawScrollbar(g, layout.x(SCROLLBAR_X), listTop, layout.w(8), listHeight, VISIBLE_ROWS, rows.size(), scrollOffset);
		repositionInlineEditor();
		super.render(g, scaledMouseX, scaledMouseY, delta);

		g.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(12), 0xFFFFFFFF);
		g.drawString(this.font, "Command", layout.x(15), layout.y(30), 0xFFA0A0A0);
		g.drawString(this.font, "Alias", layout.x(245), layout.y(30), 0xFFA0A0A0);
		if ((rowAt(scaledMouseX, scaledMouseY) >= 0 || System.currentTimeMillis() <= showHintUntil) && editingCell == Cell.NONE) {
			g.drawCenteredString(this.font, EDIT_HINT, layout.centerX(), layout.y(236), 0xFFA0A0A0);
		}
		popReferencePose(g);
	}

	private String displayCommand(String value) {
		return value == null || value.isBlank() ? COMMAND_EXAMPLE : normalizeForDisplay(value);
	}

	private String displayAlias(String value) {
		return value == null || value.isBlank() ? ALIAS_EXAMPLE : normalizeForDisplay(value);
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

	@Override
	protected void beforeClose() {
		commitInlineEdit();
	}

	private static final class AliasRow {
		private String command;
		private String alias;

		private AliasRow(String command, String alias) {
			this.command = command;
			this.alias = alias;
		}
	}

	private enum Cell {
		NONE, COMMAND, ALIAS
	}
}
