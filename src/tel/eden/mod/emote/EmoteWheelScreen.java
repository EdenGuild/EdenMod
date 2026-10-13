package tel.eden.mod.emote;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;

/**
 * The radial emote-wheel overlay (avomod2 port). Draws {@value EmoteWheel#SLOTS} emote
 * squares evenly around the screen centre; the sector under the cursor is highlighted.
 * Selection is radial-menu style: <b>releasing the wheel keybind plays whichever sector the
 * cursor was last over</b> (a small dead zone at the centre cancels). Clicking a sector or
 * pressing 1-{@value EmoteWheel#SLOTS} also plays it directly.
 */
public final class EmoteWheelScreen extends Screen {
	private static final int SQUARE_SIZE = 60;
	private static final int RADIUS = 100;
	// Cursor within this many pixels of the centre selects nothing (release = cancel).
	private static final int DEAD_ZONE = 28;
	private static final int COLOR_IDLE = 0xFF000000;
	private static final int COLOR_ACTIVE = 0xFF808080;
	private static final int COLOR_TEXT = 0xFFFFFFFF;

	private List<String> emotes;
	private int hoveredSector = -1;
	private int currentPage;
	private int totalPages;

	public EmoteWheelScreen() {
		super(Component.literal("Emote Wheel"));
		EdenModClient client = EdenModClient.instance();
		BridgeConfig config = client != null ? client.config() : null;
		if (config != null) {
			EmoteWheel.ensurePages(config);
			this.currentPage = config.emoteWheelActivePage;
			this.totalPages = config.emoteWheelPages.size();
		} else {
			this.currentPage = 0;
			this.totalPages = 1;
		}
		this.emotes = EmoteWheel.favoritesForPage(this.currentPage);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		int centerX = this.width / 2;
		int centerY = this.height / 2;
		hoveredSector = activeSector(mouseX, mouseY, centerX, centerY);
		for (int i = 0; i < EmoteWheel.SLOTS; i++) {
			int[] pos = sectorCenter(i, centerX, centerY);
			int squareX = pos[0] - SQUARE_SIZE / 2;
			int squareY = pos[1] - SQUARE_SIZE / 2;
			graphics.fill(squareX, squareY, squareX + SQUARE_SIZE, squareY + SQUARE_SIZE, hoveredSector == i ? COLOR_ACTIVE : COLOR_IDLE);
			String label = (i < emotes.size()) ? emotes.get(i) : "";
			graphics.drawCenteredString(this.font, Component.literal(label.isEmpty() ? "-" : label), pos[0], pos[1] - 4, COLOR_TEXT);
			graphics.drawCenteredString(this.font, Component.literal(Integer.toString(i + 1)), squareX + 6, squareY + 4, COLOR_TEXT);
		}
		if (totalPages > 1) {
			graphics.drawCenteredString(this.font, Component.literal("Page " + (currentPage + 1) + "/" + totalPages), centerX, centerY - 4, COLOR_TEXT);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0 && totalPages > 1) {
			int next = currentPage + (scrollY > 0 ? -1 : 1);
			if (next < 0) {
				next = totalPages - 1;
			} else if (next >= totalPages) {
				next = 0;
			}
			switchToPage(next);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		playAndClose(activeSector((int) event.x(), (int) event.y(), this.width / 2, this.height / 2));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() >= GLFW.GLFW_KEY_1 && event.key() <= GLFW.GLFW_KEY_1 + EmoteWheel.SLOTS - 1) {
			playAndClose(event.key() - GLFW.GLFW_KEY_1);
			return true;
		}
		if (totalPages > 1) {
			if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_A) {
				switchToPage((currentPage - 1 + totalPages) % totalPages);
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_RIGHT || event.key() == GLFW.GLFW_KEY_D) {
				switchToPage((currentPage + 1) % totalPages);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	private boolean pageChanged = false;

	private void switchToPage(int page) {
		this.currentPage = page;
		this.pageChanged = true;
		EdenModClient client = EdenModClient.instance();
		BridgeConfig config = client != null ? client.config() : null;
		if (config != null) {
			config.emoteWheelActivePage = page;
			config.emoteWheelFavorites = new ArrayList<>(config.emoteWheelPages.get(page));
		}
		this.emotes = EmoteWheel.favoritesForPage(page);
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.getSoundManager() != null) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
		}
	}

	@Override
	public void onClose() {
		if (pageChanged) {
			EdenModClient client = EdenModClient.instance();
			BridgeConfig config = client != null ? client.config() : null;
			if (config != null) {
				config.save();
			}
		}
		super.onClose();
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (EmoteWheel.key() != null && EmoteWheel.key().matches(event)) {
			// Release-to-select: play whatever the cursor was last over (dead zone = cancel).
			playAndClose(hoveredSector);
			return true;
		}
		return super.keyReleased(event);
	}

	/** Centre pixel [x, y] of sector {@code i}, evenly spaced around the wheel. */
	private static int[] sectorCenter(int i, int centerX, int centerY) {
		double angle = Math.toRadians(i * (360.0 / EmoteWheel.SLOTS));
		return new int[]{(int) (centerX + RADIUS * Math.cos(angle)), (int) (centerY + RADIUS * Math.sin(angle))};
	}

	/** Which sector (0-based) the cursor points at, or -1 inside the centre dead zone. */
	private static int activeSector(int mouseX, int mouseY, int centerX, int centerY) {
		int dx = mouseX - centerX;
		int dy = mouseY - centerY;
		if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) {
			return -1;
		}
		double angle = Math.atan2(dy, dx);
		if (angle < 0) {
			angle += 2 * Math.PI;
		}
		return (int) ((angle + Math.PI / EmoteWheel.SLOTS) / (Math.PI / (EmoteWheel.SLOTS / 2.0))) % EmoteWheel.SLOTS;
	}

	/** Close the wheel, then play the sector's emote if it points at a non-empty slot. */
	private void playAndClose(int sector) {
		Minecraft mc = Minecraft.getInstance();
		String emote = (sector >= 0 && sector < emotes.size()) ? emotes.get(sector) : "";
		this.onClose();
		if (mc.getConnection() != null && !emote.isEmpty()) {
			mc.getConnection().sendCommand("emote " + emote);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
