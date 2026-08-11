package dev.agaminggod.arenaagents.client.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class LocalPathfinder implements PathPlanner {
	public static final int MAX_EXPANDED_NODES = 8_192;
	public static final long MAX_PLANNING_TIME_NANOS = TimeUnit.MILLISECONDS.toNanos(40L);
	public static final int MAX_DROP_BLOCKS = 3;

	private static final int WALK_COST = 10;
	private static final int JUMP_UP_COST = 14;
	private static final int JUMP_GAP_COST = 18;
	private static final int DROP_BASE_COST = 11;
	private static final int DROP_PER_BLOCK_COST = 1;
	private static final int HEIGHT_HEURISTIC_COST = 1;
	private static final int[][] CARDINAL_OFFSETS = {
		{1, 0},
		{0, 1},
		{-1, 0},
		{0, -1}
	};
	private static final Comparator<SearchNode> OPEN_ORDER = Comparator
			.comparingLong(SearchNode::estimatedTotalCost)
			.thenComparingLong(SearchNode::heuristicCost)
			.thenComparingLong(SearchNode::pathCost)
			.thenComparingLong(SearchNode::sequence)
			.thenComparingInt(node -> node.position().x())
			.thenComparingInt(node -> node.position().y())
			.thenComparingInt(node -> node.position().z());

	@Override
	public PathPlan findPath(
			WalkabilityView view,
			GridPosition start,
			GridPosition destination
	) {
		return findPath(
				view,
				start,
				destination,
				MAX_EXPANDED_NODES,
				MAX_PLANNING_TIME_NANOS,
				System::nanoTime
		);
	}

	public PathPlan findPath(
			WalkabilityView view,
			GridPosition start,
			GridPosition destination,
			int maximumExpandedNodes,
			long timeBudgetNanos,
			LongSupplier monotonicClock
	) {
		if (view == null || start == null || destination == null || monotonicClock == null
				|| maximumExpandedNodes <= 0 || timeBudgetNanos <= 0L) {
			return PathPlan.failed(PathOutcome.INVALID, 0);
		}
		if (!isStandable(view, start) || !isStandable(view, destination)) {
			return PathPlan.failed(PathOutcome.INVALID, 0);
		}
		if (start.equals(destination)) {
			return new PathPlan(List.of(new PathNode(start, TraversalType.START)), PathOutcome.FOUND, 0);
		}

		long startedAtNanos = monotonicClock.getAsLong();
		PriorityQueue<SearchNode> open = new PriorityQueue<>(OPEN_ORDER);
		Map<GridPosition, Long> bestCosts = new HashMap<>();
		Map<GridPosition, ParentEdge> parents = new HashMap<>();
		Set<GridPosition> closed = new HashSet<>();
		long nextSequence = 0L;
		long startHeuristic = heuristic(start, destination);
		open.add(new SearchNode(start, 0L, startHeuristic, nextSequence++));
		bestCosts.put(start, 0L);
		int expandedNodes = 0;

		while (!open.isEmpty()) {
			if (deadlineReached(startedAtNanos, monotonicClock.getAsLong(), timeBudgetNanos)) {
				return PathPlan.failed(PathOutcome.TIME_LIMIT, expandedNodes);
			}
			SearchNode current = open.remove();
			Long currentBestCost = bestCosts.get(current.position());
			if (currentBestCost == null || current.pathCost() != currentBestCost || closed.contains(current.position())) {
				continue;
			}
			if (current.position().equals(destination)) {
				return reconstruct(start, destination, parents, expandedNodes);
			}
			if (expandedNodes >= maximumExpandedNodes) {
				return PathPlan.failed(PathOutcome.NODE_LIMIT, expandedNodes);
			}
			closed.add(current.position());
			expandedNodes++;

			for (Neighbor neighbor : neighbors(view, current.position())) {
				if (closed.contains(neighbor.position())) {
					continue;
				}
				long candidateCost = saturatedAdd(current.pathCost(), neighbor.cost());
				long knownCost = bestCosts.getOrDefault(neighbor.position(), Long.MAX_VALUE);
				if (candidateCost >= knownCost) {
					continue;
				}
				bestCosts.put(neighbor.position(), candidateCost);
				parents.put(
						neighbor.position(),
						new ParentEdge(current.position(), neighbor.traversal())
				);
				open.add(new SearchNode(
						neighbor.position(),
						candidateCost,
						heuristic(neighbor.position(), destination),
						nextSequence++
				));
			}
		}

		return PathPlan.failed(PathOutcome.NO_PATH, expandedNodes);
	}

	private static List<Neighbor> neighbors(WalkabilityView view, GridPosition current) {
		List<Neighbor> neighbors = new ArrayList<>(CARDINAL_OFFSETS.length);
		for (int[] offset : CARDINAL_OFFSETS) {
			GridPosition sameLevel;
			try {
				sameLevel = current.offset(offset[0], 0, offset[1]);
			} catch (ArithmeticException exception) {
				continue;
			}
			Neighbor neighbor = resolveNeighbor(view, current, sameLevel);
			if (neighbor != null) {
				neighbors.add(neighbor);
			}
		}
		return neighbors;
	}

	private static Neighbor resolveNeighbor(
			WalkabilityView view,
			GridPosition current,
			GridPosition sameLevel
	) {
		if (isStandable(view, sameLevel)) {
			return new Neighbor(sameLevel, TraversalType.WALK, WALK_COST);
		}

		GridPosition jumpDestination;
		try {
			jumpDestination = sameLevel.above();
		} catch (ArithmeticException exception) {
			return null;
		}
		if (isStandable(view, jumpDestination) && isClear(view, current.above(2))) {
			return new Neighbor(jumpDestination, TraversalType.JUMP_UP, JUMP_UP_COST);
		}

		GridPosition gapLanding;
		try {
			int dx = sameLevel.x() - current.x();
			int dz = sameLevel.z() - current.z();
			gapLanding = sameLevel.offset(dx, 0, dz);
		} catch (ArithmeticException exception) {
			return null;
		}
		if (isClear(view, sameLevel)
				&& isClear(view, sameLevel.above())
				&& isClear(view, current.above(2))
				&& isStandable(view, gapLanding)) {
			return new Neighbor(gapLanding, TraversalType.JUMP_GAP, JUMP_GAP_COST);
		}

		for (int drop = 1; drop <= MAX_DROP_BLOCKS; drop++) {
			GridPosition landing;
			try {
				landing = sameLevel.below(drop);
			} catch (ArithmeticException exception) {
				return null;
			}
			if (!isStandable(view, landing)) {
				continue;
			}
			if (isClearDropShaft(view, landing, current.y())) {
				return new Neighbor(
						landing,
						TraversalType.DROP_DOWN,
						DROP_BASE_COST + DROP_PER_BLOCK_COST * drop
				);
			}
		}
		return null;
	}

	private static boolean isClearDropShaft(
			WalkabilityView view,
			GridPosition landing,
			int sourceFeetY
	) {
		for (int y = landing.y(); y <= sourceFeetY + 1; y++) {
			if (!isClear(view, new GridPosition(landing.x(), y, landing.z()))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isStandable(WalkabilityView view, GridPosition position) {
		try {
			return view.isStandable(position);
		} catch (ArithmeticException exception) {
			return false;
		}
	}

	private static boolean isClear(WalkabilityView view, GridPosition position) {
		return view.cellAt(position) == WalkabilityView.Cell.CLEAR;
	}

	private static long heuristic(GridPosition position, GridPosition destination) {
		long horizontalDistance = absoluteDifference(position.x(), destination.x())
				+ absoluteDifference(position.z(), destination.z());
		long heightDistance = absoluteDifference(position.y(), destination.y());
		return saturatedAdd(
				saturatedMultiply(horizontalDistance, WALK_COST),
				saturatedMultiply(heightDistance, HEIGHT_HEURISTIC_COST)
		);
	}

	private static PathPlan reconstruct(
			GridPosition start,
			GridPosition destination,
			Map<GridPosition, ParentEdge> parents,
			int expandedNodes
	) {
		LinkedList<PathNode> nodes = new LinkedList<>();
		Set<GridPosition> visited = new HashSet<>();
		GridPosition current = destination;
		while (!current.equals(start)) {
			if (!visited.add(current)) {
				return PathPlan.failed(PathOutcome.INVALID, expandedNodes);
			}
			ParentEdge edge = parents.get(current);
			if (edge == null) {
				return PathPlan.failed(PathOutcome.INVALID, expandedNodes);
			}
			nodes.addFirst(new PathNode(current, edge.traversal()));
			current = edge.parent();
		}
		nodes.addFirst(new PathNode(start, TraversalType.START));
		return new PathPlan(nodes, PathOutcome.FOUND, expandedNodes);
	}

	private static boolean deadlineReached(long start, long now, long budget) {
		return now - start >= budget;
	}

	private static long absoluteDifference(int first, int second) {
		return Math.abs((long) first - second);
	}

	private static long saturatedAdd(long first, long second) {
		try {
			return Math.addExact(first, second);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	private static long saturatedMultiply(long value, long multiplier) {
		try {
			return Math.multiplyExact(value, multiplier);
		} catch (ArithmeticException exception) {
			return Long.MAX_VALUE;
		}
	}

	private record SearchNode(
			GridPosition position,
			long pathCost,
			long heuristicCost,
			long sequence
	) {
		private SearchNode {
			Objects.requireNonNull(position, "position must not be null");
		}

		private long estimatedTotalCost() {
			return saturatedAdd(pathCost, heuristicCost);
		}
	}

	private record ParentEdge(GridPosition parent, TraversalType traversal) {
	}

	private record Neighbor(GridPosition position, TraversalType traversal, int cost) {
	}
}
