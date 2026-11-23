package tel.eden.mod.mixin;

import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The fill the server last sent; {@code getProgress()} is mid-animation for 100ms after each update. */
@Mixin(LerpingBossEvent.class)
public interface LerpingBossEventAccessor {
	@Accessor("targetPercent")
	float edenmod$targetPercent();
}
