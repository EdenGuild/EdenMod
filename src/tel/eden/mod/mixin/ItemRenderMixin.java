package tel.eden.mod.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.item.CustomItemTextures;

/**
 * Applies custom consumable textures at the point items are drawn. All of
 * {@code GuiGraphics}' public {@code renderItem} overloads (hotbar, inventory/container
 * slots, tooltips) funnel into the private {@code renderItem(LivingEntity, Level,
 * ItemStack, …)}, so hooking <em>that</em> catches every render path. It also runs each
 * frame, so if the server re-sends an inventory stack (dropping our model swap) the very
 * next frame re-applies it — no flicker to vanilla.
 */
@Mixin(GuiGraphics.class)
public class ItemRenderMixin {
	@Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"))
	private void edenmod$applyCustomTexture(LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
		try {
			CustomItemTextures.applyCustomTexture(stack);
		} catch (RuntimeException ignored) {
			// A texture swap must never break item rendering.
		}
	}
}
