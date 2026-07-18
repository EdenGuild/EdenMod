package tel.eden.mod.emote;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tel.eden.mod.config.BridgeConfig;

/**
 * Assigns the {@value EmoteWheel#SLOTS} emote-wheel favorites. Each slot is a cycle button
 * over the emotes you've unlocked (kept current by {@link UnlockedEmoteDetector}), plus an
 * empty option. Opened from the mod config screen; changes persist on Done.
 */
public final class EmoteConfigScreen extends Screen {
	private static final int BUTTON_W = 150;
	private static final int BUTTON_H = 20;
	private static final int GAP_X = 10;
	private static final int GAP_Y = 6;

	private final Screen parent;
	private final BridgeConfig config;
	private final String[] favorites = new String[EmoteWheel.SLOTS];

	public EmoteConfigScreen(Screen parent, BridgeConfig config) {
		super(Component.literal("Emote Wheel Favorites"));
		this.parent = parent;
		this.config = config;
	}

	@Override
	protected void init() {
		List<String> current = EmoteWheel.favorites();
		for (int i = 0; i < EmoteWheel.SLOTS; i++) {
			favorites[i] = current.get(i);
		}

		// Options: empty, every unlocked emote, plus any saved favorite not (yet) in the
		// unlocked list so an existing choice is never dropped.
		List<String> options = new ArrayList<>();
		options.add("");
		for (String emote : config.emoteUnlocked) {
			if (!options.contains(emote)) {
				options.add(emote);
			}
		}
		for (String favorite : favorites) {
			if (!favorite.isEmpty() && !options.contains(favorite)) {
				options.add(favorite);
			}
		}

		int columns = 2;
		int rows = EmoteWheel.SLOTS / columns;
		int gridW = columns * BUTTON_W + (columns - 1) * GAP_X;
		int gridH = rows * (BUTTON_H + GAP_Y) - GAP_Y;
		int startX = (this.width - gridW) / 2;
		int startY = (this.height - gridH) / 2;

		for (int i = 0; i < EmoteWheel.SLOTS; i++) {
			int index = i;
			int col = i % columns;
			int row = i / columns;
			int x = startX + col * (BUTTON_W + GAP_X);
			int y = startY + row * (BUTTON_H + GAP_Y);
			CycleButton<String> button = CycleButton.<String>builder(value -> Component.literal(value.isEmpty() ? "(none)" : value), favorites[index]).withValues(options).create(x, y, BUTTON_W, BUTTON_H, Component.literal("Slot " + (index + 1)), (widget, value) -> favorites[index] = value);
			this.addRenderableWidget(button);
		}

		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(this.width / 2 - 75, this.height - 28, 150, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		if (config.emoteUnlocked.isEmpty()) {
			graphics.drawCenteredString(this.font, Component.literal("Open Wynncraft's emotes menu once to fill this list."), this.width / 2, 28, 0xFFA0A0A0);
		}
	}

	@Override
	public void onClose() {
		List<String> saved = new ArrayList<>();
		for (String favorite : favorites) {
			saved.add(favorite);
		}
		config.emoteWheelFavorites = saved;
		config.save();
		this.minecraft.setScreen(parent);
	}
}
