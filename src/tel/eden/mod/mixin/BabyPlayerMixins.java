package tel.eden.mod.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tel.eden.mod.gui.EdenMenuScreen;
import tel.eden.mod.render.EdenDisplayState;
import tel.eden.mod.util.BabyPlayerEmoteTracker;
import tel.eden.mod.util.Wynncraft;

// Crouching adds a small renderer offset before the model is scaled. Adjust that
// offset separately or a crouching baby player is pushed too far into the ground.
@Mixin(AvatarRenderer.class)
class BabyPlayerModelMixin {
	@Inject(method = "getRenderOffset(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"), cancellable = true)
	private void edenmod$scaleCrouchOffset(AvatarRenderState state, CallbackInfoReturnable<Vec3> cir) {
		if (!EdenMenuScreen.isBabyModeEnabled() || !state.isCrouching || !BabyPlayerEmoteTracker.isActualPlayer(state)) {
			return;
		}

		double vanillaOffset = state.scale * -2.0F / 16.0;
		double scaledOffset = vanillaOffset * EdenMenuScreen.BABY_PLAYER_SCALE;
		cir.setReturnValue(cir.getReturnValue().add(0.0, scaledOffset - vanillaOffset, 0.0));
	}
}

// The head is enlarged after vanilla animation setup. Doing this at the tail keeps
// setupAnim from replacing the scale and scales the hat/skin layer with the head.
@Mixin(HumanoidModel.class)
class BabyPlayerHeadMixin {
	@Shadow
	@Final
	public ModelPart head;

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void edenmod$scalePlayerHead(HumanoidRenderState state, CallbackInfo ci) {
		if (!EdenMenuScreen.isBabyModeEnabled() || !(state instanceof AvatarRenderState avatarState) || !BabyPlayerEmoteTracker.isActualPlayer(avatarState)) {
			return;
		}

		head.xScale *= EdenMenuScreen.BABY_HEAD_SCALE;
		head.yScale *= EdenMenuScreen.BABY_HEAD_SCALE;
		head.zScale *= EdenMenuScreen.BABY_HEAD_SCALE;
	}
}

// Normal player rendering uses one matrix for the body and all of its render layers.
// Scaling here therefore keeps armour and the outer skin layer attached to the model.
@Mixin(LivingEntityRenderer.class)
class BabyPlayerScaleMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;scale(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void edenmod$scalePlayerModel(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraState, CallbackInfo ci) {
		if (EdenMenuScreen.isBabyModeEnabled() && state instanceof AvatarRenderState avatarState && BabyPlayerEmoteTracker.isActualPlayer(avatarState)) {
			float scale = EdenMenuScreen.BABY_PLAYER_SCALE;
			poseStack.scale(scale, scale, scale);
		}
	}
}

// Name tags and entity shadows are submitted outside the model pose stack, so they
// need their own scale adjustments. Wynncraft emotes may put the player on a carrier.
@Mixin(EntityRenderer.class)
class BabyPlayerShadowMixin {
	private static final double EDENMOD_HEAD_HEIGHT = 8.0 / 16.0;
	private static final double EDENMOD_NAME_TAG_CLEARANCE = 2.0 / 16.0;

	@Inject(method = "finalizeRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;)V", at = @At("RETURN"))
	private void edenmod$scalePlayerNameTagAndShadow(Entity entity, EntityRenderState state, CallbackInfo ci) {
		if (!EdenMenuScreen.isBabyModeEnabled()) {
			return;
		}

		Entity root = entity.getRootVehicle();
		boolean actualAvatar = state instanceof AvatarRenderState && entity instanceof AbstractClientPlayer player && BabyPlayerEmoteTracker.isActualPlayer(player);
		boolean playerBacked = root instanceof AbstractClientPlayer player && BabyPlayerEmoteTracker.isActive(player);
		boolean mountedPlayer = false;
		for (Entity passenger : root.getIndirectPassengers()) {
			if (passenger instanceof AbstractClientPlayer player && BabyPlayerEmoteTracker.isActualPlayer(player)) {
				// A real mount owns the visible shadow even when the rider is not in an
				// emote. Carrier entities only qualify while their emote is active.
				mountedPlayer |= BabyPlayerEmoteTracker.isGroundedMount(root);
				playerBacked |= BabyPlayerEmoteTracker.isActive(player.getUUID());
			}
		}
		boolean compactModel = actualAvatar || playerBacked;
		boolean shouldScale = compactModel || mountedPlayer;
		if (!shouldScale && entity instanceof Display) {
			shouldScale = BabyPlayerEmoteTracker.findActiveOwner(entity) != null;
		}
		if (actualAvatar && state.nameTagAttachment != null) {
			float bodyScale = EdenMenuScreen.BABY_PLAYER_SCALE;
			double enlargedHeadHeight = EDENMOD_HEAD_HEIGHT * bodyScale * (EdenMenuScreen.BABY_HEAD_SCALE - 1.0F);
			// Player labels share one foot-relative anchor. The below-name line uses
			// that lower bound, while the name is placed above it by the player renderer.
			// Keep a small fixed gap above the enlarged head without scaling the text.
			state.nameTagAttachment = state.nameTagAttachment.scale(bodyScale).add(0.0, enlargedHeadHeight + EDENMOD_NAME_TAG_CLEARANCE, 0.0);
		}
		if (shouldScale) {
			state.shadowRadius *= EdenMenuScreen.BABY_PLAYER_SCALE;
		}
	}
}

// Wynncraft uses display entities for held items and some extra emote pieces. These
// do not pass through the player renderer, so their position and size are handled here.
@Mixin(DisplayRenderer.class)
abstract class BabyPlayerEmoteDisplayMixin {
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/DisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"))
	private void edenmod$beginDisplaySubmission(DisplayEntityRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraState, CallbackInfo ci) {
		if (EdenMenuScreen.isBabyModeEnabled()) {
			BabyPlayerEmoteTracker.beginDisplaySubmission(state);
		}
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Display;Lnet/minecraft/client/renderer/entity/state/DisplayEntityRenderState;F)V", at = @At("TAIL"))
	private void edenmod$trackDisplayEntity(Display display, DisplayEntityRenderState state, float partialTick, CallbackInfo ci) {
		// submit() receives only the render state. Keep the source entity on the state
		// so the display can later be associated with the player performing the emote.
		// Do not move this into a map keyed by state: every display gets a fresh state
		// every frame, and the churn shows up as render-thread spikes (see
		// RenderHotPathTest).
		((EdenDisplayState) state).edenmod$setSourceDisplay(display);
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/DisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/DisplayRenderer;submitInner(Lnet/minecraft/client/renderer/entity/state/DisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IF)V"))
	private void edenmod$scaleWynncraftEmoteDisplay(DisplayEntityRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraState, CallbackInfo ci) {
		if (!EdenMenuScreen.isBabyModeEnabled()) {
			return;
		}

		Display display = EdenDisplayState.sourceOf(state);
		// Player-head displays make up the animated body and are transformed in
		// BabyPlayerEmoteMixin, where Wynncraft's packed position can be decoded.
		if (display instanceof Display.ItemDisplay itemDisplay && itemDisplay.itemRenderState() != null && itemDisplay.itemRenderState().itemStack().is(Items.PLAYER_HEAD)) {
			return;
		}
		AbstractClientPlayer owner = display == null ? null : BabyPlayerEmoteTracker.findActiveOwner(display);
		if (owner == null) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		long renderStamp = BabyPlayerEmoteTracker.currentRenderStamp();
		Vec3 ownerPosition = owner.getPosition(partialTick);
		Vec3 worldPivot = BabyPlayerEmoteTracker.scalePivotForRender(owner, renderStamp, ownerPosition, partialTick);
		Vec3 pivot = worldPivot.subtract(minecraft.gameRenderer.getMainCamera().position());
		edenmod$scaleAboutPivot(poseStack, pivot, EdenMenuScreen.BABY_PLAYER_SCALE);
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/DisplayEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("RETURN"))
	private void edenmod$endDisplaySubmission(DisplayEntityRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState cameraState, CallbackInfo ci) {
		BabyPlayerEmoteTracker.endDisplaySubmission();
	}

	/** Scale both a display's geometry and its camera-relative position around the player's feet. */
	private static void edenmod$scaleAboutPivot(PoseStack poseStack, Vec3 pivot, float scale) {
		Matrix4f pose = poseStack.last().pose();
		double displayX = pose.m30();
		double displayY = pose.m31();
		double displayZ = pose.m32();
		pose.m30((float) (pivot.x + (displayX - pivot.x) * scale));
		pose.m31((float) (pivot.y + (displayY - pivot.y) * scale));
		pose.m32((float) (pivot.z + (displayZ - pivot.z) * scale));
		poseStack.scale(scale, scale, scale);
	}
}

// The real player entity is still rendered while Wynncraft's display-based emote is
// active. Its held-item layer would otherwise draw a second copy of the weapon.
@Mixin(ItemInHandLayer.class)
class BabyPlayerEmoteHeldItemMixin {
	@Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;FF)V", at = @At("HEAD"), cancellable = true)
	private void edenmod$hideRealHeldItemsDuringEmote(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, ArmedEntityRenderState state, float yRot, float xRot, CallbackInfo ci) {
		if (!EdenMenuScreen.isBabyModeEnabled() || !(state instanceof AvatarRenderState avatarState)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		Entity entity = minecraft.level == null ? null : minecraft.level.getEntity(avatarState.id);
		if (entity instanceof AbstractClientPlayer player && BabyPlayerEmoteTracker.isActive(player)) {
			ci.cancel();
		}
	}
}

// Animated Wynncraft bodies are assembled from player-head items, one item per body
// part. Their transform matrix also contains Wynncraft metadata in the Y coordinate.
// This hook decodes that data and applies one common foot-centred scale to every part.
@Mixin(value = PlayerHeadSpecialRenderer.class, priority = 500)
class BabyPlayerEmoteMixin {
	private static final int EDENMOD_EMOTE_HEAD_LIMB_INDEX = 0;
	private static final int EDENMOD_EMOTE_LEFT_LEG_LIMB_INDEX = 4;
	private static final int EDENMOD_EMOTE_RIGHT_LEG_LIMB_INDEX = 5;
	private static final int EDENMOD_EMOTE_POSITION_RADIX = 512;
	private static final int EDENMOD_EMOTE_STEVE_ALEX_RADIX = 2;
	private static final int EDENMOD_EMOTE_LIMB_FADE_RADIX = 3;
	private static final int EDENMOD_EMOTE_LIMB_INDEX_RADIX = 6;
	private static final int EDENMOD_EMOTE_ELEMENT_KIND_RADIX = 2;
	private static final int EDENMOD_EMOTE_SKIN_LIMB = 0;
	private static final double EDENMOD_EMOTE_METADATA_Y = 2.0 * EDENMOD_EMOTE_POSITION_RADIX;
	private static final double EDENMOD_EMOTE_METADATA_LIMIT = EDENMOD_EMOTE_METADATA_Y + (double) EDENMOD_EMOTE_POSITION_RADIX * EDENMOD_EMOTE_STEVE_ALEX_RADIX * EDENMOD_EMOTE_LIMB_FADE_RADIX * EDENMOD_EMOTE_LIMB_INDEX_RADIX * EDENMOD_EMOTE_ELEMENT_KIND_RADIX;
	// Rendering a player head re-enters this renderer; only transform the outer call.
	private static final ThreadLocal<Integer> EDENMOD_EMOTE_RENDER_DEPTH = ThreadLocal.withInitial(() -> 0);

	@Shadow
	@Final
	private SkullModelBase modelBase;

	@Inject(method = "submit(Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V", at = @At("HEAD"), cancellable = true)
	private void edenmod$scaleWynncraftEmotePart(PlayerSkinRenderCache.RenderInfo renderInfo, ItemDisplayContext displayContext, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, int packedOverlay, boolean hasFoil, int color, CallbackInfo ci, @Share("edenmod$posePushed") LocalBooleanRef posePushed) {
		if (EDENMOD_EMOTE_RENDER_DEPTH.get() > 0 || !EdenMenuScreen.isBabyModeEnabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.getCurrentServer() == null || !Wynncraft.isWynncraft(minecraft.getCurrentServer().ip)) {
			return;
		}

		Matrix4f pose = poseStack.last().pose();
		double encodedY = pose.m31();
		BabyPlayerEmoteTracker.EmoteMetadata metadata = edenmod$decodeMetadata(encodedY);
		if (metadata == null) {
			return;
		}
		Display sourceDisplay = BabyPlayerEmoteTracker.currentDisplaySubmission();
		AbstractClientPlayer owner = edenmod$findOwner(minecraft, renderInfo, sourceDisplay);
		if (owner == null) {
			// Packed NPC models can use the same rendering format. Without a real
			// player identity or hierarchy owner, leave every element untouched.
			return;
		}
		if (metadata.elementKind() != EDENMOD_EMOTE_SKIN_LIMB) {
			// Non-skin elements use the same packed format but are not body parts.
			// Leaving them at full size would put large effect geometry inside the model.
			ci.cancel();
			return;
		}
		BabyPlayerEmoteTracker.markActive(owner.getUUID());

		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().position();
		float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		double decodedY = edenmod$decodeEmoteY(encodedY);
		long renderStamp = BabyPlayerEmoteTracker.currentRenderStamp();
		if (edenmod$isLeg(metadata.limbIndex())) {
			// The entity position is not a reliable ground point during an emote. Measure
			// both rendered legs and use the bottom centre as the scale origin instead.
			RenderedBounds bounds = edenmod$findRenderedBounds(poseStack);
			if (bounds.isFinite()) {
				BabyPlayerEmoteTracker.samplePivot(owner.getUUID(), metadata.limbIndex(), bounds.minX + cameraPosition.x, bounds.maxX + cameraPosition.x, bounds.minY + cameraPosition.y, bounds.minZ + cameraPosition.z, bounds.maxZ + cameraPosition.z, renderStamp);
			}
		}

		Vec3 ownerPosition = owner.getPosition(partialTick);
		Vec3 worldPivot = BabyPlayerEmoteTracker.scalePivotForRender(owner, renderStamp, ownerPosition, partialTick);
		Vec3 pivot = worldPivot.subtract(cameraPosition);
		double modelX = pose.m30();
		double modelZ = pose.m32();
		float bodyScale = EdenMenuScreen.BABY_PLAYER_SCALE;
		poseStack.pushPose();
		pose = poseStack.last().pose();
		// X/Z are ordinary camera-relative coordinates. Y also carries Wynncraft's
		// metadata, so preserve the packed value while scaling its decoded position.
		pose.m30((float) (pivot.x + (modelX - pivot.x) * bodyScale));
		pose.m31((float) (encodedY + (pivot.y - decodedY) * (1.0F - bodyScale)));
		pose.m32((float) (pivot.z + (modelZ - pivot.z) * bodyScale));
		poseStack.scale(bodyScale, bodyScale, bodyScale);
		if (metadata.limbIndex() == EDENMOD_EMOTE_HEAD_LIMB_INDEX) {
			poseStack.translate(0.5F, 0.0F, 0.5F);
			poseStack.scale(EdenMenuScreen.BABY_HEAD_SCALE, EdenMenuScreen.BABY_HEAD_SCALE, EdenMenuScreen.BABY_HEAD_SCALE);
			poseStack.translate(-0.5F, 0.0F, -0.5F);
		}
		posePushed.set(true);
		EDENMOD_EMOTE_RENDER_DEPTH.set(EDENMOD_EMOTE_RENDER_DEPTH.get() + 1);
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;Lnet/minecraft/world/item/ItemDisplayContext;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V", at = @At("RETURN"))
	private void edenmod$restoreWynncraftEmotePart(PlayerSkinRenderCache.RenderInfo renderInfo, ItemDisplayContext displayContext, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int packedLight, int packedOverlay, boolean hasFoil, int color, CallbackInfo ci, @Share("edenmod$posePushed") LocalBooleanRef posePushed) {
		if (posePushed.get()) {
			EDENMOD_EMOTE_RENDER_DEPTH.set(Math.max(0, EDENMOD_EMOTE_RENDER_DEPTH.get() - 1));
			poseStack.popPose();
		}
	}

	private static boolean edenmod$isLeg(int limbIndex) {
		return limbIndex == EDENMOD_EMOTE_LEFT_LEG_LIMB_INDEX || limbIndex == EDENMOD_EMOTE_RIGHT_LEG_LIMB_INDEX;
	}

	private static AbstractClientPlayer edenmod$findOwner(Minecraft minecraft, PlayerSkinRenderCache.RenderInfo renderInfo, Display sourceDisplay) {
		if (sourceDisplay != null) {
			AbstractClientPlayer hierarchyOwner = BabyPlayerEmoteTracker.findPlayerInHierarchy(sourceDisplay);
			if (hierarchyOwner == minecraft.player) {
				return hierarchyOwner;
			}
		}

		AbstractClientPlayer localPlayer = minecraft.player;
		GameProfile profile = renderInfo == null ? null : renderInfo.gameProfile();
		if (localPlayer == null || profile == null || (profile.id() == null && profile.name() == null)) {
			return null;
		}
		if (profile.id() != null && profile.id().equals(localPlayer.getUUID())) {
			return localPlayer;
		}
		String localName = localPlayer.getGameProfile().name();
		return profile.name() != null && localName != null && profile.name().equalsIgnoreCase(localName) ? localPlayer : null;
	}

	private static BabyPlayerEmoteTracker.EmoteMetadata edenmod$decodeMetadata(double encodedY) {
		if (encodedY < EDENMOD_EMOTE_METADATA_Y || encodedY >= EDENMOD_EMOTE_METADATA_LIMIT) {
			return null;
		}
		// Wynncraft packs several small fields into the integral Y component. Peel
		// off each mixed-radix field in the same order in which it was encoded.
		int metadata = (int) encodedY - (int) EDENMOD_EMOTE_METADATA_Y;
		metadata /= EDENMOD_EMOTE_POSITION_RADIX;
		metadata /= EDENMOD_EMOTE_STEVE_ALEX_RADIX;
		metadata /= EDENMOD_EMOTE_LIMB_FADE_RADIX;
		int limbIndex = Math.floorMod(metadata, EDENMOD_EMOTE_LIMB_INDEX_RADIX);
		metadata /= EDENMOD_EMOTE_LIMB_INDEX_RADIX;
		int elementKind = Math.floorMod(metadata, EDENMOD_EMOTE_ELEMENT_KIND_RADIX);
		return new BabyPlayerEmoteTracker.EmoteMetadata(limbIndex, elementKind);
	}

	private RenderedBounds edenmod$findRenderedBounds(PoseStack poseStack) {
		// Ask the model for its already-transformed vertices. This follows the current
		// emote pose and avoids maintaining separate bounds for every animation frame.
		double[] bounds = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.0F, 0.5F);
		poseStack.scale(-1.0F, -1.0F, 1.0F);
		modelBase.root().getExtentsForGui(poseStack, point -> {
			bounds[0] = Math.min(bounds[0], point.x());
			bounds[1] = Math.max(bounds[1], point.x());
			bounds[2] = Math.min(bounds[2], edenmod$decodeEmoteY(point.y()));
			bounds[3] = Math.min(bounds[3], point.z());
			bounds[4] = Math.max(bounds[4], point.z());
		});
		poseStack.popPose();
		return new RenderedBounds(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4]);
	}

	private static double edenmod$decodeEmoteY(double encodedY) {
		// The actual Y position occupies one 512-wide section of the packed value.
		double wrapped = encodedY % EDENMOD_EMOTE_POSITION_RADIX;
		if (wrapped < 0.0) {
			wrapped += EDENMOD_EMOTE_POSITION_RADIX;
		}
		return wrapped - (EDENMOD_EMOTE_POSITION_RADIX / 2.0 - 1.0);
	}

	private record RenderedBounds(double minX, double maxX, double minY, double minZ, double maxZ) {
		private boolean isFinite() {
			return Double.isFinite(minX) && Double.isFinite(maxX) && Double.isFinite(minY) && Double.isFinite(minZ) && Double.isFinite(maxZ);
		}
	}
}
