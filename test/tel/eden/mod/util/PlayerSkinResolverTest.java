package tel.eden.mod.util;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import org.junit.jupiter.api.Test;

class PlayerSkinResolverTest {
	@Test
	void returnsDefaultSkinImmediatelyWhenUuidAndNameAreAbsent() {
		PlayerSkin expected = DefaultPlayerSkin.getDefaultSkin();

		PlayerSkin bothNull = PlayerSkinResolver.skinFor(null, null, null);
		assertNotNull(bothNull);
		assertSame(expected, bothNull, "Must return DefaultPlayerSkin directly when both uuid and name are null");

		PlayerSkin emptyName = PlayerSkinResolver.skinFor(null, null, "");
		assertSame(expected, emptyName, "Must return DefaultPlayerSkin directly when name is empty");

		PlayerSkin blankName = PlayerSkinResolver.skinFor(null, null, "   ");
		assertSame(expected, blankName, "Must return DefaultPlayerSkin directly when name is blank");
	}
}
