package dev.agaminggod.arenaagents.server.perception;

import java.util.function.BiPredicate;

/** Visits each crossed chunk before a sight ray can ask Minecraft for any blocks in it. */
final class LoadedSightRange {
	private LoadedSightRange() { }

	static double distance(double x, double z, double dx, double dz, double maximum, BiPredicate<Integer, Integer> loaded) {
		int chunkX = (int) Math.floor(x / 16.0D);
		int chunkZ = (int) Math.floor(z / 16.0D);
		int stepX = Double.compare(dx, 0.0D);
		int stepZ = Double.compare(dz, 0.0D);
		double deltaX = dx == 0.0D ? Double.POSITIVE_INFINITY : 16.0D / Math.abs(dx);
		double deltaZ = dz == 0.0D ? Double.POSITIVE_INFINITY : 16.0D / Math.abs(dz);
		double nextX = dx == 0.0D ? Double.POSITIVE_INFINITY : ((chunkX + (stepX > 0 ? 1 : 0)) * 16.0D - x) / dx;
		double nextZ = dz == 0.0D ? Double.POSITIVE_INFINITY : ((chunkZ + (stepZ > 0 ? 1 : 0)) * 16.0D - z) / dz;
		double enteredAt = 0.0D;
		while (enteredAt <= maximum) {
			if (!loaded.test(chunkX, chunkZ)) return Math.max(0.0D, enteredAt - 0.001D);
			double crossing = Math.min(nextX, nextZ);
			if (crossing > maximum) return maximum;
			if (nextX == nextZ && (!loaded.test(chunkX + stepX, chunkZ) || !loaded.test(chunkX, chunkZ + stepZ))) {
				return Math.max(0.0D, crossing - 0.001D);
			}
			if (nextX <= crossing) { chunkX += stepX; nextX += deltaX; }
			if (nextZ <= crossing) { chunkZ += stepZ; nextZ += deltaZ; }
			enteredAt = crossing;
		}
		return maximum;
	}
}
