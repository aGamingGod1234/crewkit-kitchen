package dev.agaminggod.arenaagents.world;

/** A loaded chunk's own count of successful block writes, so per-chunk scans know when they are stale. */
public interface ChunkMutationRevisionAccess {
	long arenaagents$chunkMutationRevision();
}
