package tel.eden.mod.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tel.eden.mod.party.PartyHighlightManager;

@Mixin(Entity.class)
public abstract class EntityOutlineMixin {
	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
	private void edenmod$partyOutlineColor(CallbackInfoReturnable<Integer> cir) {
		if ((Object) this instanceof Player player) {
			Integer color = PartyHighlightManager.getPartyOutlineColor(player);
			if (color != null) {
				cir.setReturnValue(color);
			}
		}
	}
}
