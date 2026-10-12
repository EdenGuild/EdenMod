package tel.eden.mod.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.config.BridgeConfig;
import tel.eden.mod.raid.RaidGambitSafety;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {
	@Shadow
	@Nullable
	protected Slot hoveredSlot;

	@Shadow
	public abstract AbstractContainerMenu getMenu();

	@Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ClickType;)V", at = @At("HEAD"), cancellable = true)
	private void edenmod$onSlotClicked(Slot slot, int slotId, int mouseButton, ClickType clickType, CallbackInfo ci) {
		try {
			BridgeConfig config = EdenModClient.instance().config();
			if (RaidGambitSafety.shouldBlockReadyClick(getMenu(), slot, clickType, config)) {
				ci.cancel();
				RaidGambitSafety.notifyBlocked(Minecraft.getInstance(), config.raidGambitMinimum);
			}
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "getTooltipFromContainerItem(Lnet/minecraft/world/item/ItemStack;)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
	private void edenmod$appendRaidReadyTooltip(ItemStack stack, CallbackInfoReturnable<List<Component>> cir) {
		try {
			if (this.hoveredSlot != null && RaidGambitSafety.isReadyClickSlot(this.hoveredSlot)) {
				BridgeConfig config = EdenModClient.instance().config();
				if (config != null && config.raidGambitSafety && config.raidGambitMinimum > 0) {
					AbstractContainerMenu menu = getMenu();
					if (RaidGambitSafety.isRaidReadyMenu(menu) && !RaidGambitSafety.isPlayerAlreadyReadied(menu)) {
						int enabled = RaidGambitSafety.countEnabledGambits(menu);
						if (enabled < config.raidGambitMinimum) {
							List<Component> base = cir.getReturnValue();
							List<Component> tooltip = new ArrayList<>(base != null ? base : List.of());
							tooltip.add(Component.empty());
							tooltip.add(RaidGambitSafety.getTooltipWarning(config.raidGambitMinimum));
							cir.setReturnValue(tooltip);
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}
}
