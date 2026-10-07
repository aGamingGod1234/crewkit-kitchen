package dev.agaminggod.arenaagents.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Gives Carpet fake players server-side fall damage again.
 *
 * <p>Entity.move only calls checkFallDamage when the instance is locally authoritative, and Player reports
 * itself as client-authoritative, so on the server a player's fall distance only comes from the client's
 * movement packet. A Carpet fake player has no client: its physics run in Entity.move on the server, so the
 * check never ran and every fall was free. The server is the authority for these bodies, so open just this
 * one gate for them; the rest of the player's authority model is left unchanged.
 */
@Mixin(Entity.class)
abstract class FakePlayerFallAuthorityMixin {
	@WrapOperation(
			method = "move",
			slice = @Slice(
					from = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getOnPosLegacy()Lnet/minecraft/core/BlockPos;"),
					to = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;checkFallDamage(DZLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V")
			),
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isLocalInstanceAuthoritative()Z")
	)
	private boolean arenaagents$serverOwnsFakePlayerFalls(Entity entity, Operation<Boolean> original) {
		return original.call(entity) || entity instanceof EntityPlayerMPFake && !entity.level().isClientSide();
	}
}
