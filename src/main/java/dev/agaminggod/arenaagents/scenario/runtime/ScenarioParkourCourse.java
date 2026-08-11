package dev.agaminggod.arenaagents.scenario.runtime;

import java.util.ArrayList;
import java.util.List;

public record ScenarioParkourCourse(List<Lane> lanes) {
	public ScenarioParkourCourse {
		lanes = List.copyOf(lanes);
	}

	public static ScenarioParkourCourse create() {
		ArrayList<Lane> lanes = new ArrayList<>(16);
		for (int laneIndex = 0; laneIndex < 16; laneIndex++) {
			int x = -23 + laneIndex * 3;
			int y = 1;
			int z = -24;
			ArrayList<Platform> platforms = new ArrayList<>(33);
			platforms.add(new Platform(x, y, z));
			for (int transition = 1; transition <= 32; transition++) {
				if ((transition & 1) == 1) {
					z += 2;
				} else {
					z += 1;
					y += 1;
				}
				platforms.add(new Platform(x, y, z));
			}
			lanes.add(new Lane(laneIndex, platforms));
		}
		return new ScenarioParkourCourse(lanes);
	}

	public record Lane(int index, List<Platform> platforms) {
		public Lane {
			if (index < 0 || index >= 16) {
				throw new IllegalArgumentException("parkour lane index must be in [0, 15]");
			}
			platforms = List.copyOf(platforms);
			if (platforms.isEmpty()) {
				throw new IllegalArgumentException("parkour lane must have platforms");
			}
		}
	}

	public record Platform(int x, int y, int z) {
	}
}
