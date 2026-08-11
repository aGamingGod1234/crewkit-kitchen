package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.scenario.runtime.ScenarioParkourCourse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

public final class ScenarioSpawnAllocator {
	public List<ScenarioSpawn> allocate(ScenarioSessionConfig config) {
		List<ScenarioParticipant> participants = config.participants().stream()
				.sorted(Comparator.comparing(ScenarioParticipant::id))
				.toList();
		if (config.preset().category() == ScenarioCategory.BUILDING) {
			return allocateBuildingPlots(config, participants);
		}
		if (config.preset().category() == ScenarioCategory.PARKOUR) {
			return allocateParkourLanes(config, participants);
		}
		int count = participants.size();
		int rotation = Math.floorMod(config.worldSeed(), count);
		int direction = (config.worldSeed() & 1L) == 0L ? 1 : -1;
		double radius = radius(config.preset().category());
		double angularOffset = Math.floorMod(config.worldSeed() * 47L, 360L);
		ArrayList<ScenarioSpawn> allocations = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			int slot = Math.floorMod(rotation + (direction * index), count);
			double angle = angularOffset + (360.0D * slot / count);
			double radians = Math.toRadians(angle);
			ScenarioParticipant participant = participants.get(index);
			allocations.add(new ScenarioSpawn(
					participant.id(),
					slot,
					lanePrefix(config.preset().category()) + slot,
					round(Math.cos(radians) * radius),
					64.0D,
					round(Math.sin(radians) * radius),
					(float) normalizeYaw(angle + 180.0D),
					participant.team()
			));
		}
		return List.copyOf(allocations);
	}

	private static List<ScenarioSpawn> allocateParkourLanes(
			ScenarioSessionConfig config,
			List<ScenarioParticipant> participants
	) {
		ArrayList<ScenarioParkourCourse.Lane> lanes = new ArrayList<>(ScenarioParkourCourse.create().lanes());
		SplittableRandom random = new SplittableRandom(config.worldSeed());
		for (int index = lanes.size() - 1; index > 0; index--) {
			int swapIndex = random.nextInt(index + 1);
			ScenarioParkourCourse.Lane value = lanes.get(index);
			lanes.set(index, lanes.get(swapIndex));
			lanes.set(swapIndex, value);
		}
		ArrayList<ScenarioSpawn> allocations = new ArrayList<>(participants.size());
		for (int index = 0; index < participants.size(); index++) {
			ScenarioParticipant participant = participants.get(index);
			ScenarioParkourCourse.Lane lane = lanes.get(index);
			ScenarioParkourCourse.Platform start = lane.platforms().getFirst();
			allocations.add(new ScenarioSpawn(
					participant.id(),
					lane.index(),
					"lane-" + lane.index(),
					start.x(),
					64.0D,
					start.z(),
					0.0F,
					participant.team()
			));
		}
		return List.copyOf(allocations);
	}

	private static List<ScenarioSpawn> allocateBuildingPlots(
			ScenarioSessionConfig config,
			List<ScenarioParticipant> participants
	) {
		ArrayList<double[]> plots = new ArrayList<>(16);
		for (double x : new double[]{-36.0D, -12.0D, 12.0D, 36.0D}) {
			for (double z : new double[]{-36.0D, -12.0D, 12.0D, 36.0D}) {
				plots.add(new double[]{x, z});
			}
		}
		SplittableRandom random = new SplittableRandom(config.worldSeed());
		for (int index = plots.size() - 1; index > 0; index--) {
			int swapIndex = random.nextInt(index + 1);
			double[] value = plots.get(index);
			plots.set(index, plots.get(swapIndex));
			plots.set(swapIndex, value);
		}
		ArrayList<ScenarioSpawn> allocations = new ArrayList<>(participants.size());
		for (int index = 0; index < participants.size(); index++) {
			ScenarioParticipant participant = participants.get(index);
			double[] plot = plots.get(index);
			double angle = Math.toDegrees(Math.atan2(plot[1], plot[0]));
			allocations.add(new ScenarioSpawn(
					participant.id(),
					index,
					"plot-" + index,
					plot[0],
					64.0D,
					plot[1],
					(float) normalizeYaw(angle + 180.0D),
					participant.team()
			));
		}
		return List.copyOf(allocations);
	}

	private static double radius(ScenarioCategory category) {
		return switch (category) {
			case SURVIVAL -> 54.0D;
			case BUILDING -> 36.0D;
			case PVP -> 46.0D;
			case PARKOUR -> 12.0D;
		};
	}

	private static String lanePrefix(ScenarioCategory category) {
		return switch (category) {
			case SURVIVAL -> "sector-";
			case BUILDING -> "plot-";
			case PVP -> "start-";
			case PARKOUR -> "lane-";
		};
	}

	private static double normalizeYaw(double angle) {
		double normalized = angle % 360.0D;
		return normalized > 180.0D ? normalized - 360.0D : normalized;
	}

	private static double round(double value) {
		return Math.rint(value * 1_000.0D) / 1_000.0D;
	}
}
