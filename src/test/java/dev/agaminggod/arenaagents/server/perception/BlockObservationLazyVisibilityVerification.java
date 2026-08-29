package dev.agaminggod.arenaagents.server.perception;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public final class BlockObservationLazyVisibilityVerification {
	private static final String[] BLOCK_IDS = {
			"minecraft:stone",
			"minecraft:grass_block",
			"minecraft:oak_log",
			"minecraft:coal_ore",
			"minecraft:crafting_table",
			"minecraft:water"
	};

	private BlockObservationLazyVisibilityVerification() {
	}

	public static int verify() {
		int assertions = 0;

		for (long seed = 1L; seed <= 64L; seed++) {
			List<BlockObservationOrdering.Candidate> candidates = candidates(seed);
			Set<BlockObservationOrdering.Candidate> occluded = occluded(candidates, seed);
			Predicate<BlockObservationOrdering.Candidate> visible = candidate -> !occluded.contains(candidate);

			ArrayList<BlockObservationOrdering.Candidate> prefiltered = new ArrayList<>();
			for (BlockObservationOrdering.Candidate candidate : candidates) {
				if (visible.test(candidate)) prefiltered.add(candidate);
			}

			List<BlockObservationOrdering.Candidate> eager = BlockObservationOrdering.select(
					prefiltered, ServerObservationCollector.MAX_BLOCKS,
					ServerObservationCollector.MAX_BLOCKS_PER_TYPE);
			List<BlockObservationOrdering.Candidate> lazy = BlockObservationOrdering.select(
					candidates, ServerObservationCollector.MAX_BLOCKS,
					ServerObservationCollector.MAX_BLOCKS_PER_TYPE, visible);

			assertEquals(eager, lazy, "lazy visibility selection is output-identical (seed " + seed + ")");
			assertEquals(
					lazy,
					BlockObservationOrdering.select(
							candidates.reversed(), ServerObservationCollector.MAX_BLOCKS,
							ServerObservationCollector.MAX_BLOCKS_PER_TYPE, visible),
					"lazy visibility selection is independent of scan order (seed " + seed + ")"
			);
			assertions += 2;
		}

		List<BlockObservationOrdering.Candidate> saturated = new ArrayList<>();
		for (int x = 1; x <= 40; x++) {
			saturated.add(new BlockObservationOrdering.Candidate(x, 0, 0, "minecraft:stone"));
		}
		saturated.add(new BlockObservationOrdering.Candidate(60, 0, 0, "minecraft:oak_log"));

		AtomicInteger eagerChecks = new AtomicInteger();
		BlockObservationOrdering.select(visibleAll(saturated, eagerChecks), 8, 2);
		AtomicInteger lazyChecks = new AtomicInteger();
		List<BlockObservationOrdering.Candidate> lazy = BlockObservationOrdering.select(
				saturated, 8, 2, candidate -> {
					lazyChecks.incrementAndGet();
					return true;
				});

		assertEquals(3, lazy.size(), "per-type exhaustion does not shorten the global selection");
		assertTrue(
				lazy.getLast().blockId().equals("minecraft:oak_log"),
				"the scan continues past an exhausted per-type quota until the global quota is full"
		);
		assertTrue(
				lazyChecks.get() < eagerChecks.get(),
				"lazy selection tests fewer candidates than pre-filtering: "
						+ lazyChecks.get() + " vs " + eagerChecks.get()
		);
		assertions += 3;

		AtomicInteger boundedChecks = new AtomicInteger();
		BlockObservationOrdering.select(saturated, 2, 8, candidate -> {
			boundedChecks.incrementAndGet();
			return true;
		});
		assertEquals(2, boundedChecks.get(), "a satisfied global quota stops visibility testing immediately");
		assertions += 1;

		return assertions;
	}

	private static List<BlockObservationOrdering.Candidate> visibleAll(
			List<BlockObservationOrdering.Candidate> candidates,
			AtomicInteger checks
	) {
		ArrayList<BlockObservationOrdering.Candidate> visible = new ArrayList<>();
		for (BlockObservationOrdering.Candidate candidate : candidates) {
			checks.incrementAndGet();
			visible.add(candidate);
		}
		return visible;
	}

	private static List<BlockObservationOrdering.Candidate> candidates(long seed) {
		Random random = new Random(seed);
		ArrayList<BlockObservationOrdering.Candidate> candidates = new ArrayList<>();
		HashSet<Long> occupied = new HashSet<>();
		int target = 200 + random.nextInt(400);
		while (candidates.size() < target) {
			int x = random.nextInt(13) - 6;
			int y = random.nextInt(7) - 3;
			int z = random.nextInt(13) - 6;
			if (!occupied.add(((long) (x + 8) << 16) | ((long) (y + 8) << 8) | (z + 8))) continue;
			candidates.add(new BlockObservationOrdering.Candidate(
					x, y, z, BLOCK_IDS[random.nextInt(BLOCK_IDS.length)]));
		}
		return List.copyOf(candidates);
	}

	private static Set<BlockObservationOrdering.Candidate> occluded(
			List<BlockObservationOrdering.Candidate> candidates,
			long seed
	) {
		Random random = new Random(seed * 31L + 7L);
		HashSet<BlockObservationOrdering.Candidate> occluded = new HashSet<>();
		for (BlockObservationOrdering.Candidate candidate : candidates) {
			if (random.nextInt(3) != 0) occluded.add(candidate);
		}
		return occluded;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
