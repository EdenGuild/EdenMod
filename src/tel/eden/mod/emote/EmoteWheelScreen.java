package tel.eden.mod.emote;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * The radial emote-wheel overlay (avomod2 port). Draws {@value EmoteWheel#SLOTS} emote
 * squares evenly around the screen centre; the sector under the cursor is highlighted and
 * chosen on click, and the number keys 1-{@value EmoteWheel#SLOTS} pick a sector directly.
 * Releasing the wheel keybind closes it without playing anything.
 */
public final class EmoteWheelScreen extends Screen {
	private static final int SQUARE_SIZE = 60;
	private static final int RADIUS = 100;
	private static final int COLOR_IDLE = 0xFF000000;
	private static final int COLOR_ACTIVE = 0xFF808080;
	private static final int COLOR_TEXT = 0xFFFFFFFF;

	private final List<String> emotes = EmoteWheel.favorites();

	public EmoteWheelScreen() {
		super(Component.literal("Emote Wheel"));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		int centerX = this.width / 2;
		int centerY = this.height / 2;
		int active = activeSector(mouseX, mouseY, centerX, centerY);
		for (int i = 0; i < EmoteWheel.SLOTS; i++) {
			int[] pos = sectorCenter(i, centerX, centerY);
			int squareX = pos[0] - SQUARE_SIZE / 2;
			int squareY = pos[1] - SQUARE_SIZE / 2;
			graphics.fill(squareX, squareY, squareX + SQUARE_SIZE, squareY + SQUARE_SIZE, active == i ? COLOR_ACTIVE : COLOR_IDLE);
			String label = emotes.get(i);
			graphics.drawCenteredString(this.font, Component.literal(label.isEmpty() ? "-" : label), pos[0], pos[1] - 4, COLOR_TEXT);
			graphics.drawCenteredString(this.font, Component.literal(Integer.toString(i + 1)), squareX + 6, squareY + 4, COLOR_TEXT);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		executeEmote(activeSector((int) event.x(), (int) event.y(), this.width / 2, this.height / 2));
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() >= GLFW.GLFW_KEY_1 && event.key() <= GLFW.GLFW_KEY_1 + EmoteWheel.SLOTS - 1) {
			executeEmote(event.key() - GLFW.GLFW_KEY_1);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (EmoteWheel.key() != null && EmoteWheel.key().matches(event)) {
			this.onClose();
			return true;
		}
		return super.keyReleased(event);
	}

	/** Centre pixel [x, y] of sector {@code i}, evenly spaced around the wheel. */
	private static int[] sectorCenter(int i, int centerX, int centerY) {
		double angle = Math.toRadians(i * (360.0 / EmoteWheel.SLOTS));
		return new int[]{(int) (centerX + RADIUS * Math.cos(angle)), (int) (centerY + RADIUS * Math.sin(angle))};
	}

	/** Which sector (0-based) the cursor points at, from its angle around the centre. */
	private static int activeSector(int mouseX, int mouseY, int centerX, int centerY) {
		double angle = Math.atan2(mouseY - centerY, mouseX - centerX);
		if (angle < 0) {
			angle += 2 * Math.PI;
		}
		return (int) ((angle + Math.PI / EmoteWheel.SLOTS) / (Math.PI / (EmoteWheel.SLOTS / 2.0))) % EmoteWheel.SLOTS;
	}

	private void executeEmote(int sector) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null && sector >= 0 && sector < emotes.size() && !emotes.get(sector).isEmpty()) {
			this.onClose();
			mc.getConnection().sendCommand("emote " + emotes.get(sector));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
