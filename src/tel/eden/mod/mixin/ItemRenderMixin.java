package tel.eden.mod.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.item.CustomItemTextures;

/**
 * Applies custom consumable textures at the point items are drawn. Inventory slots and the
 * hotbar both render through {@code GuiGraphics.renderItem(LivingEntity, ItemStack, ...)},
 * so hooking its head lets {@link CustomItemTextures} swap the item's model before it is
 * drawn.
 */
@Mixin(GuiGraphics.class)
public class ItemRenderMixin {
	@Inject(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"))
	private void edenmod$applyCustomTexture(LivingEntity entity, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
		try {
			CustomItemTextures.applyCustomTexture(stack);
		} catch (RuntimeException ignored) {
			// A texture swap must never break item rendering.
		}
	}
}
