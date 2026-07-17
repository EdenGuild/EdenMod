package tel.eden.mod.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the client's active boss bars (war tower stats live in one). */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {
	@Accessor("events")
	Map<UUID, LerpingBossEvent> edenmod$events();
}
