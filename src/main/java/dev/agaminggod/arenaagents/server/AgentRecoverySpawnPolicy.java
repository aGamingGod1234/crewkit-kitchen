package dev.agaminggod.arenaagents.server;

import java.util.Objects;
import java.util.Optional;

final class AgentRecoverySpawnPolicy {
	private static final int MAX_SEARCH_SPAN = 48;

	private AgentRecoverySpawnPolicy() {
	}

	static ChunkPosition chunkContaining(double x, double z) {
		if (!Double.isFinite(x) || !Double.isFinite(z)) {
			throw new IllegalArgumentException("recovery coordinates must be finite");
		}
		long blockX = (long) Math.floor(x);
		long blockZ = (long) Math.floor(z);
		if (blockX < Integer.MIN_VALUE || blockX > Integer.MAX_VALUE
				|| blockZ < Integer.MIN_VALUE || blockZ > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("recovery coordinates exceed supported bounds");
		}
		return new ChunkPosition(((int) blockX) >> 4, ((int) blockZ) >> 4);
	}

	static Optional<Position> selectNearestDryPosition(
			int centerX,
			int centerZ,
			int minX,
			int maxX,
			int minZ,
			int maxZ,
			ColumnLookup columns
	) {
		Objects.requireNonNull(columns, "columns must not be null");
		if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("search bounds must not be inverted");
		long width = (long) maxX - minX + 1L;
		long depth = (long) maxZ - minZ + 1L;
		if (width > MAX_SEARCH_SPAN || depth > MAX_SEARCH_SPAN) {
			throw new IllegalArgumentException("recovery search bounds exceed the supported span");
		}
		Position selected = null;
		long selectedDistance = Long.MAX_VALUE;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				Column column = Objects.requireNonNull(columns.sample(x, z), "column must not be null");
				if (!column.safe()) continue;
				long dx = (long) x - centerX;
				long dz = (long) z - centerZ;
				long distance = dx * dx + dz * dz;
				if (distance < selectedDistance) {
					selected = new Position(x, column.feetY(), z);
					selectedDistance = distance;
				}
			}
		}
		return Optional.ofNullable(selected);
	}

	record Position(int x, int y, int z) {
	}

	record ChunkPosition(int x, int z) {
	}

	record Column(
			int feetY,
			boolean floorSturdy,
			boolean floorStable,
			boolean floorDry,
			boolean feetClear,
			boolean feetDry,
			boolean headClear,
			boolean headDry
	) {
		boolean safe() {
			return floorSturdy && floorStable && floorDry && feetClear && feetDry && headClear && headDry;
		}
	}

	@FunctionalInterface
	interface ColumnLookup {
		Column sample(int x, int z);
	}
}
