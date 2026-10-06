package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.pov.PovClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** handleRespawn points the camera at the new local player; rebind the agent view or end it on a new level. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerPovMixin {
	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void arenaagents$keepPovCamera(ClientboundRespawnPacket packet, CallbackInfo callback) {
		PovClient.afterRespawn(Minecraft.getInstance());
	}
}
