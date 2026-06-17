package tel.eden.mod.mixin;

import tel.eden.mod.EdenModClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures system chat at the earliest point — at the HEAD of
 * {@code handleSystemChat}, before Wynntils or the Fabric message API can cancel
 * or reformat it — and forwards the raw component to the bridge.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void edenBridge$captureGuildChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (packet.overlay()) {
            return; // action-bar/overlay messages are never guild chat
        }
        EdenModClient mod = EdenModClient.instance();
        if (mod != null) {
            mod.handleSystemChat(packet.content());
        }
    }
}
