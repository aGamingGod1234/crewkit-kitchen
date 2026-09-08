package dev.agaminggod.arenaagents.server.perception;

import java.util.ArrayList;
import java.util.List;

public final class LoadedSightRangeVerification {
	private LoadedSightRangeVerification() { }

	public static int verify() {
		List<String> checked = new ArrayList<>();
		double stopped = LoadedSightRange.distance(1, 1, 1, 0, 256, (x, z) -> {
			checked.add(x + "," + z);
			return x != 2;
		});
		check(stopped > 30.99 && stopped < 31, "ray stops before the unloaded intermediate chunk");
		check(checked.equals(List.of("0,0", "1,0", "2,0")), "ray does not inspect a loaded distant island beyond the gap");
		check(LoadedSightRange.distance(-16, -16, -1, 0, 64, (x, z) -> x >= -1) == 0,
				"negative direction at a boundary checks the immediately adjacent chunk");
		check(LoadedSightRange.distance(2, 2, 0, 0, 128, (x, z) -> true) == 128, "vertical rays remain in their starting chunk");
		checked.clear();
		LoadedSightRange.distance(15.999, 15.9, 1, 0.01, 20, (x, z) -> { checked.add(x + "," + z); return true; });
		check(checked.contains("1,0") && checked.contains("1,1"), "shallow diagonal rays visit both crossed chunk rows");
		check(LoadedSightRange.distance(0, 0, 1, 1, 128, (x, z) -> false) == 0, "unloaded origin never produces a ray");
		return 6;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
