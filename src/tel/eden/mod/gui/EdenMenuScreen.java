package tel.eden.mod.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.lwjgl.glfw.GLFW;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;

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
	public static final float BABY_PLAYER_SCALE = 0.55f;
	public static final float BABY_HEAD_SCALE = 1.5f;
	private static final int[] KONAMI_CODE = {GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_A};
	private static final SoundEvent UI_BUTTON_CLICK_SOUND = SoundEvent.createVariableRangeEvent(Identifier.parse("edenmod:ui.button.click"));
	private static final SoundEvent UI_TOAST_CHALLENGE_COMPLETE_SOUND = SoundEvent.createVariableRangeEvent(Identifier.parse("edenmod:ui.toast.challenge_complete"));
	private static final Identifier LOGO_TEXTURE = Identifier.parse("edenmod:icon.png");
	private static final int LOGO_W = 722;
	private static final int LOGO_H = 693;

	private EdenPanelLayout layout;
	private final int[] konamiInput = new int[KONAMI_CODE.length];
	private int konamiInputLength;

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

		this.addRenderableWidget(Button.builder(Component.literal("Payouts"), button -> {
			this.minecraft.setScreen(new PayoutScreen(this, EdenModClient.instance()));
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

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (!isKonamiKey(key)) {
			konamiInputLength = 0;
			return super.keyPressed(event);
		}

		if (konamiInputLength == konamiInput.length) {
			System.arraycopy(konamiInput, 1, konamiInput, 0, konamiInput.length - 1);
			konamiInputLength--;
		}
		konamiInput[konamiInputLength++] = key;
		int progress = konamiProgress();
		if (progress > 0) {
			playKonamiTick(progress);
		}
		if (progress == KONAMI_CODE.length) {
			BridgeConfig config = EdenModClient.instance().config();
			config.unlockSecret(BridgeConfig.SECRET_BABY_PLAYERS);
			config.babyPlayers = !config.babyPlayers;
			config.save();
			konamiInputLength = 0;
		}
		return true;
	}

	public static boolean isBabyModeEnabled() {
		return EdenModClient.instance().config().babyPlayers;
	}

	public static void setBabyModeEnabled(boolean enabled) {
		EdenModClient.instance().config().babyPlayers = enabled;
	}

	private void playKonamiTick(int progress) {
		if (progress == KONAMI_CODE.length) {
			this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(UI_TOAST_CHALLENGE_COMPLETE_SOUND, 1.0f, 0.8f));
			return;
		}
		float pitch = 0.75f + (progress * 0.06f);
		this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(UI_BUTTON_CLICK_SOUND, pitch, 0.25f));
	}

	private int konamiProgress() {
		for (int length = Math.min(konamiInputLength, KONAMI_CODE.length); length > 0; length--) {
			int start = konamiInputLength - length;
			boolean matches = true;
			for (int i = 0; i < length; i++) {
				if (!matchesKonamiPosition(i, konamiInput[start + i])) {
					matches = false;
					break;
				}
			}
			if (matches) {
				return length;
			}
		}
		return 0;
	}

	private static boolean isKonamiKey(int key) {
		return key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_D || key == GLFW.GLFW_KEY_B;
	}

	private static boolean matchesKonamiPosition(int position, int key) {
		int expected = KONAMI_CODE[position];
		if (key == expected) {
			return true;
		}
		return switch (expected) {
			case GLFW.GLFW_KEY_UP -> key == GLFW.GLFW_KEY_W;
			case GLFW.GLFW_KEY_DOWN -> key == GLFW.GLFW_KEY_S;
			case GLFW.GLFW_KEY_LEFT -> key == GLFW.GLFW_KEY_A;
			case GLFW.GLFW_KEY_RIGHT -> key == GLFW.GLFW_KEY_D;
			default -> false;
		};
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
