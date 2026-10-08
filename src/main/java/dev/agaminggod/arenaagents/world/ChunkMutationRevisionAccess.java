package dev.agaminggod.arenaagents.world;

import net.minecraft.core.BlockPos;

/**
 * A loaded chunk's own record of block writes after generation: a write count per section, so per-section scans know
 * when they are stale, and the positions written since the chunk loaded (bounded; past the bound every position counts
 * as changed), so a structure is never named from a block placed into it later.
 */
public interface ChunkMutationRevisionAccess {
	long arenaagents$sectionMutationRevision(int sectionIndex);

	boolean arenaagents$changedSinceLoad(BlockPos position);
}
