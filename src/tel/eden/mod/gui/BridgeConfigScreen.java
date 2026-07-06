package tel.eden.mod.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.update.UpdateChecker;
import tel.eden.mod.update.UpdateInfo;

public final class BridgeConfigScreen extends EdenReferenceScreen {
	private static final Identifier LOGO_TEXTURE = Identifier.parse("edenmod:icon.png");
	private static final int LOGO_W = 722;
	private static final int LOGO_H = 693;

	private static final int BASE_PANEL_WIDTH = 420;
	private static final int BASE_PANEL_HEIGHT = 312;

	private final Screen parent;
	private final EdenModClient mod;
	private final BridgeConfig config;

	private EdenPanelLayout layout;
	private Button linkButton;
	private Button enabledButton;
	private Button selfPresenceButton;
	private Button partyFeedButton;
	private Button gameMessagesButton;
	private PreviewSizeSlider previewSlider;

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
		updateReferenceSpace();
		layout = EdenPanelLayout.centered(virtualWidth, virtualHeight, BASE_PANEL_WIDTH, BASE_PANEL_HEIGHT);

		linkButton = this.addRenderableWidget(Button.builder(Component.literal("Link account"), button -> startLinkFlow()).bounds(layout.x(15), layout.y(88), layout.w(390), layout.h(20)).build());

		enabledButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			config.enabled = !config.enabled;
			saveConfig();
		}).bounds(layout.x(285), layout.y(122), layout.w(96), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("R"), button -> {
			config.enabled = true;
			saveConfig();
		}).bounds(layout.x(385), layout.y(122), layout.w(20), layout.h(20)).build());

		selfPresenceButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			config.announceSelfPresence = !config.announceSelfPresence;
			saveConfig();
		}).bounds(layout.x(285), layout.y(148), layout.w(96), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("R"), button -> {
			config.announceSelfPresence = true;
			saveConfig();
		}).bounds(layout.x(385), layout.y(148), layout.w(20), layout.h(20)).build());

		partyFeedButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			config.partyAnnounce = !config.partyAnnounce;
			saveConfig();
		}).bounds(layout.x(285), layout.y(174), layout.w(96), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("R"), button -> {
			config.partyAnnounce = true;
			saveConfig();
		}).bounds(layout.x(385), layout.y(174), layout.w(20), layout.h(20)).build());

		gameMessagesButton = this.addRenderableWidget(Button.builder(Component.empty(), button -> {
			config.gameDisplayMode = nextGameMode(config.gameDisplayMode);
			saveConfig();
		}).bounds(layout.x(285), layout.y(200), layout.w(96), layout.h(20)).build());
		this.addRenderableWidget(Button.builder(Component.literal("R"), button -> {
			config.gameDisplayMode = BridgeConfig.GameDisplayMode.ALL;
			saveConfig();
		}).bounds(layout.x(385), layout.y(200), layout.w(20), layout.h(20)).build());

		previewSlider = this.addRenderableWidget(new PreviewSizeSlider(layout.x(285), layout.y(226), layout.w(96), layout.h(20)));
		this.addRenderableWidget(Button.builder(Component.literal("R"), button -> {
			config.imagePreviewSize = 40;
			saveConfig();
		}).bounds(layout.x(385), layout.y(226), layout.w(20), layout.h(20)).build());

		this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose()).bounds(layout.x(15), layout.y(276), layout.w(390), layout.h(20)).build());

		refreshButtonLabels();
	}

	private void saveConfig() {
		config.save();
		refreshButtonLabels();
	}

	private void refreshButtonLabels() {
		enabledButton.setMessage(Component.literal(config.enabled ? "Enabled" : "Disabled"));
		selfPresenceButton.setMessage(Component.literal(config.announceSelfPresence ? "On" : "Off"));
		partyFeedButton.setMessage(Component.literal(config.partyAnnounce ? "On" : "Off"));
		gameMessagesButton.setMessage(Component.literal(shortGameModeLabel(config.gameDisplayMode)));
		previewSlider.syncFromConfig();
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

	private String shortGameModeLabel(BridgeConfig.GameDisplayMode mode) {
		return switch (mode) {
			case ALL -> "Shown";
			case NONE -> "Hidden";
			case REACTIONS -> "React Only";
		};
	}

	private Component linkStatusText() {
		if (config.jwt.isEmpty()) {
			return Component.literal("Not linked").withStyle(style -> style.withColor(0xAAAAAA));
		}
		if (!config.hasValidJwt()) {
			return Component.literal("Token expired - re-link").withStyle(style -> style.withColor(0xFF5555));
		}
		String name = config.linkedUsername;
		return Component.literal(name.isEmpty() ? "Linked" : "Linked as " + name).withStyle(style -> style.withColor(0x55FF55));
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		this.renderMenuBackground(g);
		pushReferencePose(g);
		layout.drawBackground(g);
		layout.drawPanel(g);
		super.render(g, scaledMouseX, scaledMouseY, delta);

		int logoWidth = layout.w(54);
		int logoHeight = logoWidth * LOGO_H / LOGO_W;
		g.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, layout.centerX() - (logoWidth / 2), layout.y(14), 0.0f, 0.0f, logoWidth, logoHeight, LOGO_W, LOGO_H, LOGO_W, LOGO_H);
		g.drawCenteredString(this.font, this.title, layout.centerX(), layout.y(12), 0xFFFFFFFF);
		g.drawCenteredString(this.font, linkStatusText(), layout.centerX(), layout.y(68), 0xFFFFFFFF);

		drawSettingLabel(g, "Bridge", 122);
		drawSettingLabel(g, "My login/logout messages", 148);
		drawSettingLabel(g, "Party feed", 174);
		drawSettingLabel(g, "Game Messages", 200);
		drawSettingLabel(g, "Image Preview Size", 226);

		String currentVer = UpdateChecker.currentVersion();
		if (currentVer == null) {
			currentVer = "Unknown";
		}
		UpdateInfo pendingUpdate = EdenModClient.instance().getPendingUpdate();
		String versionText = "v" + currentVer;
		String updateText = pendingUpdate != null ? "Update Available: " + pendingUpdate.version() : "Up to date";
		int metaRight = layout.panelX() + layout.panelWidth() - layout.w(14);
		g.drawString(this.font, versionText, metaRight - this.font.width(versionText), layout.y(14), 0xFFAAAAAA);
		g.drawString(this.font, updateText, metaRight - this.font.width(updateText), layout.y(28), pendingUpdate != null ? 0xFF55FF55 : 0xFFAAAAAA);
		popReferencePose(g);
	}

	private void drawSettingLabel(GuiGraphics g, String text, int baseY) {
		g.drawString(this.font, text, layout.x(15), layout.y(baseY + 6), 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		config.save();
		this.minecraft.setScreen(parent);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
		return super.mouseClicked(rescale(event), bl);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		return super.mouseReleased(rescale(event));
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double d, double e) {
		return super.mouseDragged(rescale(event), d / uiScale, e / uiScale);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double d, double e) {
		return super.mouseScrolled(mouseX / uiScale, mouseY / uiScale, d, e);
	}
	private final class PreviewSizeSlider extends AbstractSliderButton {
		private static final int MIN = 1;
		private static final int MAX = 100;

		private PreviewSizeSlider(int x, int y, int width, int height) {
			super(x, y, width, height, Component.empty(), 0.0d);
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
}
