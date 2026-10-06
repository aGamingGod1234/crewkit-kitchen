package dev.agaminggod.arenaagents.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.agaminggod.arenaagents.server.pov.PovViewRedirect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The ServerLevel effects that pick recipients by their own distance check rather than through PlayerList.broadcast:
 * particles, block-break cracks and explosions. Each is measured from an operator's POV agent.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelParticlesPovMixin {
	// Every public sendParticles overload, targeted or not, ends in this per-player range check.
	@ModifyExpressionValue(method = "sendParticles(Lnet/minecraft/server/level/ServerPlayer;ZDDDLnet/minecraft/network/protocol/Packet;)Z",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;blockPosition()Lnet/minecraft/core/BlockPos;"))
	private BlockPos arenaagents$particlesAroundPovAnchor(BlockPos bodyBlock, @Local(argsOnly = true) ServerPlayer viewer) {
		return PovViewRedirect.blockAnchor(viewer, bodyBlock);
	}

	@WrapOperation(method = "destroyBlockProgress(ILnet/minecraft/core/BlockPos;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getX()D"))
	private double arenaagents$cracksAroundPovAnchorX(ServerPlayer viewer, Operation<Double> original) {
		return PovViewRedirect.anchorX(viewer, original.call(viewer));
	}

	@WrapOperation(method = "destroyBlockProgress(ILnet/minecraft/core/BlockPos;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getY()D"))
	private double arenaagents$cracksAroundPovAnchorY(ServerPlayer viewer, Operation<Double> original) {
		return PovViewRedirect.anchorY(viewer, original.call(viewer));
	}

	@WrapOperation(method = "destroyBlockProgress(ILnet/minecraft/core/BlockPos;I)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getZ()D"))
	private double arenaagents$cracksAroundPovAnchorZ(ServerPlayer viewer, Operation<Double> original) {
		return PovViewRedirect.anchorZ(viewer, original.call(viewer));
	}

	// Nearer of body and agent: the packet also carries the body's knockback, see PovViewRedirect.nearestDistanceSqr.
	@WrapOperation(method = "explode(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/core/particles/ParticleOptions;Lnet/minecraft/util/random/WeightedList;Lnet/minecraft/core/Holder;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D"))
	private double arenaagents$explosionNearPovAnchor(ServerPlayer viewer, Vec3 center, Operation<Double> original) {
		return PovViewRedirect.nearestDistanceSqr(viewer, center, original.call(viewer, center));
	}
}
