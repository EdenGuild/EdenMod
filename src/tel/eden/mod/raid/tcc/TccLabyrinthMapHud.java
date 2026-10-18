package tel.eden.mod.raid.tcc;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.hud.HudPanel;
import tel.eden.mod.hud.HudRender;
import tel.eden.mod.net.BridgeWebSocketClient;
import tel.eden.mod.party.PartyHealthTracker;
import tel.eden.mod.util.PlayerSkinResolver;

/**
 * Minimap HUD for The Canyon Colossus Room 2 Labyrinth framed with a custom ornate border.
 * Features player heads with mini HP bars, offline red-dot fallback, defeated golem "X" markers,
 * and exit reach indicators.
 */
public final class TccLabyrinthMapHud {
	public static final String PANEL = "tccLabyrinthMap";
	public static final float DEFAULT_X = 0.02f;
	public static final float DEFAULT_Y = 0.05f;
	public static final int PANEL_W = 130;
	public static final int PANEL_H = 180;

	private static final int INSET_X = 5;
	private static final int INSET_Y = 6;
	private static final int VIEWPORT_W = 120;
	private static final int VIEWPORT_H = 167;

	private static final Identifier MAP_TEXTURE = Identifier.fromNamespaceAndPath("edenmod", "textures/raid/tcc_room2_map.png");
	private static final Identifier FRAME_TEXTURE = Identifier.fromNamespaceAndPath("edenmod", "textures/raid/tcc_minimap_frame.png");
	private static final Identifier X_TEXTURE = Identifier.fromNamespaceAndPath("edenmod", "textures/raid/tcc_golem_x.png");
	private static final Identifier[] WING_TEXTURES = new Identifier[]{Identifier.fromNamespaceAndPath("edenmod", "textures/raid/tcc_wings_0.png"), Identifier.fromNamespaceAndPath("edenmod", "textures/raid/tcc_wings_1.png"), Identifier.fromNamespaceAndPath("edenmod", "textures/raid/tcc_wings_2.png"), Identifier.fromNamespaceAndPath("edenmod", "textures/raid/tcc_wings_3.png")};

	private static final int COLOR_PROVI_GREEN = 0xFF00E817;
	private static final int COLOR_AMBER = 0xFFF59E0B;
	private static final int COLOR_CRIMSON = 0xFFEF4444;
	private static final int COLOR_GOLD = 0xFFFBBF24;

	private TccLabyrinthMapHud() {
	}

	public static void render(BridgeConfig config, GuiGraphics graphics) {
		if (config == null || !config.tccLabyrinthMap) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null || mc.level == null || mc.options.hideGui) {
			return;
		}

		if (!TccLabyrinthTracker.isInRoom2()) {
			return;
		}

		HudPanel panel = new HudPanel();
		panel.add(TccLabyrinthMapHud::drawMinimap);

		HudRender.draw(config, PANEL, DEFAULT_X, DEFAULT_Y, PANEL_W, PANEL_H, panel, graphics);
	}

	private static void drawMinimap(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}

		// 1. Dark viewport background
		graphics.fill(INSET_X, INSET_Y, INSET_X + VIEWPORT_W, INSET_Y + VIEWPORT_H, 0xEE0B0E14);

		// 2. Base map texture (556 x 775 source)
		graphics.blit(RenderPipelines.GUI_TEXTURED, MAP_TEXTURE, INSET_X, INSET_Y, 0.0f, 0.0f, VIEWPORT_W, VIEWPORT_H, 556, 775, 556, 775);

		// 3. Defeated Golems (Red 'X')
		for (String golemId : TccLabyrinthTracker.getDefeatedGolems()) {
			TccLabyrinthTracker.Golem g = TccLabyrinthTracker.Golem.fromId(golemId);
			if (g != null) {
				float[] screen = projectMapPixel(g.mapPixelX, g.mapPixelY);
				drawGolemX(graphics, Math.round(screen[0]), Math.round(screen[1]));
			}
		}

		// 4. Confirmed Exit Marker (white circle drawn inside of purple circle)
		TccLabyrinthTracker.ExitPoint confirmed = TccLabyrinthTracker.getConfirmedExit();
		if (confirmed != null) {
			float[] screen = projectMapPixel(confirmed.mapPixelX, confirmed.mapPixelY);
			drawConfirmedExitCircle(graphics, Math.round(screen[0]), Math.round(screen[1]));
		}

		// 5. Exit Reached Banner
		if (TccLabyrinthTracker.isExitReached()) {
			int bannerY = INSET_Y + VIEWPORT_H - 12;
			graphics.fill(INSET_X, bannerY, INSET_X + VIEWPORT_W, INSET_Y + VIEWPORT_H, 0xDD006622);
			graphics.pose().pushMatrix();
			graphics.pose().translate(INSET_X + (VIEWPORT_W / 2.0f), bannerY + 2);
			graphics.pose().scale(0.7f, 0.7f);
			String exitText = "EXIT REACHED";
			int tw = mc.font.width(exitText);
			graphics.drawString(mc.font, exitText, -tw / 2, 0, 0xFFFFFFFF, true);
			graphics.pose().popMatrix();
		}

		// 5. Player markers
		EdenModClient mod = EdenModClient.instance();
		BridgeWebSocketClient socket = mod != null ? mod.socket() : null;
		boolean connected = socket != null && socket.isConnected();

		List<TccLabyrinthTracker.PlayerEntry> players = new ArrayList<>();
		// Local player entry
		double lx = mc.player.getX();
		double ly = mc.player.getY();
		double lz = mc.player.getZ();
		float lyaw = mc.player.getYRot();
		boolean lWings = mc.player.getUUID().equals(TccLabyrinthTracker.getWingsPlayerUUID());
		players.add(new TccLabyrinthTracker.PlayerEntry(mc.player.getGameProfile().name(), mc.player.getUUID(), lx, ly, lz, lyaw, lWings, true));

		// Peer players if connected
		if (connected) {
			players.addAll(TccLabyrinthTracker.getPeers());
		}

		for (TccLabyrinthTracker.PlayerEntry p : players) {
			float[] mapPix = TccLabyrinthTracker.worldToMapPixels(p.x(), p.z());
			float[] screen = projectMapPixel(mapPix[0], mapPix[1]);
			int sx = Math.max(INSET_X + 6, Math.min(INSET_X + VIEWPORT_W - 6, Math.round(screen[0])));
			int sy = Math.max(INSET_Y + 6, Math.min(INSET_Y + VIEWPORT_H - 6, Math.round(screen[1])));

			drawPlayer(graphics, mc, p, sx, sy, !connected && p.isLocal());
		}

		// 6. Minimap Ornate Frame overlay (144 x 143 visible frame cropped from 164 x 155 source at u=13, v=6)
		graphics.blit(RenderPipelines.GUI_TEXTURED, FRAME_TEXTURE, 0, 0, 13.0f, 6.0f, PANEL_W, PANEL_H, 144, 143, 164, 155);
	}

	private static float[] projectMapPixel(float mapPixelX, float mapPixelY) {
		float sx = INSET_X + (mapPixelX / 556.0f) * VIEWPORT_W;
		float sy = INSET_Y + (mapPixelY / 775.0f) * VIEWPORT_H;
		return new float[]{sx, sy};
	}

	private static void drawGolemX(GuiGraphics graphics, int cx, int cy) {
		int sz = 10;
		graphics.blit(RenderPipelines.GUI_TEXTURED, X_TEXTURE, cx - sz / 2, cy - sz / 2, 0.0f, 0.0f, sz, sz, 424, 424, 424, 424);
	}

	private static void drawConfirmedExitCircle(GuiGraphics graphics, int cx, int cy) {
		graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);
	}

	private static void drawPlayer(GuiGraphics graphics, Minecraft mc, TccLabyrinthTracker.PlayerEntry p, int sx, int sy, boolean fallbackRedDot) {
		int headSize = 10;
		int headX = sx - headSize / 2;
		int headY = sy - headSize / 2;

		// 0. Fluttering wings for wings player (rendered behind head starting halfway down model)
		if (p.isWings()) {
			long cycleStep = (System.currentTimeMillis() / 90L) % 6L;
			int frameIdx;
			if (cycleStep == 0)
				frameIdx = 0;
			else if (cycleStep == 1)
				frameIdx = 1;
			else if (cycleStep == 2)
				frameIdx = 2;
			else if (cycleStep == 3)
				frameIdx = 3;
			else if (cycleStep == 4)
				frameIdx = 2;
			else
				frameIdx = 1;

			Identifier wingTex = WING_TEXTURES[frameIdx];
			int wingSize = 30;
			int wingX = sx - wingSize / 2;
			int wingY = sy - 10;
			graphics.blit(RenderPipelines.GUI_TEXTURED, wingTex, wingX, wingY, 0.0f, 0.0f, wingSize, wingSize, 192, 192, 192, 192);
		}

		// A. Mini HP Bar (Above head / dot)
		int barW = 14;
		int barH = 2;
		int barX = sx - barW / 2;
		int barY = headY - 4;

		PartyHealthTracker.PlayerHealthData health = PartyHealthTracker.getHealth(p.uuid());
		float percent;
		boolean overMax = false;
		float overPercent = 0.0f;

		if (health != null) {
			percent = health.percent();
			overMax = health.overMax();
			overPercent = health.overPercent();
		} else if (p.isLocal() && mc.player != null) {
			float cur = mc.player.getHealth();
			float max = Math.max(1.0f, mc.player.getMaxHealth());
			percent = cur / max;
		} else {
			percent = 1.0f;
		}
		percent = Math.max(0.0f, Math.min(1.0f, percent));

		// HP Bar Underlay
		graphics.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xDD111111);
		int hpColor = percent > 0.5f ? COLOR_PROVI_GREEN : (percent > 0.25f ? COLOR_AMBER : COLOR_CRIMSON);
		int fillW = Math.round(barW * percent);
		if (fillW > 0) {
			graphics.fill(barX, barY, barX + fillW, barY + barH, hpColor);
		}
		if (overMax && overPercent > 0.0f) {
			int overFillW = Math.round(barW * Math.min(1.0f, overPercent));
			if (overFillW > 0) {
				graphics.fill(barX, barY, barX + overFillW, barY + barH, COLOR_GOLD);
			}
		}

		// B. Player marker: Red dot (fallback) or Player Skin Head
		if (fallbackRedDot) {
			int r = 3;
			graphics.fill(sx - r, sy - r, sx + r + 1, sy + r + 1, 0xFFFF2222);
			graphics.renderOutline(sx - r - 1, sy - r - 1, (r + 1) * 2, (r + 1) * 2, 0xFFFFFFFF);
		} else {
			PlayerSkin skin = PlayerSkinResolver.skinFor(mc, p.uuid(), p.name());
			PlayerFaceRenderer.draw(graphics, skin, headX, headY, headSize);
			// Border around head
			graphics.renderOutline(headX - 1, headY - 1, headSize + 2, headSize + 2, p.isWings() ? 0xFF00E5FF : 0xAAFFFFFF);
		}

		// C. Player Name (Below head / dot)
		graphics.pose().pushMatrix();
		graphics.pose().translate(sx, headY + headSize + 2);
		graphics.pose().scale(0.60f, 0.60f);
		String nameStr = p.name();
		int tw = mc.font.width(nameStr);
		int nameColor = p.isWings() ? 0xFF00E5FF : (fallbackRedDot ? 0xFFFF8888 : 0xFFFFFFFF);
		graphics.drawString(mc.font, nameStr, -tw / 2, 0, nameColor, true);
		graphics.pose().popMatrix();
	}
}
