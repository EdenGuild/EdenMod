package tel.eden.mod.mixin;

import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.ChatDecorators;
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
		if (mod != null) {
			// Display-only decorations: click-to-reply shouts, congratulate buttons.
			modified = ChatDecorators.decorate(modified, mod.config());
		}
		if (modified != packet.content()) {
			// This HEAD inject fires before vanilla's ensureRunningOnSameThread, i.e.
			// on the netty network thread. Marshal the re-display onto the client
			// thread — mutating the chat GUI off-thread races the render thread and
			// the emote message would flicker, drop, or crash.
			ci.cancel();
			Component finalModified = modified;
			Minecraft.getInstance().execute(() -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) {
					mc.player.displayClientMessage(finalModified, false);
				}
			});
		}
	}
}
