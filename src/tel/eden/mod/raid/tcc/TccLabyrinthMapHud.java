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
import tel.eden.mod.hud.HudLayout;
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
	public static final float DEFAULT_X = 0.0f;
	public static final float DEFAULT_Y = 0.0f;
	public static final int PANEL_W = 130;
	public static final int PANEL_H = 180;

	public static final String ALERT_PANEL = "tccReportTitle";
	public static final float ALERT_DEFAULT_X = 0.35f;
	public static final float ALERT_DEFAULT_Y = 0.44f;
	public static final int ALERT_PANEL_W = 220;
	public static final int ALERT_PANEL_H = 20;

	private static volatile String alertText = null;
	private static volatile int alertColor = 0xFFFFFFFF;
	private static volatile long alertStartMs = 0L;
	private static volatile long alertExpiresAtMs = 0L;

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

		if (TccLabyrinthTracker.isInRoom2()) {
			HudPanel panel = new HudPanel();
			panel.add(TccLabyrinthMapHud::drawMinimap);
			HudRender.draw(config, PANEL, DEFAULT_X, DEFAULT_Y, PANEL_W, PANEL_H, panel, graphics);
		}

		renderReportAlert(config, graphics);
	}

	public static void triggerAlert(String text, int colorRgb) {
		alertText = text;
		alertColor = colorRgb;
		long now = System.currentTimeMillis();
		alertStartMs = now;
		alertExpiresAtMs = now + 5000L;
	}

	public static void resetAlert() {
		alertText = null;
		alertExpiresAtMs = 0L;
	}

	public static String getAlertText() {
		return alertText;
	}

	private static void renderReportAlert(BridgeConfig config, GuiGraphics graphics) {
		if (alertText == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now >= alertExpiresAtMs) {
			alertText = null;
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			return;
		}

		long remaining = alertExpiresAtMs - now;
		float alphaFactor = 1.0f;
		if (remaining < 1000L) {
			alphaFactor = remaining / 1000.0f;
		} else if (now - alertStartMs < 300L) {
			alphaFactor = (now - alertStartMs) / 300.0f;
		}
		int alpha = Math.max(10, Math.min(255, (int) (alphaFactor * 255)));

		int baseTextW = mc.font.width(alertText);
		int screenW = mc.getWindow().getGuiScaledWidth();
		float hudScale = HudLayout.scale(config, ALERT_PANEL);

		// Default title font scale: 1.25f, clamped so it never goes off screen
		float fontScale = 1.25f;
		float maxPanelW = (screenW - 12) / Math.max(0.1f, hudScale);
		if ((baseTextW * fontScale + 16) > maxPanelW) {
			fontScale = Math.max(0.6f, (maxPanelW - 16) / Math.max(1, baseTextW));
		}

		int textW = Math.round(baseTextW * fontScale);
		int textH = Math.round(mc.font.lineHeight * fontScale);
		int panelW = textW + 16;
		int panelH = textH + 8;

		int bgAlpha = (int) (alpha * 0.75f);
		int bgColor = (bgAlpha << 24) | 0x0C1017;
		int borderColor = ((alpha * 2 / 3) << 24) | (alertColor & 0x00FFFFFF);
		int textColor = (alpha << 24) | (alertColor & 0x00FFFFFF);

		HudPanel panel = new HudPanel();
		final float finalFontScale = fontScale;
		final String textToDraw = alertText;
		panel.add(g -> {
			g.fill(0, 0, panelW, panelH, bgColor);
			g.renderOutline(0, 0, panelW, panelH, borderColor);
			g.pose().pushMatrix();
			g.pose().translate(8.0f, (panelH - textH) / 2.0f);
			g.pose().scale(finalFontScale, finalFontScale);
			g.drawString(mc.font, textToDraw, 0, 0, textColor, true);
			g.pose().popMatrix();
		});

		float defX = (screenW - panelW) / (2.0f * screenW);
		float defY = ALERT_DEFAULT_Y;
		HudRender.draw(config, ALERT_PANEL, defX, defY, panelW, panelH, panel, graphics);
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

		// 4. Exit Reached Banner
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

		// 6. Confirmed Exit Marker (white pulsating circle rendered above player skins and hp bars)
		TccLabyrinthTracker.ExitPoint confirmed = TccLabyrinthTracker.getConfirmedExit();
		if (confirmed != null) {
			float[] screen = projectMapPixel(confirmed.mapPixelX, confirmed.mapPixelY);
			drawConfirmedExitCircle(graphics, Math.round(screen[0]), Math.round(screen[1]));
		}

		// 7. Minimap Ornate Frame overlay (144 x 143 visible frame cropped from 164 x 155 source at u=13, v=6)
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
		long time = System.currentTimeMillis();
		double pulse = (Math.sin(time / 200.0) + 1.0) * 0.5;
		float radius = 9.0f + (float) (pulse * 4.0);
		int alpha = (int) (180 + pulse * 75);
		int color = (alpha << 24) | 0x00FFFFFF;
		int shadowColor = ((alpha / 3) << 24) | 0x000000;

		int rCeil = (int) Math.ceil(radius + 2.5f);
		double rInnerSq = (radius - 1.3) * (radius - 1.3);
		double rOuterSq = (radius + 1.3) * (radius + 1.3);
		double rShadowInnerSq = (radius - 2.2) * (radius - 2.2);
		double rShadowOuterSq = (radius + 2.2) * (radius + 2.2);

		for (int dx = -rCeil; dx <= rCeil; dx++) {
			int dxSq = dx * dx;
			for (int dy = -rCeil; dy <= rCeil; dy++) {
				int distSq = dxSq + dy * dy;
				int px = cx + dx;
				int py = cy + dy;
				if (px >= INSET_X && px < INSET_X + VIEWPORT_W && py >= INSET_Y && py < INSET_Y + VIEWPORT_H) {
					if (distSq >= rInnerSq && distSq <= rOuterSq) {
						graphics.fill(px, py, px + 1, py + 1, color);
					} else if ((distSq >= rShadowInnerSq && distSq < rInnerSq) || (distSq > rOuterSq && distSq <= rShadowOuterSq)) {
						graphics.fill(px, py, px + 1, py + 1, shadowColor);
					}
				}
			}
		}
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
