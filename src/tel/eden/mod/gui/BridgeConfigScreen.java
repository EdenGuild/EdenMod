package tel.eden.mod.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.update.UpdateChecker;
import tel.eden.mod.update.UpdateInfo;

/**
 * Fullscreen bridge config screen.
 *
 * <p>Unlike the other Eden screens (which are centered floating panels), the
 * config screen fills the window like a vanilla options menu. Settings live in a
 * smooth-scrolling list built from row helpers: each option is a single
 * {@code addToggleRow}/{@code addCycleRow}/{@code addSliderRow} call that registers
 * the control, its reset button, and its label together — so the screen scales to
 * any number of settings, and the list glides (pixel-smooth) when it overflows.
 *
 * <p>Row controls are registered as normal Screen widgets (so input — including
 * slider dragging — is handled natively), but rendered by hand inside a scissor so
 * scrolling clips cleanly. Rows outside the viewport are hidden, so they can't be
 * clicked from the header/footer gaps.
 */
public final class BridgeConfigScreen extends Screen {
	private static final Identifier LOGO_TEXTURE = Identifier.parse("edenmod:icon.png");
	private static final int LOGO_W = 256;
	private static final int LOGO_H = 256;

	private static final int MAX_CONTENT_WIDTH = 420;
	private static final int ROW_HEIGHT = 24;
	private static final int LIST_TOP_PADDING = 4;
	private static final int CONTROL_W = 96;
	private static final int RESET_W = 20;
	private static final int SCROLLBAR_W = 6;
	private static final int HEADER_BOTTOM = 120;
	private static final int FOOTER_HEIGHT = 40;
	// Fraction of the remaining distance the list glides each frame (smoothness).
	private static final double SCROLL_EASE = 0.4;

	public enum Category {
		ALL("All"), GENERAL("General"), PARTY_RAIDS("Party & Raids"), WAR("War & Territory"), VISUALS("Visuals & QoL");

		private final String label;

		Category(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	private static Category selectedCategory = Category.ALL;

	private final Screen parent;
	private final EdenModClient mod;
	private final BridgeConfig config;

	private final List<SettingRow> allRows = new ArrayList<>();
	private final List<SettingRow> visibleRows = new ArrayList<>();
	private Button linkButton;
	private EdenDropdown<Category> categoryDropdown;
	private double scroll;
	private double scrollTarget;
	private boolean draggingScrollbar;

	public static Screen create(Screen parent, EdenModClient mod) {
		return new BridgeConfigScreen(parent, mod);
	}

	private BridgeConfigScreen(Screen parent, EdenModClient mod) {
		super(Component.literal("EdenMod"));
		this.parent = parent;
		this.mod = mod;
		this.config = mod.config();
	}

	@Override
	protected void init() {
		super.init();
		allRows.clear();
		visibleRows.clear();
		int cx = contentX();
		int cw = contentWidth();

		int btnW = Math.max(100, Math.min(130, cw / 3));
		int dropW = cw - btnW - 8;

		linkButton = this.addRenderableWidget(Button.builder(Component.literal("Link account"), button -> startLinkFlow()).bounds(cx, 92, btnW, 20).build());

		EdenDropdown.PopupSettings popupSettings = new EdenDropdown.PopupSettings(HEADER_BOTTOM, this.height - FOOTER_HEIGHT, 20, Category.values().length);
		categoryDropdown = this.addRenderableWidget(new EdenDropdown<>(cx + btnW + 8, 92, dropW, 20, this.font, List.of(Category.values()), selectedCategory, cat -> "Category: " + cat.label(), newCat -> {
			selectedCategory = newCat;
			updateVisibleRows();
		}, openDrop -> {
		}, popupSettings));

		// --- One line per setting; the list handles layout + smooth scrolling. ---
		// General
		addToggleRow(Category.GENERAL, "Bridge", () -> config.enabled, v -> config.enabled = v, "Enabled", "Disabled", true);
		addToggleRow(Category.GENERAL, "My login/logout messages", () -> config.announceSelfPresence, v -> config.announceSelfPresence = v, "On", "Off", true);
		addToggleRow(Category.GENERAL, "Party feed", () -> config.partyAnnounce, v -> config.partyAnnounce = v, "On", "Off", true);
		addToggleRow(Category.GENERAL, "Auto /stream on join", () -> config.autoStream, v -> config.autoStream = v, "On", "Off", false);
		addToggleRow(Category.GENERAL, "Click shouts to reply", () -> config.shoutsClickable, v -> config.shoutsClickable = v, "On", "Off", true);
		addToggleRow(Category.GENERAL, "Click-to-congratulate", () -> config.clickToCongratulate, v -> config.clickToCongratulate = v, "On", "Off", false);
		EditBox congratsBox = new EditBox(this.font, 0, 0, CONTROL_W, 20, Component.literal("Congrats message"));
		congratsBox.setMaxLength(80);
		congratsBox.setValue(config.congratsMessage);
		congratsBox.setResponder(value -> {
			config.congratsMessage = value;
			config.save();
		});
		addRow(Category.GENERAL, "Congrats message", congratsBox, () -> {
			if (!congratsBox.isFocused() && !congratsBox.getValue().equals(config.congratsMessage)) {
				congratsBox.setValue(config.congratsMessage);
			}
		}, () -> {
			config.congratsMessage = "Congrats!";
			congratsBox.setValue("Congrats!");
		});

		// Party & Raids
		addToggleRow(Category.PARTY_RAIDS, "Party member outlines", () -> config.partyHighlightEnabled, v -> config.partyHighlightEnabled = v, "On", "Off", true);
		addToggleRow(Category.PARTY_RAIDS, "Party health bars", () -> config.partyHealthBarEnabled, v -> config.partyHealthBarEnabled = v, "On", "Off", true);
		addToggleRow(Category.PARTY_RAIDS, "Show own health bar (F5)", () -> config.partyHealthBarShowSelf, v -> config.partyHealthBarShowSelf = v, "On", "Off", true);
		HealthBarScaleSlider scaleSlider = new HealthBarScaleSlider(CONTROL_W, 20);
		addSliderRow(Category.PARTY_RAIDS, "Party health bar size", scaleSlider, scaleSlider::syncFromConfig, () -> config.partyHealthBarScale = 100);
		HealthBarDistanceScaleSlider distanceScaleSlider = new HealthBarDistanceScaleSlider(CONTROL_W, 20);
		addSliderRow(Category.PARTY_RAIDS, "Distance size boost", distanceScaleSlider, distanceScaleSlider::syncFromConfig, () -> config.partyHealthBarDistanceScale = 0);
		addToggleRow(Category.PARTY_RAIDS, "Raid ready-up ping", () -> config.raidReadyPing, v -> config.raidReadyPing = v, "On", "Off", true);
		RaidReminderSlider reminderSlider = new RaidReminderSlider(CONTROL_W, 20);
		addSliderRow(Category.PARTY_RAIDS, "Ready reminder delay", reminderSlider, reminderSlider::syncFromConfig, () -> config.raidReadyReminderSeconds = 60);

		// War & Territory
		addToggleRow(Category.WAR, "Attack timers HUD", () -> config.warAttackTimers, v -> config.warAttackTimers = v, "On", "Off", true);
		AttackTimerRowsSlider rowsSlider = new AttackTimerRowsSlider(CONTROL_W, 20);
		addSliderRow(Category.WAR, "Max attack-timer rows", rowsSlider, rowsSlider::syncFromConfig, () -> config.warAttackTimerMaxRows = 14);
		addToggleRow(Category.WAR, "Green beacon at soonest war", () -> config.warGreenBeacon, v -> config.warGreenBeacon = v, "On", "Off", true);
		addToggleRow(Category.WAR, "War info overlay (DPS/EHP)", () -> config.warDpsHud, v -> config.warDpsHud = v, "On", "Off", true);
		addToggleRow(Category.WAR, "Weekly war count HUD", () -> config.warWeeklyCountHud, v -> config.warWeeklyCountHud = v, "On", "Off", false);

		// Visuals & QoL
		if (config.isSecretUnlocked(BridgeConfig.SECRET_BABY_PLAYERS)) {
			addToggleRow(Category.VISUALS, "Baby players", EdenMenuScreen::isBabyModeEnabled, EdenMenuScreen::setBabyModeEnabled, "On", "Off", false);
		}
		addButtonRow(Category.VISUALS, "HUD layout", () -> Component.literal("Edit..."), () -> this.minecraft.setScreen(new HudLayoutScreen(this, config)), () -> {
		});
		addCycleRow(Category.VISUALS, "Chat emote tools", () -> config.chatEmoteToolsMode.label(), () -> config.chatEmoteToolsMode = nextChatEmoteToolsMode(config.chatEmoteToolsMode), () -> config.chatEmoteToolsMode = BridgeConfig.ChatEmoteToolsMode.UI_AND_AUTO);
		addToggleRow(Category.VISUALS, "Allow emote picker outside chat", () -> config.emotePickerOpenFromGameplay, v -> config.emotePickerOpenFromGameplay = v, "Allowed", "Chat only", true);
		addToggleRow(Category.VISUALS, "Emote wheel", () -> config.emoteWheelEnabled, v -> config.emoteWheelEnabled = v, "On", "Off", true);
		addButtonRow(Category.VISUALS, "Emote wheel favorites", () -> Component.literal("Edit..."), () -> this.minecraft.setScreen(new tel.eden.mod.emote.EmoteConfigScreen(this, config)), () -> {
		});
		addCycleRow(Category.VISUALS, "Game messages", () -> shortGameModeLabel(config.gameDisplayMode), () -> config.gameDisplayMode = nextGameMode(config.gameDisplayMode), () -> config.gameDisplayMode = BridgeConfig.GameDisplayMode.ALL);
		PreviewSizeSlider slider = new PreviewSizeSlider(CONTROL_W, 20);
		addSliderRow(Category.VISUALS, "Image preview size", slider, slider::syncFromConfig, () -> config.imagePreviewSize = 40);
		addToggleRow(Category.VISUALS, "Custom item textures", () -> config.customItemTextures, v -> config.customItemTextures = v, "On", "Off", true);
		addToggleRow(Category.VISUALS, "Consumable labels", () -> config.consumableLabels, v -> config.consumableLabels = v, "On", "Off", true);
		addToggleRow(Category.VISUALS, "Dropped item scaling", () -> config.groundItemVisibility, v -> config.groundItemVisibility = v, "On", "Off", false);
		addButtonRow(Category.VISUALS, "Dropped item rules", () -> Component.literal("Edit..."), () -> this.minecraft.setScreen(new GroundItemVisibilityScreen(this, config)), () -> config.groundItemVisibilityRules = new ArrayList<>());
		// ------------------------------------------------------------------------

		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose()).bounds(cx, this.height - 30, cw, 20).build());

		refreshRows();
		updateVisibleRows();
	}

	private void addToggleRow(Category category, String label, Supplier<Boolean> get, Consumer<Boolean> set, String onText, String offText, boolean resetValue) {
		addButtonRow(category, label, () -> Component.literal(get.get() ? onText : offText), () -> set.accept(!get.get()), () -> set.accept(resetValue));
	}

	private void addCycleRow(Category category, String label, Supplier<String> valueLabel, Runnable onClick, Runnable onReset) {
		addButtonRow(category, label, () -> Component.literal(valueLabel.get()), onClick, onReset);
	}

	private void addButtonRow(Category category, String label, Supplier<Component> valueText, Runnable onClick, Runnable onReset) {
		Button control = Button.builder(Component.empty(), button -> {
			onClick.run();
			saveConfig();
		}).bounds(0, 0, CONTROL_W, 20).build();
		addRow(category, label, control, () -> control.setMessage(valueText.get()), onReset);
	}

	private void addSliderRow(Category category, String label, AbstractSliderButton slider, Runnable sync, Runnable onReset) {
		addRow(category, label, slider, sync, onReset);
	}

	private void addRow(Category category, String label, AbstractWidget control, Runnable refresh, Runnable onReset) {
		Button reset = Button.builder(Component.literal("R"), button -> {
			onReset.run();
			saveConfig();
		}).bounds(0, 0, RESET_W, 20).build();
		// addWidget (input only, not a renderable): the Screen routes clicks/drags to
		// them natively — so the slider works — while we draw them inside a scissor.
		this.addWidget(control);
		this.addWidget(reset);
		allRows.add(new SettingRow(category, label, control, reset, refresh));
	}

	private void updateVisibleRows() {
		visibleRows.clear();
		for (SettingRow row : allRows) {
			if (selectedCategory == Category.ALL || row.category == selectedCategory) {
				visibleRows.add(row);
			} else {
				row.control.visible = false;
				row.reset.visible = false;
			}
		}
		scrollTarget = 0;
		scroll = 0;
		layoutRows();
	}

	private void saveConfig() {
		config.save();
		refreshRows();
	}

	private void refreshRows() {
		for (SettingRow row : allRows) {
			row.refresh.run();
		}
	}

	private void startLinkFlow() {
		linkButton.setMessage(Component.literal("Opening browser..."));
		linkButton.active = false;
		mod.startLinkFlow(() -> this.minecraft.execute(() -> this.minecraft.setScreen(BridgeConfigScreen.create(parent, mod))));
	}

	private BridgeConfig.GameDisplayMode nextGameMode(BridgeConfig.GameDisplayMode current) {
		BridgeConfig.GameDisplayMode[] values = BridgeConfig.GameDisplayMode.values();
		return values[(current.ordinal() + 1) % values.length];
	}

	private BridgeConfig.ChatEmoteToolsMode nextChatEmoteToolsMode(BridgeConfig.ChatEmoteToolsMode current) {
		BridgeConfig.ChatEmoteToolsMode[] values = BridgeConfig.ChatEmoteToolsMode.values();
		return values[(current.ordinal() + 1) % values.length];
	}

	private String shortGameModeLabel(BridgeConfig.GameDisplayMode mode) {
		return switch (mode) {
			case ALL -> "Shown";
			case NONE -> "Hidden";
			case REACTIONS -> "React Only";
		};
	}

	private Component linkStatusText() {
		String name = EdenModClient.playerName();
		return switch (mod.bridgeStatus()) {
			case FULL -> Component.literal(name == null || name.isEmpty() ? "Linked" : "Linked as " + name).withStyle(style -> style.withColor(0x55FF55));
			case NOT_MEMBER -> Component.literal("Linked, but not in the Eden guild").withStyle(style -> style.withColor(0xFFAA00));
			case NOT_LINKED -> Component.literal("Not linked").withStyle(style -> style.withColor(0xFFAA00));
			case UNKNOWN -> Component.literal("Join Wynncraft to check status").withStyle(style -> style.withColor(0xAAAAAA));
		};
	}

	private Component rankStatusText() {
		if (mod.bridgeStatus() != EdenModClient.BridgeStatus.FULL) {
			return Component.empty();
		}
		String guildRank = mod.liveGuildRank();
		String discordRank = mod.liveDiscordRank();
		if ((guildRank == null || guildRank.isEmpty()) && (discordRank == null || discordRank.isEmpty())) {
			return Component.empty();
		}
		String label = (guildRank != null && !guildRank.isEmpty() && discordRank != null && !discordRank.isEmpty()) ? guildRank + "  ·  " + discordRank : (guildRank != null && !guildRank.isEmpty() ? guildRank : discordRank);
		return Component.literal(label).withStyle(style -> style.withColor(0x88CC88));
	}

	// --- Fullscreen geometry (recomputed each frame so it tracks window resizes). ---

	private int contentWidth() {
		return Math.min(MAX_CONTENT_WIDTH, this.width - 40);
	}

	private int contentX() {
		return (this.width - contentWidth()) / 2;
	}

	private int listTop() {
		return HEADER_BOTTOM;
	}

	private int listHeight() {
		return Math.max(ROW_HEIGHT, this.height - FOOTER_HEIGHT - HEADER_BOTTOM);
	}

	private int contentHeight() {
		return LIST_TOP_PADDING + visibleRows.size() * ROW_HEIGHT;
	}

	private double maxScroll() {
		return Math.max(0, contentHeight() - listHeight());
	}

	private double clampScroll(double value) {
		return Math.max(0, Math.min(value, maxScroll()));
	}

	private int scrollbarX() {
		return contentX() + contentWidth() + 4;
	}

	private int controlX() {
		return contentX() + contentWidth() - 10 - RESET_W - 8 - CONTROL_W;
	}

	private int resetX() {
		return contentX() + contentWidth() - 10 - RESET_W;
	}

	private int rowY(int index) {
		return (int) Math.round(listTop() + LIST_TOP_PADDING - scroll + index * ROW_HEIGHT);
	}

	private boolean rowInView(int rowY) {
		return rowY + ROW_HEIGHT > listTop() && rowY < listTop() + listHeight();
	}

	private void positionRow(SettingRow row, int rowY) {
		row.control.setX(controlX());
		row.control.setY(rowY);
		row.control.setWidth(CONTROL_W);
		row.reset.setX(resetX());
		row.reset.setY(rowY);
		row.reset.setWidth(RESET_W);
	}

	private void advanceScroll() {
		scrollTarget = clampScroll(scrollTarget);
		double diff = scrollTarget - scroll;
		scroll = Math.abs(diff) < 0.5 ? scrollTarget : scroll + diff * SCROLL_EASE;
		scroll = clampScroll(scroll);
	}

	/** Position each row and hide the ones scrolled outside the viewport. */
	private void layoutRows() {
		for (int i = 0; i < visibleRows.size(); i++) {
			int rowY = rowY(i);
			boolean inView = rowInView(rowY);
			SettingRow row = visibleRows.get(i);
			row.control.visible = inView;
			row.reset.visible = inView;
			if (inView) {
				positionRow(row, rowY);
			}
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		this.renderMenuBackground(g);
		advanceScroll();
		layoutRows();
		super.render(g, mouseX, mouseY, delta);

		int cx = contentX();
		int cw = contentWidth();
		int top = listTop();
		int height = listHeight();
		int centerX = this.width / 2;

		int logoWidth = 40;
		int logoHeight = logoWidth * LOGO_H / LOGO_W;
		g.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, centerX - (logoWidth / 2), 10, 0.0f, 0.0f, logoWidth, logoHeight, LOGO_W, LOGO_H, LOGO_W, LOGO_H);
		g.drawCenteredString(this.font, this.title, centerX, 54, 0xFFFFFFFF);
		g.drawCenteredString(this.font, linkStatusText(), centerX, 66, 0xFFFFFFFF);
		g.drawCenteredString(this.font, rankStatusText(), centerX, 78, 0xFFFFFFFF);

		g.fill(cx, top, cx + cw, top + height, 0x22000000);

		int labelX = cx + 10;
		g.enableScissor(cx, top, cx + cw, top + height);
		for (int i = 0; i < visibleRows.size(); i++) {
			int rowY = rowY(i);
			if (!rowInView(rowY)) {
				continue;
			}
			SettingRow row = visibleRows.get(i);
			row.control.render(g, mouseX, mouseY, delta);
			row.reset.render(g, mouseX, mouseY, delta);
			g.drawString(this.font, row.label, labelX, rowY + 6, 0xFFA0A0A0);
		}
		g.disableScissor();

		drawScrollbar(g, scrollbarX(), top, SCROLLBAR_W, height);

		String currentVer = UpdateChecker.currentVersion();
		if (currentVer == null) {
			currentVer = "Unknown";
		}
		UpdateInfo pendingUpdate = EdenModClient.instance().getPendingUpdate();
		String versionText = "v" + currentVer;
		String updateText = pendingUpdate != null ? "Update Available: " + pendingUpdate.version() : "Up to date";
		g.drawString(this.font, versionText, this.width - this.font.width(versionText) - 6, 6, 0xFFAAAAAA);
		g.drawString(this.font, updateText, this.width - this.font.width(updateText) - 6, 18, pendingUpdate != null ? 0xFF55FF55 : 0xFFAAAAAA);

		if (categoryDropdown != null) {
			categoryDropdown.renderPopup(g, mouseX, mouseY);
		}
	}

	private void drawScrollbar(GuiGraphics g, int x, int y, int width, int height) {
		if (maxScroll() <= 0) {
			return;
		}
		g.fill(x, y, x + width, y + height, 0x2A000000);
		int thumbHeight = Math.max(18, Math.round(height * (listHeight() / (float) contentHeight())));
		int travel = Math.max(1, height - thumbHeight);
		int thumbY = y + (int) Math.round((scroll / maxScroll()) * travel);
		g.fill(x + 1, thumbY, x + width - 1, thumbY + thumbHeight, 0xFF8A8A8A);
		g.fill(x + 1, thumbY, x + width - 1, thumbY + 1, 0xFFD8D8D8);
		g.fill(x + 1, thumbY + thumbHeight - 1, x + width - 1, thumbY + thumbHeight, 0xFF444444);
	}

	private boolean isOverScrollbar(double mouseX, double mouseY) {
		int top = listTop();
		return mouseX >= scrollbarX() && mouseX <= scrollbarX() + SCROLLBAR_W && mouseY >= top && mouseY <= top + listHeight();
	}

	private boolean isOverList(double mouseX, double mouseY) {
		int top = listTop();
		return mouseX >= contentX() && mouseX <= contentX() + contentWidth() && mouseY >= top && mouseY <= top + listHeight();
	}

	private void scrollToMouse(double mouseY) {
		double maxOffset = maxScroll();
		if (maxOffset <= 0) {
			scroll = 0;
			scrollTarget = 0;
			return;
		}
		int top = listTop();
		int height = listHeight();
		int thumbHeight = Math.max(18, Math.round(height * (listHeight() / (float) contentHeight())));
		double relative = mouseY - top - (thumbHeight / 2.0);
		double range = Math.max(1, height - thumbHeight);
		double percent = Math.max(0.0, Math.min(1.0, relative / range));
		scrollTarget = percent * maxOffset;
		scroll = scrollTarget;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		if (categoryDropdown != null && categoryDropdown.isOpen()) {
			if (categoryDropdown.isOverPopup(event.x(), event.y())) {
				categoryDropdown.mouseClicked(event, bl);
				return true;
			}
			if (!categoryDropdown.isMouseOver(event.x(), event.y())) {
				categoryDropdown.close();
			}
		}
		if (event.button() == 0 && isOverScrollbar(event.x(), event.y())) {
			draggingScrollbar = true;
			scrollToMouse(event.y());
			return true;
		}
		return super.mouseClicked(event, bl);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingScrollbar = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingScrollbar) {
			scrollToMouse(event.y());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
		if (categoryDropdown != null && categoryDropdown.isOpen() && categoryDropdown.isOverPopup(mouseX, mouseY)) {
			return categoryDropdown.mouseScrolled(mouseX, mouseY, dx, dy);
		}
		if (maxScroll() > 0 && (isOverList(mouseX, mouseY) || isOverScrollbar(mouseX, mouseY))) {
			scrollTarget = clampScroll(scrollTarget - dy * ROW_HEIGHT * 2);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, dx, dy);
	}

	@Override
	public void onClose() {
		config.save();
		this.minecraft.setScreen(parent);
	}

	private static final class SettingRow {
		private final Category category;
		private final String label;
		private final AbstractWidget control;
		private final Button reset;
		private final Runnable refresh;

		private SettingRow(Category category, String label, AbstractWidget control, Button reset, Runnable refresh) {
			this.category = category;
			this.label = label;
			this.control = control;
			this.reset = reset;
			this.refresh = refresh;
		}
	}

	private final class PreviewSizeSlider extends AbstractSliderButton {
		private static final int MIN = 1;
		private static final int MAX = 100;

		private PreviewSizeSlider(int width, int height) {
			super(0, 0, width, height, Component.empty(), 0.0d);
			syncFromConfig();
		}

		private void syncFromConfig() {
			this.value = (config.imagePreviewSize - MIN) / (double) (MAX - MIN);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(config.imagePreviewSize + "%"));
		}

		@Override
		protected void applyValue() {
			int snapped = MIN + (int) Math.round(this.value * (MAX - MIN));
			if (snapped != config.imagePreviewSize) {
				config.imagePreviewSize = snapped;
				config.save();
			}
			updateMessage();
		}
	}

	private final class AttackTimerRowsSlider extends AbstractSliderButton {
		private static final int MIN = 1;
		private static final int MAX = 50;

		private AttackTimerRowsSlider(int width, int height) {
			super(0, 0, width, height, Component.empty(), 0.0d);
			syncFromConfig();
		}

		private void syncFromConfig() {
			this.value = (config.warAttackTimerMaxRows - MIN) / (double) (MAX - MIN);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(config.warAttackTimerMaxRows + " rows"));
		}

		@Override
		protected void applyValue() {
			int snapped = MIN + (int) Math.round(this.value * (MAX - MIN));
			if (snapped != config.warAttackTimerMaxRows) {
				config.warAttackTimerMaxRows = snapped;
				config.save();
			}
			updateMessage();
		}
	}

	private final class RaidReminderSlider extends AbstractSliderButton {
		private static final int MIN = 10;
		private static final int MAX = 300;

		private RaidReminderSlider(int width, int height) {
			super(0, 0, width, height, Component.empty(), 0.0d);
			syncFromConfig();
		}

		private void syncFromConfig() {
			int current = Math.max(MIN, Math.min(MAX, config.raidReadyReminderSeconds));
			this.value = (current - MIN) / (double) (MAX - MIN);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int sec = config.raidReadyReminderSeconds;
			if (sec == 60) {
				setMessage(Component.literal("1 min"));
			} else if (sec % 60 == 0) {
				setMessage(Component.literal((sec / 60) + " mins"));
			} else if (sec < 60) {
				setMessage(Component.literal(sec + "s"));
			} else {
				setMessage(Component.literal((sec / 60) + "m " + (sec % 60) + "s"));
			}
		}

		@Override
		protected void applyValue() {
			int snapped = MIN + (int) Math.round(this.value * (MAX - MIN));
			snapped = Math.round(snapped / 5.0f) * 5;
			snapped = Math.max(MIN, Math.min(MAX, snapped));
			if (snapped != config.raidReadyReminderSeconds) {
				config.raidReadyReminderSeconds = snapped;
				config.save();
			}
			updateMessage();
		}
	}

	private final class HealthBarScaleSlider extends AbstractSliderButton {
		private static final int MIN = 50;
		private static final int MAX = 200;

		private HealthBarScaleSlider(int width, int height) {
			super(0, 0, width, height, Component.empty(), 0.0d);
			syncFromConfig();
		}

		private void syncFromConfig() {
			int current = Math.max(MIN, Math.min(MAX, config.partyHealthBarScale));
			this.value = (current - MIN) / (double) (MAX - MIN);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(config.partyHealthBarScale + "%"));
		}

		@Override
		protected void applyValue() {
			int snapped = MIN + (int) Math.round(this.value * (MAX - MIN));
			snapped = Math.round(snapped / 5.0f) * 5;
			snapped = Math.max(MIN, Math.min(MAX, snapped));
			if (snapped != config.partyHealthBarScale) {
				config.partyHealthBarScale = snapped;
				config.save();
			}
			updateMessage();
		}
	}

	private final class HealthBarDistanceScaleSlider extends AbstractSliderButton {
		private static final int MIN = 0;
		private static final int MAX = 200;

		private HealthBarDistanceScaleSlider(int width, int height) {
			super(0, 0, width, height, Component.empty(), 0.0d);
			syncFromConfig();
		}

		private void syncFromConfig() {
			int current = Math.max(MIN, Math.min(MAX, config.partyHealthBarDistanceScale));
			this.value = (current - MIN) / (double) (MAX - MIN);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			if (config.partyHealthBarDistanceScale <= 0) {
				setMessage(Component.literal("Off"));
			} else {
				setMessage(Component.literal(config.partyHealthBarDistanceScale + "%"));
			}
		}

		@Override
		protected void applyValue() {
			int snapped = MIN + (int) Math.round(this.value * (MAX - MIN));
			snapped = Math.round(snapped / 5.0f) * 5;
			snapped = Math.max(MIN, Math.min(MAX, snapped));
			if (snapped != config.partyHealthBarDistanceScale) {
				config.partyHealthBarDistanceScale = snapped;
				config.save();
			}
			updateMessage();
		}
	}
}
