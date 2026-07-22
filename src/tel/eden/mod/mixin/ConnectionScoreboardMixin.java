package tel.eden.mod.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tel.eden.mod.war.ScoreboardCapture;

/**
 * Mirrors the raw scoreboard packets into {@link ScoreboardCapture}'s shadow scoreboard.
 *
 * <p>Injected at the <em>head of {@code Connection.channelRead0}</em> — the netty entry
 * point that later calls {@code packet.handle(listener)}. This runs strictly before any
 * packet-<em>listener</em> mixin (where Wynntils strips the guild war-timer segment), so
 * the war suite still sees those lines no matter what Wynntils does downstream. We only
 * observe (never cancel), so vanilla and every other handler are unaffected. A low
 * {@code priority} makes this injector run first even against another head injector.
 *
 * <p>{@code channelRead0} runs on the netty thread; the shadow scoreboard is only touched
 * on the client thread (the HUD reads it there), so each apply is marshalled across.
 */
@Mixin(value = Connection.class, priority = 100)
public class ConnectionScoreboardMixin {
	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void edenmod$captureScoreboard(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		// Cheap gate: the vast majority of packets are not scoreboard packets and fall
		// straight through. Only capture the five that define the sidebar.
		if (packet instanceof ClientboundSetScorePacket score) {
			Minecraft.getInstance().execute(() -> ScoreboardCapture.apply(score));
		} else if (packet instanceof ClientboundResetScorePacket reset) {
			Minecraft.getInstance().execute(() -> ScoreboardCapture.apply(reset));
		} else if (packet instanceof ClientboundSetObjectivePacket objective) {
			Minecraft.getInstance().execute(() -> ScoreboardCapture.apply(objective));
		} else if (packet instanceof ClientboundSetDisplayObjectivePacket display) {
			Minecraft.getInstance().execute(() -> ScoreboardCapture.apply(display));
		} else if (packet instanceof ClientboundSetPlayerTeamPacket team) {
			Minecraft.getInstance().execute(() -> ScoreboardCapture.apply(team));
		}
	}
}
