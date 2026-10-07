package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.pov.input.PovOutgoingRelay;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single place vanilla screens send their packets from. During a takeover the anvil, beacon, merchant, sign,
 * book, recipe book, bundle and crafter packets belong to the agent's body and are relayed to it instead.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ClientCommonPacketListenerPovMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void arenaagents$relayAgentScreenPackets(Packet<?> packet, CallbackInfo callback) {
		if (PovOutgoingRelay.intercept(packet)) callback.cancel();
	}
}
