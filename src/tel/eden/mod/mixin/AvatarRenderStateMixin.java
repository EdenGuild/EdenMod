package tel.eden.mod.mixin;

import java.util.UUID;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import tel.eden.mod.render.EdenAvatarState;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements EdenAvatarState {
	@Unique
	private UUID edenmod$playerUuid;

	@Override
	public UUID edenmod$getPlayerUuid() {
		return edenmod$playerUuid;
	}

	@Override
	public void edenmod$setPlayerUuid(UUID playerUuid) {
		this.edenmod$playerUuid = playerUuid;
	}
}
