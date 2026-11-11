package tel.eden.mod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.item.GroundItemVisibilityRenderer;

/**
 * Scales dropped item renders without touching entity size or pickup behavior. The pose
 * scale is inserted immediately before the clustered item model submission, after vanilla
 * has already applied its bob/spin transforms, so the scaled item stays anchored to the
 * same floating/rotating origin.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class GroundItemVisibilityMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V", at = @At("TAIL"))
	private void edenmod$extractDroppedItemScale(ItemEntity itemEntity, ItemEntityRenderState state, float partialTick, CallbackInfo ci) {
		EdenModClient client = EdenModClient.instance();
		GroundItemVisibilityRenderer.extract(client.config(), itemEntity, state);
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"))
	private void edenmod$scaleDroppedItem(ItemEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo ci) {
		float scale = GroundItemVisibilityRenderer.scaleFor(state);
		if (scale != 1.0f) {
			poseStack.scale(scale, scale, scale);
		}
	}
}
