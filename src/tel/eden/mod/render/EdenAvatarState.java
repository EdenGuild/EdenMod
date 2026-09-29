package tel.eden.mod.render;

import java.util.UUID;

/**
 * Accessor interface duck-typed onto {@link net.minecraft.client.renderer.entity.state.AvatarRenderState}
 * to carry player identity across the render pipeline without polling the world entity.
 */
public interface EdenAvatarState {
	UUID edenmod$getPlayerUuid();

	void edenmod$setPlayerUuid(UUID uuid);
}
