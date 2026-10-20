package tel.eden.mod.emote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.config.BridgeConfig;

/**
 * Assigns the {@value EmoteWheel#SLOTS} emote-wheel favorites across multiple pages.
 * Supports adding pages, deleting pages, switching the active wheel page, and randomizing
 * all slots on the current page. Each slot is a cycle button over the emotes you've unlocked
 * (kept current by {@link UnlockedEmoteDetector}), plus an empty option.
 */
public final class EmoteConfigScreen extends Screen {
	private static final int BUTTON_W = 150;
	private static final int BUTTON_H = 20;
	private static final int GAP_X = 10;
	private static final int GAP_Y = 6;

	private final Screen parent;
	private final BridgeConfig config;
	private final List<List<String>> pages = new ArrayList<>();
	private int currentPageIndex = 0;
	private int activePageIndex = 0;
	private final String[] favorites = new String[EmoteWheel.SLOTS];
	private final List<CycleButton<String>> slotButtons = new ArrayList<>();
	private final List<String> options = new ArrayList<>();

	private Button prevPageBtn;
	private Button nextPageBtn;
	private Button addPageBtn;
	private Button deletePageBtn;
	private Button activeToggleBtn;
	private Button randomizeBtn;

	public EmoteConfigScreen(Screen parent, BridgeConfig config) {
		super(Component.literal("Emote Wheel Favorites"));
		this.parent = parent;
		this.config = config;
	}

	@Override
	protected void init() {
		if (pages.isEmpty()) {
			EmoteWheel.ensurePages(config);
			for (List<String> p : config.emoteWheelPages) {
				List<String> pageCopy = new ArrayList<>(p);
				while (pageCopy.size() < EmoteWheel.SLOTS) {
					pageCopy.add("");
				}
				pages.add(pageCopy);
			}
			activePageIndex = Math.max(0, Math.min(config.emoteWheelActivePage, pages.size() - 1));
			currentPageIndex = activePageIndex;
		} else {
			saveCurrentPageToMemory();
		}

		slotButtons.clear();
		options.clear();
		options.add("");
		for (String emote : config.emoteUnlocked) {
			if (!options.contains(emote)) {
				options.add(emote);
			}
		}
		for (List<String> p : pages) {
			for (String fav : p) {
				if (!fav.isEmpty() && !options.contains(fav)) {
					options.add(fav);
				}
			}
		}

		int gridW = 2 * BUTTON_W + GAP_X; // 310
		int startX = (this.width - gridW) / 2;
		int startY = Math.max(34, (this.height - 180) / 2);

		// Row 1: Page controls
		// [<] (28)  [Page X of Y] (text)  [>] (28)   [+ Page] (82)   [Delete] (82)
		prevPageBtn = Button.builder(Component.literal("<"), b -> previousPage()).bounds(startX, startY, 28, 20).build();
		nextPageBtn = Button.builder(Component.literal(">"), b -> nextPage()).bounds(startX + 102, startY, 28, 20).build();
		addPageBtn = Button.builder(Component.literal("+ Page"), b -> addPage()).bounds(startX + 138, startY, 82, 20).build();
		deletePageBtn = Button.builder(Component.literal("Delete"), b -> deleteCurrentPage()).bounds(startX + 228, startY, 82, 20).build();

		this.addRenderableWidget(prevPageBtn);
		this.addRenderableWidget(nextPageBtn);
		this.addRenderableWidget(addPageBtn);
		this.addRenderableWidget(deletePageBtn);

		// Row 2: Active page toggle & Randomize All
		activeToggleBtn = Button.builder(Component.literal(activeButtonText()), b -> toggleActivePage()).bounds(startX, startY + 24, 150, 20).build();
		randomizeBtn = Button.builder(Component.literal("🎲 Randomize All"), b -> randomizeCurrentPage()).bounds(startX + 160, startY + 24, 150, 20).build();

		this.addRenderableWidget(activeToggleBtn);
		this.addRenderableWidget(randomizeBtn);

		// Load current page favorites into the slots array
		List<String> currentList = pages.get(currentPageIndex);
		for (int i = 0; i < EmoteWheel.SLOTS; i++) {
			favorites[i] = currentList.get(i);
		}

		// Grid of 8 slot buttons
		int columns = 2;
		for (int i = 0; i < EmoteWheel.SLOTS; i++) {
			int index = i;
			int col = i % columns;
			int row = i / columns;
			int x = startX + col * (BUTTON_W + GAP_X);
			int y = startY + 50 + row * (BUTTON_H + GAP_Y);
			CycleButton<String> button = CycleButton.<String>builder(value -> Component.literal(value.isEmpty() ? "(none)" : value), favorites[index]).withValues(options).create(x, y, BUTTON_W, BUTTON_H, Component.literal("Slot " + (index + 1)), (widget, value) -> favorites[index] = value);
			slotButtons.add(button);
			this.addRenderableWidget(button);
		}

		// Done button
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(this.width / 2 - 75, this.height - 28, 150, 20).build());

		updateHeaderWidgets();
	}

	private void updateHeaderWidgets() {
		if (prevPageBtn != null) {
			prevPageBtn.active = pages.size() > 1;
		}
		if (nextPageBtn != null) {
			nextPageBtn.active = pages.size() > 1;
		}
		if (deletePageBtn != null) {
			deletePageBtn.active = pages.size() > 1;
		}
		if (activeToggleBtn != null) {
			activeToggleBtn.setMessage(Component.literal(activeButtonText()));
		}
	}

	private String activeButtonText() {
		return (currentPageIndex == activePageIndex) ? "§a✔ Active Page" : "Set as Active";
	}

	private void saveCurrentPageToMemory() {
		List<String> pageList = new ArrayList<>();
		for (String fav : favorites) {
			pageList.add(fav != null ? fav : "");
		}
		while (pageList.size() < EmoteWheel.SLOTS) {
			pageList.add("");
		}
		if (currentPageIndex < pages.size()) {
			pages.set(currentPageIndex, pageList);
		} else {
			pages.add(pageList);
		}
	}

	private void loadPageFromMemory(int index) {
		currentPageIndex = Math.max(0, Math.min(index, pages.size() - 1));
		List<String> pageList = pages.get(currentPageIndex);
		for (int i = 0; i < EmoteWheel.SLOTS; i++) {
			favorites[i] = (i < pageList.size()) ? pageList.get(i) : "";
			if (!favorites[i].isEmpty() && !options.contains(favorites[i])) {
				options.add(favorites[i]);
			}
		}
		for (int i = 0; i < slotButtons.size() && i < EmoteWheel.SLOTS; i++) {
			slotButtons.get(i).setValue(favorites[i]);
		}
		updateHeaderWidgets();
	}

	private void addPage() {
		saveCurrentPageToMemory();
		List<String> newPage = new ArrayList<>();
		for (int i = 0; i < EmoteWheel.SLOTS; i++) {
			newPage.add("");
		}
		pages.add(newPage);
		loadPageFromMemory(pages.size() - 1);
	}

	private void deleteCurrentPage() {
		if (pages.size() <= 1) {
			return;
		}
		pages.remove(currentPageIndex);
		if (activePageIndex >= pages.size()) {
			activePageIndex = pages.size() - 1;
		} else if (activePageIndex == currentPageIndex) {
			activePageIndex = Math.min(activePageIndex, pages.size() - 1);
		} else if (activePageIndex > currentPageIndex) {
			activePageIndex--;
		}
		int nextIndex = Math.min(currentPageIndex, pages.size() - 1);
		loadPageFromMemory(nextIndex);
	}

	private void toggleActivePage() {
		activePageIndex = currentPageIndex;
		updateHeaderWidgets();
	}

	private void previousPage() {
		if (pages.size() <= 1) {
			return;
		}
		saveCurrentPageToMemory();
		int prev = (currentPageIndex - 1 + pages.size()) % pages.size();
		loadPageFromMemory(prev);
	}

	private void nextPage() {
		if (pages.size() <= 1) {
			return;
		}
		saveCurrentPageToMemory();
		int next = (currentPageIndex + 1) % pages.size();
		loadPageFromMemory(next);
	}

	/** Randomly select {@code count} emotes from candidates pool, wrapping if pool is smaller than count. */
	public static List<String> randomizeEmotes(List<String> pool, int count) {
		if (pool == null || pool.isEmpty() || count <= 0) {
			List<String> empty = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				empty.add("");
			}
			return empty;
		}
		List<String> candidates = new ArrayList<>();
		for (String s : pool) {
			if (s != null && !s.isBlank() && !candidates.contains(s)) {
				candidates.add(s);
			}
		}
		if (candidates.isEmpty()) {
			List<String> empty = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				empty.add("");
			}
			return empty;
		}
		Collections.shuffle(candidates);
		List<String> result = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			result.add(candidates.get(i % candidates.size()));
		}
		return result;
	}

	private void randomizeCurrentPage() {
		List<String> pool = new ArrayList<>();
		for (String emote : config.emoteUnlocked) {
			if (emote != null && !emote.isBlank() && !pool.contains(emote)) {
				pool.add(emote);
			}
		}
		for (String opt : options) {
			if (opt != null && !opt.isBlank() && !pool.contains(opt)) {
				pool.add(opt);
			}
		}
		if (pool.isEmpty()) {
			if (this.minecraft != null && this.minecraft.player != null) {
				this.minecraft.player.displayClientMessage(Component.literal("No unlocked emotes found to randomize from! Open Wynncraft's /emote menu first.").withStyle(net.minecraft.ChatFormatting.RED), true);
			}
			return;
		}
		List<String> randomized = randomizeEmotes(pool, EmoteWheel.SLOTS);
		for (int i = 0; i < EmoteWheel.SLOTS; i++) {
			favorites[i] = randomized.get(i);
			if (!favorites[i].isEmpty() && !options.contains(favorites[i])) {
				options.add(favorites[i]);
			}
			if (i < slotButtons.size()) {
				slotButtons.get(i).setValue(favorites[i]);
			}
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (pages.size() > 1) {
			if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
				previousPage();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_RIGHT || event.key() == GLFW.GLFW_KEY_PAGE_DOWN) {
				nextPage();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 1) { // Right-click steps backwards through options
			for (int i = 0; i < slotButtons.size(); i++) {
				CycleButton<String> button = slotButtons.get(i);
				if (button.isMouseOver(event.x(), event.y())) {
					cycleSlotBackward(i, button);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void cycleSlotBackward(int index, CycleButton<String> button) {
		String prev = previousOption(options, button.getValue());
		favorites[index] = prev;
		button.setValue(prev);
		if (this.minecraft != null) {
			button.playDownSound(this.minecraft.getSoundManager());
		}
	}

	static String previousOption(List<String> options, String current) {
		if (options == null || options.isEmpty()) {
			return "";
		}
		int idx = options.indexOf(current);
		return options.get(idx <= 0 ? options.size() - 1 : idx - 1);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
		super.render(graphics, mouseX, mouseY, delta);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFFFF);

		int gridW = 2 * BUTTON_W + GAP_X;
		int startX = (this.width - gridW) / 2;
		int startY = Math.max(34, (this.height - 180) / 2);

		String pageIndicator = "Page " + (currentPageIndex + 1) + " of " + pages.size();
		graphics.drawCenteredString(this.font, Component.literal(pageIndicator), startX + 65, startY + 6, 0xFFFFFFFF);

		if (config.emoteUnlocked.isEmpty()) {
			graphics.drawCenteredString(this.font, Component.literal("Open Wynncraft's emotes menu once to fill this list."), this.width / 2, 24, 0xFFA0A0A0);
		}
	}

	@Override
	public void onClose() {
		saveCurrentPageToMemory();
		if (pages.isEmpty()) {
			List<String> blank = new ArrayList<>();
			for (int i = 0; i < EmoteWheel.SLOTS; i++) {
				blank.add("");
			}
			pages.add(blank);
		}
		if (activePageIndex < 0 || activePageIndex >= pages.size()) {
			activePageIndex = 0;
		}
		config.emoteWheelPages = new ArrayList<>(pages);
		config.emoteWheelActivePage = activePageIndex;
		config.emoteWheelFavorites = new ArrayList<>(pages.get(activePageIndex));
		config.save();
		if (this.minecraft != null) {
			this.minecraft.setScreen(parent);
		}
	}
}
