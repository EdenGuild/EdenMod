package tel.eden.mod.mixin;

import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.DiscordChatFormatter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
	private void edenBridge$captureGuildChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		if (packet.overlay()) {
			return;
		}
		EdenModClient mod = EdenModClient.instance();
		if (mod != null) {
			mod.handleSystemChat(packet.content());
		}
		Component modified = DiscordChatFormatter.processEmotes(packet.content());
		if (modified != packet.content()) {
			Component finalModified = modified;
			ci.cancel();
			Minecraft.getInstance().execute(() -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) {
					mc.player.displayClientMessage(finalModified, false);
				}
			});
		}
	}
}
