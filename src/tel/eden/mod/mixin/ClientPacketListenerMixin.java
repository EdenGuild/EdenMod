package tel.eden.mod.mixin;

import tel.eden.mod.EdenModClient;
import tel.eden.mod.chat.ChatDecorators;
import tel.eden.mod.chat.DiscordChatFormatter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@Inject(method = "setActionBarText", at = @At("HEAD"))
	private void edenBridge$observeWynncraftActionBar(ClientboundSetActionBarTextPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isSameThread()) {
			EdenModClient mod = EdenModClient.instance();
			if (mod != null) {
				mod.handleWynncraftActionBar(packet.text());
			}
		}
	}

	@Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
	private void edenBridge$muteBackgroundContainerClick(ClientboundSoundPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread()) {
			return;
		}
		EdenModClient mod = EdenModClient.instance();
		if (mod != null && mod.shouldMuteBackgroundMenuClick(packet.getSound().value(), packet.getSource())) {
			ci.cancel();
		}
	}

	@Inject(method = "handleContainerClose", at = @At("HEAD"))
	private void edenBridge$observeServerContainerClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isSameThread()) {
			EdenModClient mod = EdenModClient.instance();
			if (mod != null) {
				mod.onGuildLogContainerClosed(packet.getContainerId());
			}
		}
	}

	@Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
	private void edenBridge$openGuildLogInBackground(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread()) {
			return;
		}
		EdenModClient mod = EdenModClient.instance();
		if (mod != null && mod.onGuildLogOpenScreen(packet)) {
			ci.cancel();
		}
	}

	@Inject(method = "handleContainerContent", at = @At("HEAD"), cancellable = true)
	private void edenBridge$captureBackgroundGuildLogContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread()) {
			return;
		}
		EdenModClient mod = EdenModClient.instance();
		if (mod != null && mod.onGuildLogContent(packet.containerId(), packet.stateId(), packet.items())) {
			ci.cancel();
		}
	}

	@Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
	private void edenBridge$captureBackgroundGuildLogSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (!minecraft.isSameThread()) {
			return;
		}
		EdenModClient mod = EdenModClient.instance();
		if (mod != null && mod.onGuildLogSlot(packet.getContainerId(), packet.getStateId(), packet.getSlot(), packet.getItem())) {
			ci.cancel();
		}
	}

	@Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
	private void edenBridge$captureGuildChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		// Vanilla enters this method once on Netty, schedules the packet onto the client
		// thread, then enters it again there. Running our capture on both entries used to
		// enqueue every reward confirmation twice, so one real ticker could account for two
		// gifts. Let vanilla perform the hand-off and only inspect the packet on its final,
		// client-thread invocation.
		if (!minecraft.isSameThread()) {
			return;
		}
		EdenModClient mod = EdenModClient.instance();
		if (packet.overlay()) {
			// Reward feedback may be rendered as Wynncraft's ticker/action bar rather than a
			// chat line. It is bookkeeping input, but must not enter the general chat relay.
			if (mod != null) {
				mod.handleRewardFeedback(packet.content());
			}
			return;
		}
		if (mod != null) {
			mod.handleSystemChat(packet.content());
		}
		Component modified = DiscordChatFormatter.processEmotes(packet.content());
		if (mod != null) {
			// Display-only decorations: click-to-reply shouts, congratulate buttons.
			modified = ChatDecorators.decorate(modified, mod.config());
		}
		if (modified != packet.content()) {
			// Keep the re-display deferred so it happens after this packet handler returns;
			// mutating the chat GUI recursively from inside it can flicker or drop the line.
			ci.cancel();
			Component finalModified = modified;
			minecraft.execute(() -> {
				Minecraft mc = Minecraft.getInstance();
				if (mc.player != null) {
					mc.player.displayClientMessage(finalModified, false);
				}
			});
		}
	}
}
