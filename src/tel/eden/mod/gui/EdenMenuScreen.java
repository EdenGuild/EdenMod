package tel.eden.mod.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import tel.eden.mod.EdenModClient;

public class EdenMenuScreen extends EdenReferenceScreen {
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_SPACING = 24;
	private static final int BUTTON_COUNT = 4;
	private static final int LOGO_WIDTH = 128;
	private static final int LOGO_GAP = 20;
	private static final int CONTENT_MARGIN = 12;

	private static final Identifier LOGO_TEXTURE = Identifier.parse("edenmod:icon.png");
	private static final int LOGO_W = 722;
	private static final int LOGO_H = 693;

	public EdenMenuScreen() {
		super(Component.literal("Eden Bridge"));
	}

	@Override
	protected void init() {
		super.init();
		updateReferenceSpace();

		int logoHeight = LOGO_WIDTH * LOGO_H / LOGO_W;
		int stackHeight = BUTTON_HEIGHT + BUTTON_SPACING * (BUTTON_COUNT - 1);
		int totalHeight = logoHeight + LOGO_GAP + stackHeight;
		int topY = Math.max(CONTENT_MARGIN, (virtualHeight - totalHeight) / 2);
		int startY = topY + logoHeight + LOGO_GAP;
		int buttonX = virtualWidth / 2 - BUTTON_WIDTH / 2;

		this.addRenderableWidget(Button.builder(Component.literal("Config"), button -> {
			this.minecraft.setScreen(BridgeConfigScreen.create(this, EdenModClient.instance()));
		}).bounds(buttonX, startY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		this.addRenderableWidget(Button.builder(Component.literal("Create Party"), button -> {
			this.minecraft.setScreen(new PartyCreateScreen(this, EdenModClient.instance()));
		}).bounds(buttonX, startY + BUTTON_SPACING, BUTTON_WIDTH, BUTTON_HEIGHT).build());

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
		}).bounds(buttonX, startY + BUTTON_SPACING * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build());

		this.addRenderableWidget(Button.builder(Component.literal("Party List"), button -> {
			this.minecraft.setScreen(new PartyListScreen(this, EdenModClient.instance()));
		}).bounds(buttonX, startY + BUTTON_SPACING * 3, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	@Override
	public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		this.renderMenuBackground(guiGraphics);
	}

	@Override
	public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		this.renderMenuBackground(guiGraphics);

		int scaledMouseX = scaledMouseX(mouseX);
		int scaledMouseY = scaledMouseY(mouseY);

		pushReferencePose(guiGraphics);
		super.render(guiGraphics, scaledMouseX, scaledMouseY, partialTick);

		int logoHeight = LOGO_WIDTH * LOGO_H / LOGO_W;
		int stackHeight = BUTTON_HEIGHT + BUTTON_SPACING * (BUTTON_COUNT - 1);
		int totalHeight = logoHeight + LOGO_GAP + stackHeight;
		int topY = Math.max(CONTENT_MARGIN, (virtualHeight - totalHeight) / 2);
		guiGraphics.blit(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, virtualWidth / 2 - LOGO_WIDTH / 2, topY, 0.0f, 0.0f, LOGO_WIDTH, logoHeight, LOGO_W, LOGO_H, LOGO_W, LOGO_H);

		String currentVer = tel.eden.mod.update.UpdateChecker.currentVersion();
		if (currentVer == null) {
			currentVer = "Unknown";
		}

		tel.eden.mod.update.UpdateInfo pendingUpdate = tel.eden.mod.EdenModClient.instance().getPendingUpdate();
		String updateText = pendingUpdate != null ? "Update Available: " + pendingUpdate.version() : "Up to date";

		String text1 = "v" + currentVer;
		String text2 = updateText;
		guiGraphics.drawString(this.minecraft.font, text1, virtualWidth - this.minecraft.font.width(text1) - 5, 5, 0xFFAAAAAA);
		guiGraphics.drawString(this.minecraft.font, text2, virtualWidth - this.minecraft.font.width(text2) - 5, 20, pendingUpdate != null ? 0xFF55FF55 : 0xFFAAAAAA);
		popReferencePose(guiGraphics);
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
}
