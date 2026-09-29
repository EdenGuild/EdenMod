package tel.eden.mod.party;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.render.EdenAvatarState;

/**
 * Renders an Eden-themed overhead health bar above party members' nametags.
 *
 * <p>Theme elements:
 * <ul>
 *   <li>Obsidian slate border with dark crimson deficit underlay</li>
 *   <li>Dynamic health progression: Emerald Green (>50%) -> Amber (>25%) -> Crimson (<=25%)</li>
 *   <li>Overheal/overMax renders in radiant Eden Gold</li>
 *   <li>Party slot pip on the left border matching the player's party outline color</li>
 * </ul>
 */
public final class PartyHealthBarRenderer {
	private static final float NAMETAG_SCALE = 0.025f;
	private static final float BAR_WIDTH = 38.0f;
	private static final float BAR_HEIGHT = 4.0f;
	private static final float BORDER = 1.0f;
	private static final int FULL_BRIGHT = 0xF000F0;

	private static final int BORDER_COLOR = 0xFF0A0F14;
	private static final int BG_COLOR = 0xFF141A22;
	private static final int DEFICIT_COLOR = 0xFF2A1215;
	private static final int COLOR_EMERALD = 0xFF10B981;
	private static final int COLOR_AMBER = 0xFFF59E0B;
	private static final int COLOR_CRIMSON = 0xFFEF4444;
	private static final int COLOR_GOLD = 0xFFFBBF24;
	private static final int DIVIDER_COLOR = 0x66000000;
	private static final boolean WYNNTILS_LOADED = FabricLoader.getInstance().isModLoaded("wynntils");

	private PartyHealthBarRenderer() {
	}

	public static void renderIfVisible(AvatarRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraRenderState) {
		EdenModClient client = EdenModClient.instance();
		BridgeConfig config = client != null ? client.config() : null;
		if (config != null && !config.partyHealthBarEnabled) {
			return;
		}

		if (!(state instanceof EdenAvatarState ext)) {
			return;
		}

		UUID uuid = ext.edenmod$getPlayerUuid();
		if (uuid == null) {
			return;
		}

		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && uuid.equals(mc.player.getUUID())) {
			return;
		}

		PartyHealthTracker.PlayerHealthData health = PartyHealthTracker.getHealth(uuid);
		if (health == null) {
			return;
		}

		Vec3 attachment = state.nameTagAttachment != null ? state.nameTagAttachment : new Vec3(0, 2.0, 0);

		poseStack.pushPose();
		float scaleFactor = (config != null ? Math.max(50, Math.min(200, config.partyHealthBarScale)) : 100) / 100.0f;
		float effectiveScale = NAMETAG_SCALE * scaleFactor;
		float yOffset = computeWorldYOffset(state);
		poseStack.translate(attachment.x, attachment.y + yOffset, attachment.z);
		poseStack.mulPose(cameraRenderState.orientation);
		poseStack.scale(effectiveScale, -effectiveScale, effectiveScale);

		submitEdenBar(submitNodeCollector, poseStack, health, config);
		poseStack.popPose();
	}

	private static float computeWorldYOffset(AvatarRenderState state) {
		int lines = 0;
		if (state.nameTag != null) {
			lines++;
		}
		if (state.scoreText != null) {
			lines++;
		}
		float pixelOffset = (Math.max(1, lines) * 10.0f) + 14.0f;
		if (state.nameTag == null) {
			// When nametag is hidden by Wynncraft (e.g. housing holograms), add clearance for title displays
			pixelOffset += 12.0f;
		}
		if (state.showExtraEars) {
			pixelOffset += 10.0f;
		}
		if (WYNNTILS_LOADED) {
			pixelOffset += 8.0f;
		}
		return pixelOffset * NAMETAG_SCALE;
	}

	private static void submitEdenBar(SubmitNodeCollector collector, PoseStack poseStack, PartyHealthTracker.PlayerHealthData health, BridgeConfig config) {
		float rawPercent = health.percent();
		float percent = Float.isNaN(rawPercent) ? 1.0f : Math.max(0.0f, Math.min(1.0f, rawPercent));
		float fillWidth = Math.round(BAR_WIDTH * percent);
		float x = -BAR_WIDTH / 2.0f;
		float y = 0.0f;

		int fillColor;
		if (health.overMax()) {
			fillColor = COLOR_GOLD;
		} else if (percent > 0.50f) {
			fillColor = COLOR_EMERALD;
		} else if (percent > 0.25f) {
			fillColor = COLOR_AMBER;
		} else {
			fillColor = COLOR_CRIMSON;
		}

		List<Integer> palette = (config != null && config.partyColors != null && config.partyColors.size() == 10) ? config.partyColors : PartyHighlightManager.DEFAULT_PALETTE;
		int slotIndex = health.partySlot();
		int slotColor = (slotIndex >= 0 && slotIndex < palette.size()) ? palette.get(slotIndex) | 0xFF000000 : COLOR_EMERALD;

		collector.submitCustomGeometry(poseStack, RenderTypes.textBackground(), (pose, vertices) -> {
			// 1. Obsidian Outer Border
			drawQuad(vertices, pose, FULL_BRIGHT, x - BORDER, y - BORDER, BAR_WIDTH + (BORDER * 2.0f), BORDER, 0.01f, BORDER_COLOR);
			drawQuad(vertices, pose, FULL_BRIGHT, x - BORDER, y + BAR_HEIGHT, BAR_WIDTH + (BORDER * 2.0f), BORDER, 0.01f, BORDER_COLOR);
			drawQuad(vertices, pose, FULL_BRIGHT, x - BORDER, y, BORDER, BAR_HEIGHT, 0.01f, BORDER_COLOR);
			drawQuad(vertices, pose, FULL_BRIGHT, x + BAR_WIDTH, y, BORDER, BAR_HEIGHT, 0.01f, BORDER_COLOR);

			// 2. Party Slot Gem/Pip on the left edge (aligned with bar height)
			float pipSize = BAR_HEIGHT;
			float pipX = x - BORDER - pipSize - 2.0f;
			float pipY = y;

			// Pip obsidian border edges
			drawQuad(vertices, pose, FULL_BRIGHT, pipX - BORDER, pipY - BORDER, pipSize + (BORDER * 2.0f), BORDER, 0.01f, BORDER_COLOR);
			drawQuad(vertices, pose, FULL_BRIGHT, pipX - BORDER, pipY + pipSize, pipSize + (BORDER * 2.0f), BORDER, 0.01f, BORDER_COLOR);
			drawQuad(vertices, pose, FULL_BRIGHT, pipX - BORDER, pipY, BORDER, pipSize, 0.01f, BORDER_COLOR);
			drawQuad(vertices, pose, FULL_BRIGHT, pipX + pipSize, pipY, BORDER, pipSize, 0.01f, BORDER_COLOR);

			// Pip slot color fill
			drawQuad(vertices, pose, FULL_BRIGHT, pipX, pipY, pipSize, pipSize, 0.002f, slotColor);

			// 3. Dark Deficit Background (missing HP)
			if (fillWidth < BAR_WIDTH) {
				drawQuad(vertices, pose, FULL_BRIGHT, x + fillWidth, y, BAR_WIDTH - fillWidth, BAR_HEIGHT, 0.001f, DEFICIT_COLOR);
			}

			// 4. Health Fill
			if (fillWidth > 0.0f) {
				drawQuad(vertices, pose, FULL_BRIGHT, x, y, fillWidth, BAR_HEIGHT, 0.002f, fillColor);
			}

			// 5. Tactical Quarter Dividers (25%, 50%, 75%)
			for (float mark = 0.25f; mark < 1.0f; mark += 0.25f) {
				float markX = x + (BAR_WIDTH * mark);
				drawQuad(vertices, pose, FULL_BRIGHT, markX, y, 0.5f, BAR_HEIGHT, 0.003f, DIVIDER_COLOR);
			}
		});
	}

	private static void drawQuad(VertexConsumer vertices, PoseStack.Pose pose, int light, float x, float y, float width, float height, float z, int color) {
		vertices.addVertex(pose, x, y, z).setLight(light).setColor(color);
		vertices.addVertex(pose, x, y + height, z).setLight(light).setColor(color);
		vertices.addVertex(pose, x + width, y + height, z).setLight(light).setColor(color);
		vertices.addVertex(pose, x + width, y, z).setLight(light).setColor(color);
	}
}
