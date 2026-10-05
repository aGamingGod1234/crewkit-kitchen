package dev.agaminggod.arenaagents.world;

import java.util.LinkedHashMap;

/** Bounded revisions at chunk resolution for local queries and region resolution for wide queries. */
public final class WorldMutationRevisions {
	// A 257-block sight query touches at most sixteen regions; sixteen agents fit the index.
	private static final int REGION_SHIFT = 8;
	private static final int MAXIMUM_RADIUS = 512;
	private static final int CHUNK_SHIFT = 4;
	private static final int CAPACITY = 256;
	private final LinkedHashMap<Long, Long> regions = new LinkedHashMap<>(16, 0.75F, true);
	private final LinkedHashMap<Long, Long> chunks = new LinkedHashMap<>(16, 0.75F, true);
	private long sequence;

	public synchronized long revision(int centerX, int centerZ, int radius) {
		if (radius < 0 || radius >= MAXIMUM_RADIUS) throw new IllegalArgumentException("invalid observation radius");
		// Navigation samples a chunk plus neighboring collision reach (radius 9).
		// Keep wide perception queries coarse without invalidating local searches for distant writes.
		int shift = radius < (1 << CHUNK_SHIFT) ? CHUNK_SHIFT : REGION_SHIFT;
		LinkedHashMap<Long, Long> index = shift == CHUNK_SHIFT ? chunks : regions;
		int minimumX = (int) (((long) centerX - radius) >> shift);
		int maximumX = (int) (((long) centerX + radius) >> shift);
		int minimumZ = (int) (((long) centerZ - radius) >> shift);
		int maximumZ = (int) (((long) centerZ + radius) >> shift);
		long revision = 0L;
		for (int x = minimumX; x <= maximumX; x++) {
			for (int z = minimumZ; z <= maximumZ; z++) {
				long key = key(x, z);
				Long stamp = index.get(key);
				if (stamp == null) {
					// Recreated regions must never revive a cache key from before eviction.
					stamp = ++sequence;
					index.put(key, stamp);
					if (index.size() > CAPACITY) index.pollFirstEntry();
				}
				revision = Math.max(revision, stamp);
			}
		}
		return revision;
	}

	public synchronized void recordMutation(int blockX, int blockZ) {
		recordMutation(regions, key(blockX >> REGION_SHIFT, blockZ >> REGION_SHIFT));
		recordMutation(chunks, key(blockX >> CHUNK_SHIFT, blockZ >> CHUNK_SHIFT));
	}

	private void recordMutation(LinkedHashMap<Long, Long> index, long key) {
		if (index.get(key) != null) index.put(key, ++sequence);
	}

	synchronized int retainedRegions() {
		return regions.size();
	}

	private static long key(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xffffffffL);
	}
}
