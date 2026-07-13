package dev.agaminggod.arenaagents.client.perception;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservationCollectorVerification {
	private ObservationCollectorVerification() {
	}

	public static void verifyLoadedChunkBoundary() {
		AtomicInteger snapshotReads = new AtomicInteger();
		ObservationCollector.BlockProbe unloadedProbe = new ObservationCollector.BlockProbe() {
			@Override
			public boolean isLoaded(int x, int y, int z) {
				return false;
			}

			@Override
			public BlockSnapshot snapshot(int x, int y, int z, double distanceSquared) {
				snapshotReads.incrementAndGet();
				throw new AssertionError("unloaded block must not be read");
			}
		};

		List<BlockSnapshot> unloaded = ObservationCollector.collectNearbyBlocks(0, 64, 0, 4, unloadedProbe);
		if (!unloaded.isEmpty() || snapshotReads.get() != 0) {
			throw new AssertionError("unloaded chunks must be skipped before block state access");
		}

		ObservationCollector.BlockProbe loadedProbe = new ObservationCollector.BlockProbe() {
			@Override
			public boolean isLoaded(int x, int y, int z) {
				return true;
			}

			@Override
			public BlockSnapshot snapshot(int x, int y, int z, double distanceSquared) {
				return new BlockSnapshot(
						x,
						y,
						z,
						"minecraft:stone",
						"minecraft:empty",
						false,
						true,
						distanceSquared
				);
			}
		};
		List<BlockSnapshot> first = ObservationCollector.collectNearbyBlocks(0, 64, 0, 8, loadedProbe);
		List<BlockSnapshot> second = ObservationCollector.collectNearbyBlocks(0, 64, 0, 8, loadedProbe);
		if (first.size() != ObservationLimits.MAX_BLOCKS || !first.equals(second)) {
			throw new AssertionError("bounded block scan must be capped and deterministic");
		}
		if (first.getFirst().x() != 0 || first.getFirst().y() != 64 || first.getFirst().z() != 0) {
			throw new AssertionError("bounded block scan must visit the nearest position first");
		}
	}
}
