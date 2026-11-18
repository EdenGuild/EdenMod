package tel.eden.mod.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
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

	@Test
	void parsesDashedAndDashlessUuids() {
		UUID expected = UUID.fromString("387c49fd-f06c-47b7-97a6-b97a5ecc0bd3");
		assertEquals(expected, PlayerSkinResolver.parseUuid("387c49fd-f06c-47b7-97a6-b97a5ecc0bd3"));
		assertEquals(expected, PlayerSkinResolver.parseUuid(" 387c49fdf06c47b797a6b97a5ecc0bd3 "));
		assertNull(PlayerSkinResolver.parseUuid("not-a-uuid"));
		assertNull(PlayerSkinResolver.parseUuid(""));
		assertNull(PlayerSkinResolver.parseUuid(null));
	}
}
