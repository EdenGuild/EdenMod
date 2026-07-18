package tel.eden.mod.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;

public class EdenMenuScreen extends Screen {
	private static final int BASE_CONTENT_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_SPACING = 24;
	private static final int BUTTON_COUNT = 7;
	private static final int LOGO_WIDTH = 128;
	private static final int LOGO_GAP = 20;
	private static final int CONTENT_MARGIN = 12;
	private static final int BASE_META_MARGIN = 5;
	private static final int MIN_TOP_SAFE_MARGIN = 20;
	private static final int MIN_BOTTOM_SAFE_MARGIN = 36;
	private static final int MIN_BUTTON_WIDTH = 170;

	private static final Identifier LOGO_TEXTURE = Identifier.parse("edenmod:icon.png");
	private static final int LOGO_W = 722;
	private static final int LOGO_H = 693;

	private EdenPanelLayout layout;

	public EdenMenuScreen() {
		super(Component.literal("Eden Bridge"));
	}

	@Override
	protected void init() {
		super.init();
		int baseLogoHeight = LOGO_WIDTH * LOGO_H / LOGO_W;
		int baseStackHeight = BUTTON_HEIGHT + BUTTON_SPACING * (BUTTON_COUNT - 1);
		int baseTotalHeight = baseLogoHeight + LOGO_GAP + baseStackHeight;
		layout = EdenPanelLayout.centered(this.width, this.height, BASE_CONTENT_WIDTH + (CONTENT_MARGIN * 2), baseTotalHeight + (MIN_TOP_SAFE_MARGIN * 2));
		MenuMetrics metrics = menuMetrics();

		this.addRenderableWidget(Button.builder(Component.literal("Config"), button -> {
			this.minecraft.setScreen(BridgeConfigScreen.create(this, EdenModClient.instance()));
		}).bounds(metrics.buttonX, metrics.startY, metrics.buttonWidth, metrics.buttonHeight).build());

		this.addRenderableWidget(Button.builder(Component.literal("Create Party"), button -> {
			this.minecraft.setScreen(new PartyCreateScreen(this, EdenModClient.instance()));
		}).bounds(metrics.buttonX, metrics.startY + metrics.buttonPitch, metrics.buttonWidth, metrics.buttonHeight).build());

		this.addRenderableWidget(Button.builder(Component.literal("Manage Party"), button -> {
			String ign = EdenModClient.instance().playerName();
			tel.eden.mod.net.PartyInfo myParty = null;
			for (tel.eden.mod.net.PartyInfo p : EdenModClient.instance().knownParties()) {
				if (ign != null && p.host().equalsIgnoreCase(ign)) {
					myParty = p;
					break;
				}
			}
			if (myParty != null) {
				this.minecraft.setScreen(new PartyManageScreen(this, EdenModClient.instance(), myParty));
			} else {
				this.minecraft.player.displayClientMessage(Component.literal("You are not hosting a party!").withStyle(net.minecraft.ChatFormatting.RED), false);
			}
		}).bounds(metrics.buttonX, metrics.startY + (metrics.buttonPitch * 2), metrics.buttonWidth, metrics.buttonHeight).build());

		this.addRenderableWidget(Button.builder(Component.literal("Party List"), button -> {
			this.minecraft.setScreen(new PartyListScreen(this, EdenModClient.instance()));
		}).bounds(metrics.buttonX, metrics.startY + (metrics.buttonPitch * 3), metrics.buttonWidth, metrics.buttonHeight).build());

		this.addRenderableWidget(Button.builder(Component.literal("Aspect Payouts"), button -> {
			this.minecraft.setScreen(new AspectsPayoutScreen(this, EdenModClient.instance()));
		}).bounds(metrics.buttonX, metrics.startY + (metrics.buttonPitch * 4), metrics.buttonWidth, metrics.buttonHeight).build());

		this.addRenderableWidget(Button.builder(Component.literal("Command Aliases"), button -> {
			this.minecraft.setScreen(new CommandAliasScreen(this, EdenModClient.instance()));
		}).bounds(metrics.buttonX, metrics.startY + (metrics.buttonPitch * 5), metrics.buttonWidth, metrics.buttonHeight).build());

		this.addRenderableWidget(Button.builder(Component.literal("Command Keybinds"), button -> {
			this.minecraft.setScreen(new CommandKeybindScreen(this, EdenModClient.instance()));
		}).bounds(metrics.buttonX, metrics.startY + (metrics.buttonPitch * 6), metrics.buttonWidth, metrics.buttonHeight).build());
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		this.renderMenuBackground(guiGraphics);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		this.renderMenuBackground(guiGraphics);
		super.render(guiGraphics, mouseX, mouseY, partialTick);

		MenuMetrics metrics = menuMetrics();
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, (this.width / 2) - (metrics.logoWidth / 2), metrics.topY, 0.0f, 0.0f, metrics.logoWidth, metrics.logoHeight, LOGO_W, LOGO_H, LOGO_W, LOGO_H);

		String currentVer = tel.eden.mod.update.UpdateChecker.currentVersion();
		if (currentVer == null) {
			currentVer = "Unknown";
		}

		tel.eden.mod.update.UpdateInfo pendingUpdate = tel.eden.mod.EdenModClient.instance().getPendingUpdate();
		String updateText = pendingUpdate != null ? "Update Available: " + pendingUpdate.version() : "Up to date";

		String text1 = "v" + currentVer;
		String text2 = updateText;
		guiGraphics.drawString(this.minecraft.font, text1, this.width - this.minecraft.font.width(text1) - BASE_META_MARGIN, BASE_META_MARGIN, 0xFFAAAAAA);
		guiGraphics.drawString(this.minecraft.font, text2, this.width - this.minecraft.font.width(text2) - BASE_META_MARGIN, BASE_META_MARGIN + this.font.lineHeight + 1, pendingUpdate != null ? 0xFF55FF55 : 0xFFAAAAAA);
	}

	private int bottomSafeMargin() {
		return Math.max(MIN_BOTTOM_SAFE_MARGIN, this.height / 6);
	}

	private int topSafeMargin() {
		return Math.max(MIN_TOP_SAFE_MARGIN, this.height / 12);
	}

	private MenuMetrics menuMetrics() {
		int stackHeight = BUTTON_HEIGHT + BUTTON_SPACING * (BUTTON_COUNT - 1);
		int topPadding = topSafeMargin();
		int bottomPadding = bottomSafeMargin();
		int availableWidth = Math.max(1, this.width - (CONTENT_MARGIN * 2));
		int availableHeight = Math.max(1, this.height - topPadding - bottomPadding);
		int buttonWidth = Math.min(BASE_CONTENT_WIDTH, Math.max(MIN_BUTTON_WIDTH, availableWidth));
		int maxLogoHeight = Math.max(1, availableHeight - stackHeight - LOGO_GAP);
		int maxLogoWidthFromHeight = Math.max(1, Math.round(maxLogoHeight * (LOGO_W / (float) LOGO_H)));
		int logoWidth = Math.max(1, Math.min(LOGO_WIDTH, Math.min(maxLogoWidthFromHeight, availableWidth)));
		int logoHeight = Math.max(1, logoWidth * LOGO_H / LOGO_W);
		int clusterHeight = logoHeight + LOGO_GAP + stackHeight;
		int topY = topPadding + Math.max(0, (availableHeight - clusterHeight) / 2);
		int buttonX = (this.width - buttonWidth) / 2;
		int startY = topY + logoHeight + LOGO_GAP;
		return new MenuMetrics(topY, logoWidth, logoHeight, buttonX, buttonWidth, BUTTON_HEIGHT, BUTTON_SPACING, startY);
	}

	private static final class MenuMetrics {
		private final int topY;
		private final int logoWidth;
		private final int logoHeight;
		private final int buttonX;
		private final int buttonWidth;
		private final int buttonHeight;
		private final int buttonPitch;
		private final int startY;

		private MenuMetrics(int topY, int logoWidth, int logoHeight, int buttonX, int buttonWidth, int buttonHeight, int buttonPitch, int startY) {
			this.topY = topY;
			this.logoWidth = logoWidth;
			this.logoHeight = logoHeight;
			this.buttonX = buttonX;
			this.buttonWidth = buttonWidth;
			this.buttonHeight = buttonHeight;
			this.buttonPitch = buttonPitch;
			this.startY = startY;
		}
	}
}
