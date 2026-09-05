package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Invalidates cached perception samples when a level successfully changes a block. */
@Mixin(Level.class)
abstract class LevelMutationRevisionMixin implements WorldMutationRevisionAccess {
	@Unique
	private long arenaagents$worldMutationRevision;

	@Unique
	private long arenaagents$lastMutationGameTime = Long.MIN_VALUE;

	@Inject(
			method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At("RETURN")
	)
	private void arenaagents$recordBlockMutation(
			BlockPos position,
			BlockState state,
			int flags,
			int recursionLeft,
			CallbackInfoReturnable<Boolean> callback
	) {
		if (!callback.getReturnValueZ()) return;
		long gameTime = ((Level) (Object) this).getGameTime();
		if (gameTime == arenaagents$lastMutationGameTime) return;
		arenaagents$lastMutationGameTime = gameTime;
		arenaagents$worldMutationRevision++;
	}

	@Override
	public long arenaagents$worldMutationRevision() {
		return arenaagents$worldMutationRevision;
	}
}
