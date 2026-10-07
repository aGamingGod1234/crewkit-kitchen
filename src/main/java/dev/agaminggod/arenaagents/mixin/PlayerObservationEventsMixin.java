package dev.agaminggod.arenaagents.mixin;

import carpet.patches.EntityPlayerMPFake;
import dev.agaminggod.arenaagents.server.perception.PlayerObservationEvents;
import dev.agaminggod.arenaagents.server.pov.PovMessageRelay;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class PlayerObservationEventsMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"))
	private void arenaagents$observeAddressedPacket(Packet<?> packet, ChannelFutureListener listener, CallbackInfo callback) {
		if (!((Object) this instanceof ServerGamePacketListenerImpl connection)) return;
		if (connection.player instanceof EntityPlayerMPFake) PlayerObservationEvents.capture(connection.player, packet);
		// Agent text a takeover operator should see, and the operator's own text to drop broadcasts it already got.
		PovMessageRelay.observe(connection.player, packet);
	}
}
