package tel.eden.mod.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.EdenModClient;

/** Tracks client-initiated closes, including closes sent by other client mods. */
@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonPacketListenerMixin {
	@Inject(method = "send", at = @At("HEAD"))
	private void edenBridge$observeContainerClose(Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ServerboundContainerClosePacket close) {
			EdenModClient mod = EdenModClient.instance();
			if (mod != null) {
				mod.onGuildLogContainerClosed(close.getContainerId());
			}
		}
	}
}
