package tel.eden.mod.party;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.render.EdenAvatarState;

/**
 * Renders an overhead health bar above party members' nametags.
 *
 * <p>Features:
 * <ul>
 *   <li>Trapezoidal styled bar with sloped sides and dark deficit underlay</li>
 *   <li>Outer border matches the player's party position outline color</li>
 *   <li>Inner bezel matches a darker shade of the party position color</li>
 *   <li>Smooth health bar gliding animation when HP changes</li>
 *   <li>Dynamic health fill progression: Vibrant Green (>50%) -> Amber (>25%) -> Crimson (<=25%)</li>
 * </ul>
 */
public final class PartyHealthBarRenderer {
	private static final float NAMETAG_SCALE = 0.025f;
	private static final float BAR_WIDTH = 40.0f;
	private static final float BAR_HEIGHT = 4.375f;
	private static final int FULL_BRIGHT = 0xF000F0;

	private static final int COLOR_PROVI_GREEN = 0xFF00E817;
	private static final int COLOR_AMBER = 0xFFF59E0B;
	private static final int COLOR_CRIMSON = 0xFFEF4444;
	private static final int COLOR_GOLD = 0xFFFBBF24;
	private static final boolean WYNNTILS_LOADED = FabricLoader.getInstance().isModLoaded("wynntils");
	private static final float DISTANCE_SCALE_START = 20.0f;
	private static final float DISTANCE_SCALE_PER_BLOCK = 0.025f;

	private static final Identifier BAR_TEXTURE = Identifier.fromNamespaceAndPath("edenmod", "textures/gui/healthbars/party_bar.png");

	private static final class SmoothHealthState {
		float displayedPercent = 1.0f;
		float displayedOverPercent = 0.0f;
		long lastUpdateMs = 0L;
		boolean initialized = false;
	}

	private static final Map<UUID, SmoothHealthState> SMOOTH_STATES = new ConcurrentHashMap<>();

	private PartyHealthBarRenderer() {
	}

	public static void reset() {
		SMOOTH_STATES.clear();
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
			if (config == null || !config.partyHealthBarShowSelf) {
				return;
			}
		}

		PartyHealthTracker.PlayerHealthData health = PartyHealthTracker.getHealth(uuid);
		if (health == null) {
			return;
		}

		Vec3 attachment = state.nameTagAttachment != null ? state.nameTagAttachment : new Vec3(0, 2.0, 0);

		poseStack.pushPose();
		float scaleFactor = (config != null ? Math.max(50, Math.min(200, config.partyHealthBarScale)) : 100) / 100.0f;
		float distanceMultiplier = config != null ? computeDistanceScale(state.distanceToCameraSq, config.partyHealthBarDistanceScale) : 1.0f;
		float effectiveScale = NAMETAG_SCALE * scaleFactor * distanceMultiplier;
		float yOffset = computeWorldYOffset(state);
		poseStack.translate(attachment.x, attachment.y + yOffset, attachment.z);
		poseStack.mulPose(cameraRenderState.orientation);
		poseStack.scale(effectiveScale, -effectiveScale, effectiveScale);

		submitEdenBar(submitNodeCollector, poseStack, uuid, health, config);
		poseStack.popPose();
	}

	static float computeDistanceScale(double distanceToCameraSq, int distanceScalePercent) {
		if (distanceScalePercent <= 0 || distanceToCameraSq <= (DISTANCE_SCALE_START * DISTANCE_SCALE_START)) {
			return 1.0f;
		}
		float dist = (float) Math.sqrt(distanceToCameraSq);
		float intensity = Math.max(0, Math.min(200, distanceScalePercent)) / 100.0f;
		return 1.0f + Math.min((dist - DISTANCE_SCALE_START) * DISTANCE_SCALE_PER_BLOCK, 2.5f) * intensity;
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

	private static void submitEdenBar(SubmitNodeCollector collector, PoseStack poseStack, UUID uuid, PartyHealthTracker.PlayerHealthData health, BridgeConfig config) {
		float rawPercent = health.percent();
		float targetPercent = Float.isNaN(rawPercent) ? 1.0f : Math.max(0.0f, Math.min(1.0f, rawPercent));
		float rawOverPercent = health.overPercent();
		float targetOverPercent = Float.isNaN(rawOverPercent) ? 0.0f : Math.max(0.0f, Math.min(1.0f, rawOverPercent));

		long now = System.currentTimeMillis();
		SmoothHealthState smooth = SMOOTH_STATES.computeIfAbsent(uuid, k -> new SmoothHealthState());
		float displayedPercent;
		float displayedOverPercent;
		if (!smooth.initialized || (now - smooth.lastUpdateMs) > 1000L) {
			smooth.displayedPercent = targetPercent;
			smooth.displayedOverPercent = targetOverPercent;
			smooth.lastUpdateMs = now;
			smooth.initialized = true;
			displayedPercent = targetPercent;
			displayedOverPercent = targetOverPercent;
		} else {
			long dtMs = Math.min(100L, Math.max(1L, now - smooth.lastUpdateMs));
			smooth.lastUpdateMs = now;
			float dtSec = dtMs / 1000.0f;
			// Frame-rate independent exponential approach: glides smoothly to new HP in ~0.35s
			float blend = 1.0f - (float) Math.exp(-8.0f * dtSec);
			smooth.displayedPercent += (targetPercent - smooth.displayedPercent) * blend;
			smooth.displayedOverPercent += (targetOverPercent - smooth.displayedOverPercent) * blend;
			if (Math.abs(targetPercent - smooth.displayedPercent) < 0.002f) {
				smooth.displayedPercent = targetPercent;
			}
			if (Math.abs(targetOverPercent - smooth.displayedOverPercent) < 0.002f) {
				smooth.displayedOverPercent = targetOverPercent;
			}
			displayedPercent = Math.max(0.0f, Math.min(1.0f, smooth.displayedPercent));
			displayedOverPercent = Math.max(0.0f, Math.min(1.0f, smooth.displayedOverPercent));
		}

		float x = -BAR_WIDTH / 2.0f;
		float y = 0.0f;

		int fillColor;
		if (displayedPercent > 0.50f) {
			fillColor = COLOR_PROVI_GREEN;
		} else if (displayedPercent > 0.25f) {
			fillColor = COLOR_AMBER;
		} else {
			fillColor = COLOR_CRIMSON;
		}

		List<Integer> palette = (config != null && config.partyColors != null && config.partyColors.size() == 10) ? config.partyColors : PartyHighlightManager.DEFAULT_PALETTE;
		int slotIndex = health.partySlot();
		int slotColor = (slotIndex >= 0 && slotIndex < palette.size()) ? palette.get(slotIndex) | 0xFF000000 : COLOR_PROVI_GREEN;

		int r = (slotColor >> 16) & 0xFF;
		int g = (slotColor >> 8) & 0xFF;
		int b = slotColor & 0xFF;
		int darkerSlotColor = 0xFF000000 | ((int) (r * 0.38f) << 16) | ((int) (g * 0.38f) << 8) | (int) (b * 0.38f);

		collector.submitCustomGeometry(poseStack, RenderTypes.text(BAR_TEXTURE), (pose, vertices) -> {
			// 1. Dark Deficit Background (V: 24..31 / 64) - back layer
			drawTexturedQuad(vertices, pose, FULL_BRIGHT, x, y, BAR_WIDTH, BAR_HEIGHT, 0.0f, 0.0f, 24.0f / 64.0f, 1.0f, 31.0f / 64.0f, 0xFFFFFFFF);

			// 2. Smooth Base Health Bar Fill (V: 0..7 / 64) - on top of deficit background
			if (displayedPercent > 0.0f) {
				float fillWidth = BAR_WIDTH * displayedPercent;
				drawTexturedQuad(vertices, pose, FULL_BRIGHT, x, y, fillWidth, BAR_HEIGHT, 0.01f, 0.0f, 0.0f, displayedPercent, 7.0f / 64.0f, fillColor);
			}

			// 3. Smooth Overhealth Fill (V: 0..7 / 64) - gold overlay on top of base health fill
			if (displayedOverPercent > 0.0f) {
				float overWidth = BAR_WIDTH * displayedOverPercent;
				drawTexturedQuad(vertices, pose, FULL_BRIGHT, x, y, overWidth, BAR_HEIGHT, 0.015f, 0.0f, 0.0f, displayedOverPercent, 7.0f / 64.0f, COLOR_GOLD);
			}

			// 4. Inner Bezel (V: 16..23 / 64) - on top of fill edge, darker shade of party slot color
			drawTexturedQuad(vertices, pose, FULL_BRIGHT, x, y, BAR_WIDTH, BAR_HEIGHT, 0.02f, 0.0f, 16.0f / 64.0f, 1.0f, 23.0f / 64.0f, darkerSlotColor);

			// 5. Outer Border (V: 8..15 / 64) - front layer, party slot position color
			drawTexturedQuad(vertices, pose, FULL_BRIGHT, x, y, BAR_WIDTH, BAR_HEIGHT, 0.03f, 0.0f, 8.0f / 64.0f, 1.0f, 15.0f / 64.0f, slotColor);

			// 5. Quarter Dividers (25%, 50%, 75%)
			float dividerWidth = BAR_WIDTH / 64.0f;
			float dividerY = y + (BAR_HEIGHT * (2.0f / 7.0f));
			float dividerHeight = BAR_HEIGHT * (3.0f / 7.0f);
			float whiteU = 1.5f / 64.0f;
			float whiteV = 33.5f / 64.0f;
			for (float mark = 0.25f; mark < 1.0f; mark += 0.25f) {
				float markX = x + (BAR_WIDTH * mark) - (dividerWidth / 2.0f);
				drawTexturedQuad(vertices, pose, FULL_BRIGHT, markX, dividerY, dividerWidth, dividerHeight, 0.04f, whiteU, whiteV, whiteU, whiteV, darkerSlotColor);
			}
		});
	}

	private static void drawTexturedQuad(VertexConsumer vertices, PoseStack.Pose pose, int light, float x, float y, float width, float height, float z, float minU, float minV, float maxU, float maxV, int color) {
		vertices.addVertex(pose, x, y, z).setColor(color).setUv(minU, minV).setLight(light);
		vertices.addVertex(pose, x, y + height, z).setColor(color).setUv(minU, maxV).setLight(light);
		vertices.addVertex(pose, x + width, y + height, z).setColor(color).setUv(maxU, maxV).setLight(light);
		vertices.addVertex(pose, x + width, y, z).setColor(color).setUv(maxU, minV).setLight(light);
	}
}
