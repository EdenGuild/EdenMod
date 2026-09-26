package tel.eden.mod.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.EdenModClient;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerCommandAliasMixin {
	private static final ThreadLocal<Boolean> ALIAS_BYPASS = ThreadLocal.withInitial(() -> false);

	@Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
	private void edenmod$rewriteCommandAlias(String command, CallbackInfo ci) {
		if (ALIAS_BYPASS.get()) {
			return;
		}
		EdenModClient mod = EdenModClient.instance();
		if (mod == null) {
			return;
		}
		mod.onOutgoingCommand(command);
		String rewritten = mod.rewriteOutgoingCommand(command);
		if (rewritten == null || rewritten.equals(command)) {
			return;
		}

		ALIAS_BYPASS.set(true);
		try {
			((ClientPacketListener) (Object) this).sendCommand(rewritten);
		} finally {
			ALIAS_BYPASS.set(false);
		}
		ci.cancel();
	}
}
