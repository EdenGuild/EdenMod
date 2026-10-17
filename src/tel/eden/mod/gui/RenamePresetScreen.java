package tel.eden.mod.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.war.eco.TerritoryGuideController;

/**
 * Modal dialog allowing the user to rename a saved territory preset.
 */
public class RenamePresetScreen extends Screen {
	private final Screen parent;
	private final String oldName;
	private EditBox nameField;

	public RenamePresetScreen(Screen parent, String oldName) {
		super(Component.literal("Rename Preset"));
		this.parent = parent;
		this.oldName = oldName;
	}

	@Override
	protected void init() {
		int panelW = 220;
		int panelH = 92;
		int x = (this.width - panelW) / 2;
		int y = (this.height - panelH) / 2;

		nameField = new EditBox(this.font, x + 15, y + 30, panelW - 30, 20, Component.literal("Preset Name"));
		nameField.setValue(oldName != null ? oldName : "");
		nameField.setMaxLength(32);
		this.addRenderableWidget(nameField);
		this.setInitialFocus(nameField);

		int btnW = (panelW - 36) / 2;
		this.addRenderableWidget(Button.builder(Component.literal("Save"), b -> commit()).bounds(x + 15, y + 58, btnW, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(x + 21 + btnW, y + 58, btnW, 20).build());
	}

	private void commit() {
		if (nameField != null) {
			String newName = nameField.getValue().trim();
			if (newName.isEmpty()) {
				newName = "Default";
			}
			if (!newName.equalsIgnoreCase(oldName)) {
				TerritoryGuideController.renameSavedPreset(oldName, newName);
			}
		}
		onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			commit();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		if (this.minecraft != null) {
			this.minecraft.setScreen(parent);
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		int panelW = 220;
		int panelH = 92;
		int x = (this.width - panelW) / 2;
		int y = (this.height - panelH) / 2;

		graphics.fill(x, y, x + panelW, y + panelH, 0xF012161E);
		graphics.renderOutline(x, y, panelW, panelH, 0xFF3E4A5B);
		graphics.drawString(this.font, "§6Rename Preset", x + 15, y + 14, 0xFFFFFFFF);
		super.render(graphics, mouseX, mouseY, delta);
	}
}
