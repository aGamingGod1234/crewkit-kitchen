package dev.agaminggod.arenaagents.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.agaminggod.arenaagents.server.pov.PovViewRedirect;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Point broadcasts (sounds, level events, block events) reach an operator in a POV session when they are near its
 * agent, not its body, so it hears what the agent hears. The listener is a loop local, hence the wraps.
 */
@Mixin(PlayerList.class)
abstract class PlayerListBroadcastPovMixin {
	@WrapOperation(method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getX()D"))
	private double arenaagents$listenAtPovAnchorX(ServerPlayer listener, Operation<Double> original) {
		return PovViewRedirect.anchorX(listener, original.call(listener));
	}

	@WrapOperation(method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getY()D"))
	private double arenaagents$listenAtPovAnchorY(ServerPlayer listener, Operation<Double> original) {
		return PovViewRedirect.anchorY(listener, original.call(listener));
	}

	@WrapOperation(method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getZ()D"))
	private double arenaagents$listenAtPovAnchorZ(ServerPlayer listener, Operation<Double> original) {
		return PovViewRedirect.anchorZ(listener, original.call(listener));
	}
}
