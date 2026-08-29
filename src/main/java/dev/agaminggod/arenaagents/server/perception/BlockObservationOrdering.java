package dev.agaminggod.arenaagents.server.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public final class BlockObservationOrdering {
	private static final Comparator<Candidate> ORDER = Comparator
			.comparingLong(Candidate::distanceSquared)
			.thenComparingInt(candidate -> Math.abs(candidate.y()))
			.thenComparingInt(Candidate::y)
			.thenComparingInt(Candidate::x)
			.thenComparingInt(Candidate::z)
			.thenComparing(Candidate::blockId);

	private BlockObservationOrdering() {
	}

	public static List<Candidate> select(List<Candidate> candidates, int maximumEntries, int maximumPerBlockType) {
		return select(candidates, maximumEntries, maximumPerBlockType, candidate -> true);
	}

	public static List<Candidate> select(
			List<Candidate> candidates,
			int maximumEntries,
			int maximumPerBlockType,
			Predicate<Candidate> admissible
	) {
		Objects.requireNonNull(candidates, "candidates must not be null");
		Objects.requireNonNull(admissible, "admissible must not be null");
		if (maximumEntries <= 0 || maximumPerBlockType <= 0) {
			throw new IllegalArgumentException("observation limits must be positive");
		}
		ArrayList<Candidate> ordered = new ArrayList<>(candidates);
		ordered.sort(ORDER);
		ArrayList<Candidate> selected = new ArrayList<>(Math.min(maximumEntries, ordered.size()));
		Map<String, Integer> counts = new HashMap<>();
		for (Candidate candidate : ordered) {
			int count = counts.getOrDefault(candidate.blockId(), 0);
			if (count >= maximumPerBlockType) continue;
			if (!admissible.test(candidate)) continue;
			selected.add(candidate);
			counts.put(candidate.blockId(), count + 1);
			if (selected.size() >= maximumEntries) break;
		}
		return List.copyOf(selected);
	}

	public record Candidate(int x, int y, int z, String blockId) {
		public Candidate {
			blockId = Objects.requireNonNull(blockId, "blockId must not be null");
			if (blockId.isBlank()) throw new IllegalArgumentException("blockId must not be blank");
		}

		private long distanceSquared() {
			long dx = x;
			long dy = y;
			long dz = z;
			return dx * dx + dy * dy + dz * dz;
		}
	}
}
