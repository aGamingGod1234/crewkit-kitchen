package dev.agaminggod.arenaagents.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.agaminggod.arenaagents.server.pov.PovViewRedirect;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Measures entity tracking range from an operator's POV agent instead of its body. The agent is at distance zero, so
 * it is always in range of its own viewers; the visibility check is also forced for it because the client camera
 * sits on that entity. The chunk check ({@code isChunkTracked}) reads the stored view, which is already agent-centred.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class ChunkMapTrackedEntityPovMixin {
	@Shadow
	@Final
	private Entity entity;

	@ModifyExpressionValue(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At(
			value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 arenaagents$measureFromPovAnchor(Vec3 bodyPosition, @Local(argsOnly = true) ServerPlayer viewer) {
		return PovViewRedirect.positionAnchor(viewer, bodyPosition);
	}

	@ModifyExpressionValue(method = "updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At(
			value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;broadcastToPlayer(Lnet/minecraft/server/level/ServerPlayer;)Z"))
	private boolean arenaagents$alwaysSendPovAnchor(boolean visible, @Local(argsOnly = true) ServerPlayer viewer) {
		return visible || PovViewRedirect.isAnchorOf(viewer, entity);
	}
}
