package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.world.ChunkMutationRevisionAccess;
import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the actual write, including callers that bypass Level.setBlock. */
@Mixin(LevelChunk.class)
abstract class LevelChunkMutationRevisionMixin implements ChunkMutationRevisionAccess {
	@Unique
	private static final int ARENAAGENTS$MAX_CHANGED_POSITIONS = 4_096;

	@Shadow @Final private Level level;
	@Unique
	private long[] arenaagents$sectionRevisions;
	@Unique
	private LongOpenHashSet arenaagents$changedPositions;
	@Unique
	private boolean arenaagents$changesOverflowed;
	@Unique
	private boolean arenaagents$postProcessing;

	@Inject(method = "setBlockState", at = @At("RETURN"))
	private void arenaagents$recordBlockMutation(
			BlockPos position, BlockState state, int flags,
			CallbackInfoReturnable<BlockState> callback
	) {
		if (callback.getReturnValue() != null) {
			LevelChunk self = (LevelChunk) (Object) this;
			int section = self.getSectionIndex(position.getY());
			if (arenaagents$sectionRevisions == null) arenaagents$sectionRevisions = new long[self.getSections().length];
			if (section >= 0 && section < arenaagents$sectionRevisions.length) arenaagents$sectionRevisions[section]++;
			// Generation's own fix-ups (fences joining, stairs shaping) are not changes made after the world was generated.
			if (!arenaagents$postProcessing && !arenaagents$changesOverflowed) {
				if (arenaagents$changedPositions == null) arenaagents$changedPositions = new LongOpenHashSet();
				arenaagents$changedPositions.add(position.asLong());
				if (arenaagents$changedPositions.size() > ARENAAGENTS$MAX_CHANGED_POSITIONS) {
					arenaagents$changedPositions = null;
					arenaagents$changesOverflowed = true;
				}
			}
			((WorldMutationRevisionAccess) level).arenaagents$recordWorldMutation(position);
		}
	}

	@Inject(method = "postProcessGeneration", at = @At("HEAD"))
	private void arenaagents$beginPostProcessing(ServerLevel level, CallbackInfo callback) {
		arenaagents$postProcessing = true;
	}

	@Inject(method = "postProcessGeneration", at = @At("RETURN"))
	private void arenaagents$endPostProcessing(ServerLevel level, CallbackInfo callback) {
		arenaagents$postProcessing = false;
	}

	@Override
	public long arenaagents$sectionMutationRevision(int sectionIndex) {
		long[] revisions = arenaagents$sectionRevisions;
		return revisions == null || sectionIndex < 0 || sectionIndex >= revisions.length ? 0L : revisions[sectionIndex];
	}

	@Override
	public boolean arenaagents$changedSinceLoad(BlockPos position) {
		if (arenaagents$changesOverflowed) return true;
		LongOpenHashSet changed = arenaagents$changedPositions;
		return changed != null && changed.contains(position.asLong());
	}
}
