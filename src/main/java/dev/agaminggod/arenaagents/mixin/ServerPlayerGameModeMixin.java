package dev.agaminggod.arenaagents.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records the authoritative world mutation produced by this player's break handler. */
@Mixin(ServerPlayerGameMode.class)
abstract class ServerPlayerGameModeMixin {
	@com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "useItemOn")
	private net.minecraft.world.InteractionResult arenaagents$attributeBlockUse(
			ServerPlayer actor, net.minecraft.world.level.Level level, net.minecraft.world.item.ItemStack stack,
			net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.InteractionResult> original) {
		return dev.agaminggod.arenaagents.server.runtime.BlockUseAttribution.during(actor,
				() -> original.call(actor, level, stack, hand, hit));
	}

	@Shadow
	@Final
	protected ServerPlayer player;

	@Unique
	private BlockPos arenaagents$lastDestroyedBlock;

	@Unique
	private long arenaagents$lastDestroyedGameTime = -1L;

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void arenaagents$recordDestroyedBlock(BlockPos position, CallbackInfoReturnable<Boolean> callback) {
		if (!callback.getReturnValueZ()) return;
		arenaagents$lastDestroyedBlock = position.immutable();
		arenaagents$lastDestroyedGameTime = player.level().getGameTime();
	}
}
