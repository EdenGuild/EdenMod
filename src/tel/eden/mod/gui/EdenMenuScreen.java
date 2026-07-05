package tel.eden.mod.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;

public class EdenMenuScreen extends Screen {

	private static final Identifier LOGO_TEXTURE = Identifier.parse("edenmod:icon.png");
	private static final int LOGO_W = 722;
	private static final int LOGO_H = 693;

	public EdenMenuScreen() {
		super(Component.literal("Eden Bridge"));
	}

	@Override
	protected void init() {
		super.init();

		int buttonWidth = 200;
		int buttonHeight = 20;
		int startY = this.height / 2 - 10;
		int spacing = 24;

		this.addRenderableWidget(Button.builder(Component.literal("Config"), button -> {
			this.minecraft.setScreen(BridgeConfigScreen.create(this, EdenModClient.instance()));
		}).bounds(this.width / 2 - buttonWidth / 2, startY, buttonWidth, buttonHeight).build());

		this.addRenderableWidget(Button.builder(Component.literal("Create Party"), button -> {
			this.minecraft.setScreen(new PartyCreateScreen(this, EdenModClient.instance()));
		}).bounds(this.width / 2 - buttonWidth / 2, startY + spacing, buttonWidth, buttonHeight).build());

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
				this.minecraft.player.displayClientMessage(Component.literal("You are not hosting a party!").withStyle(net.minecraft.ChatFormatting.RED), true);
			}
		}).bounds(this.width / 2 - buttonWidth / 2, startY + spacing * 2, buttonWidth, buttonHeight).build());

		this.addRenderableWidget(Button.builder(Component.literal("Party List"), button -> {
			this.minecraft.setScreen(new PartyListScreen(this, EdenModClient.instance()));
		}).bounds(this.width / 2 - buttonWidth / 2, startY + spacing * 3, buttonWidth, buttonHeight).build());
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		this.renderMenuBackground(guiGraphics);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.render(guiGraphics, mouseX, mouseY, partialTick);
		int logoW = 128; // Decent size logo
		int logoH = logoW * LOGO_H / LOGO_W;
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, this.width / 2 - logoW / 2, this.height / 2 - 10 - logoH - 20, 0.0f, 0.0f, logoW, logoH, LOGO_W, LOGO_H, LOGO_W, LOGO_H);

		String currentVer = tel.eden.mod.update.UpdateChecker.currentVersion();
		if (currentVer == null)
			currentVer = "Unknown";

		tel.eden.mod.update.UpdateInfo pendingUpdate = tel.eden.mod.EdenModClient.instance().getPendingUpdate();
		String updateText = (pendingUpdate != null) ? "Update Available: " + pendingUpdate.version() : "Up to date";

		String text1 = "v" + currentVer;
		String text2 = updateText;

		int textX1 = this.width - this.minecraft.font.width(text1) - 5;
		int textX2 = this.width - this.minecraft.font.width(text2) - 5;

		guiGraphics.drawString(this.minecraft.font, text1, textX1, 5, 0xAAAAAA);
		guiGraphics.drawString(this.minecraft.font, text2, textX2, 20, pendingUpdate != null ? 0x55FF55 : 0xAAAAAA);
	}
}
