package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Comparator;

/** Server-authored factual requirements that a translated predicate may not weaken. */
public record GoalTranslationConstraint(List<KillClause> killClauses, List<ItemClause> itemClauses,
		List<GoalPredicate> factualPredicates) {
	private static final int MAX_LEAVES = 16;
	private static final GoalTranslationConstraint NONE = new GoalTranslationConstraint(List.of(), List.of());

	public GoalTranslationConstraint {
		killClauses = List.copyOf(Objects.requireNonNull(killClauses, "killClauses must not be null"));
		itemClauses = List.copyOf(Objects.requireNonNull(itemClauses, "itemClauses must not be null"));
		factualPredicates = List.copyOf(Objects.requireNonNull(factualPredicates, "factualPredicates must not be null"));
		for (GoalPredicate fact : factualPredicates) {
			if (!(fact instanceof GoalPredicate.PositionWithin) && !(fact instanceof GoalPredicate.SurviveDuration)) {
				throw new IllegalArgumentException("Only position and survival requirements are supported");
			}
		}
		int leaves = factualPredicates.size();
		for (KillClause clause : killClauses) {
			Objects.requireNonNull(clause, "kill clause must not be null");
			for (KillAlternative alternative : clause.alternatives()) {
				leaves = Math.addExact(leaves, alternative.count());
			}
		}
		for (ItemClause clause : itemClauses) {
			Objects.requireNonNull(clause, "item clause must not be null");
			leaves = Math.addExact(leaves, clause.alternatives().size());
		}
		if (leaves > MAX_LEAVES) throw new IllegalArgumentException("translation constraints may require at most 16 leaves");
	}

	public GoalTranslationConstraint(List<KillClause> killClauses, List<ItemClause> itemClauses) {
		this(killClauses, itemClauses, List.of());
	}

	/** Compatibility constructor for constraints persisted before item quantities were captured. */
	public GoalTranslationConstraint(List<KillClause> killClauses) {
		this(killClauses, List.of());
	}

	public static GoalTranslationConstraint none() {
		return NONE;
	}

	/** A new translation request must offer every identifier needed by its factual constraints. */
	public void requireCatalog(List<String> candidateIds) {
		Set<String> offered = Set.copyOf(candidateIds);
		if (candidateIds.size() > 64 || offered.size() != candidateIds.size()) {
			throw new AgentDomainException("GOAL_TRANSLATION_CATALOG_TOO_BROAD", "Translation catalog must contain at most 64 unique identifiers");
		}
		boolean complete = killClauses.stream().flatMap(clause -> clause.alternatives().stream())
				.allMatch(alternative -> !alternative.entityTypes().isEmpty() && offered.containsAll(alternative.entityTypes()))
				&& itemClauses.stream().flatMap(clause -> clause.alternatives().stream())
						.allMatch(alternative -> !alternative.itemIds().isEmpty() && offered.containsAll(alternative.itemIds()));
		if (!complete) throw new AgentDomainException("GOAL_TRANSLATION_CATALOG_MISMATCH",
				"Translation catalog omits a required item or entity identifier; specify a narrower goal");
	}

	public void validate(GoalPredicate predicate) {
		validate(predicate, GoalPredicate.DEFAULT_DIMENSION);
	}

	/** Coordinates in request-derived constraints are relative to the draft's captured dimension. */
	public void validate(GoalPredicate predicate, String dimensionId) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		Objects.requireNonNull(dimensionId, "dimensionId must not be null");
		for (GoalPredicate fact : factualPredicates) {
			if (!provesFact(predicate, fact, dimensionId)) {
				throw new AgentDomainException("GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						"Translated predicate must preserve every requested position, radius, stability and survival duration on every completion path");
			}
		}
		if (killClauses.isEmpty() && itemClauses.isEmpty()) return;
		for (EvidencePath path : evidencePaths(predicate)) {
			if (!satisfiesKillClauses(path.kills())
					|| !satisfiesItemClauses(disjointInventoryEvidence(path.items()))) {
				throw new AgentDomainException(
						"GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						"Translated predicate does not prove every requested item or kill count; overlapping inventory groups may need a more specific predicate"
				);
			}
		}
	}

	private static boolean provesFact(GoalPredicate predicate, GoalPredicate required, String dimensionId) {
		if (predicate instanceof GoalPredicate.AllOf all) {
			return all.predicates().stream().anyMatch(child -> provesFact(child, required, dimensionId));
		}
		if (predicate instanceof GoalPredicate.AnyOf any) {
			return any.predicates().stream().allMatch(child -> provesFact(child, required, dimensionId));
		}
		if (required instanceof GoalPredicate.PositionWithin expected && predicate instanceof GoalPredicate.PositionWithin actual) {
			return actual.dimensionId().equals(dimensionId)
					&& actual.x() == expected.x() && actual.y() == expected.y() && actual.z() == expected.z()
					&& actual.radius() <= expected.radius() && actual.stableTicks() >= expected.stableTicks();
		}
		return required instanceof GoalPredicate.SurviveDuration expected
				&& predicate instanceof GoalPredicate.SurviveDuration actual && actual.ticks() >= expected.ticks();
	}

	private boolean satisfiesKillClauses(List<String> kills) {
		HashMap<String, Integer> counts = new HashMap<>();
		kills.forEach(id -> counts.merge(id, 1, Integer::sum));
		List<InventoryEvidence> evidence = counts.entrySet().stream()
				.map(entry -> new InventoryEvidence(Set.of(entry.getKey()), entry.getValue())).toList();
		List<List<AllocationRequirement>> clauses = killClauses.stream().map(clause -> clause.alternatives().stream()
				.map(value -> new AllocationRequirement(Set.copyOf(value.entityTypes()), value.count())).toList()).toList();
		return selectAlternatives(clauses, evidence, 0, new ArrayList<>());
	}

	private boolean satisfiesItemClauses(List<InventoryEvidence> evidence) {
		List<List<AllocationRequirement>> clauses = itemClauses.stream().map(clause -> clause.alternatives().stream()
				.map(value -> new AllocationRequirement(Set.copyOf(value.itemIds()), value.count())).toList()).toList();
		return selectAlternatives(clauses, evidence, 0, new ArrayList<>());
	}

	private static boolean selectAlternatives(List<List<AllocationRequirement>> clauses,
			List<InventoryEvidence> evidence, int index, ArrayList<AllocationRequirement> selected) {
		if (index == clauses.size()) return hasAllocation(evidence, selected);
		// Only explicit alternatives branch. Interchangeable evidence is matched by capacity,
		// never permuted, and different alternatives of one clause cannot mix their quotas.
		for (AllocationRequirement alternative : clauses.get(index)) {
			selected.add(alternative);
			boolean satisfied = selectAlternatives(clauses, evidence, index + 1, selected);
			selected.removeLast();
			if (satisfied) return true;
		}
		return false;
	}

	/** Integral max flow permits reassignment without consuming the same evidence twice. */
	private static boolean hasAllocation(List<InventoryEvidence> evidence, List<AllocationRequirement> requirements) {
		int sink = evidence.size() + requirements.size() + 1;
		long[][] residual = new long[sink + 1][sink + 1];
		long needed = 0;
		for (int i = 0; i < evidence.size(); i++) residual[0][i + 1] = evidence.get(i).count();
		for (int r = 0; r < requirements.size(); r++) {
			AllocationRequirement requirement = requirements.get(r);
			int node = evidence.size() + r + 1;
			residual[node][sink] = requirement.count();
			needed += requirement.count();
			for (int i = 0; i < evidence.size(); i++) {
				InventoryEvidence source = evidence.get(i);
				if (requirement.ids().containsAll(source.itemIds())) residual[i + 1][node] = source.count();
			}
		}
		long allocated = 0;
		while (allocated < needed) {
			int[] parent = new int[sink + 1];
			java.util.Arrays.fill(parent, -1);
			parent[0] = 0;
			int[] queue = new int[sink + 1];
			int head = 0, tail = 1;
			while (head < tail && parent[sink] == -1) {
				int node = queue[head++];
				for (int next = 1; next <= sink; next++) {
					if (parent[next] == -1 && residual[node][next] > 0) {
						parent[next] = node;
						queue[tail++] = next;
					}
				}
			}
			if (parent[sink] == -1) return false;
			long amount = needed - allocated;
			for (int node = sink; node != 0; node = parent[node]) amount = Math.min(amount, residual[parent[node]][node]);
			for (int node = sink; node != 0; node = parent[node]) {
				residual[parent[node]][node] -= amount;
				residual[node][parent[node]] += amount;
			}
			allocated += amount;
		}
		return true;
	}

	private record AllocationRequirement(Set<String> ids, int count) {}

	/** Disjoint guarantees can add; overlapping guarantees cannot prove independent inventory counts. */
	private static List<InventoryEvidence> disjointInventoryEvidence(List<InventoryEvidence> evidence) {
		HashMap<Set<String>, Integer> strongest = new HashMap<>();
		evidence.forEach(value -> strongest.merge(value.itemIds(), value.count(), Math::max));
		List<InventoryEvidence> ordered = strongest.entrySet().stream()
				.map(entry -> new InventoryEvidence(entry.getKey(), entry.getValue()))
				.sorted(Comparator.comparingInt((InventoryEvidence value) -> value.itemIds().size())
						.thenComparing(Comparator.comparingInt(InventoryEvidence::count).reversed())
						.thenComparing(value -> String.join(",", new java.util.TreeSet<>(value.itemIds())))).toList();
		ArrayList<InventoryEvidence> independent = new ArrayList<>();
		HashSet<String> claimedIds = new HashSet<>();
		for (InventoryEvidence value : ordered) {
			if (value.itemIds().stream().anyMatch(claimedIds::contains)) continue;
			independent.add(value);
			claimedIds.addAll(value.itemIds());
		}
		return List.copyOf(independent);
	}

	private static List<EvidencePath> evidencePaths(GoalPredicate predicate) {
		return switch (predicate) {
			case GoalPredicate.EntityKilledByAgent value -> List.of(
					new EvidencePath(List.of(value.entityType()), List.of()));
			case GoalPredicate.InventoryContains value -> List.of(
					new EvidencePath(List.of(), List.of(new InventoryEvidence(Set.of(value.itemId()), value.count()))));
			case GoalPredicate.InventoryContainsAny value -> List.of(
					new EvidencePath(List.of(), List.of(new InventoryEvidence(Set.copyOf(value.itemIds()), value.count()))));
			case GoalPredicate.AllOf value -> allOfPaths(value.predicates());
			case GoalPredicate.AnyOf value -> value.predicates().stream()
					.flatMap(child -> evidencePaths(child).stream())
					.toList();
			default -> List.of(new EvidencePath(List.of(), List.of()));
		};
	}

	private static List<EvidencePath> allOfPaths(List<GoalPredicate> predicates) {
		List<EvidencePath> paths = List.of(new EvidencePath(List.of(), List.of()));
		for (GoalPredicate predicate : predicates) {
			ArrayList<EvidencePath> combined = new ArrayList<>();
			for (EvidencePath left : paths) {
				for (EvidencePath right : evidencePaths(predicate)) {
					ArrayList<String> kills = new ArrayList<>(left.kills().size() + right.kills().size());
					kills.addAll(left.kills());
					kills.addAll(right.kills());
					ArrayList<InventoryEvidence> items = new ArrayList<>(left.items());
					items.addAll(right.items());
					combined.add(new EvidencePath(List.copyOf(kills), List.copyOf(items)));
				}
			}
			paths = List.copyOf(combined);
		}
		return paths;
	}

	private record InventoryEvidence(Set<String> itemIds, int count) {}

	private record EvidencePath(List<String> kills, List<InventoryEvidence> items) {
	}

	public record KillClause(List<KillAlternative> alternatives) {
		public KillClause {
			alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives must not be null"));
			if (alternatives.isEmpty()) throw new IllegalArgumentException("kill clause alternatives must not be empty");
			alternatives.forEach(value -> Objects.requireNonNull(value, "kill alternative must not be null"));
		}
	}

	public record KillAlternative(List<String> entityTypes, int count) {
		public KillAlternative {
			entityTypes = List.copyOf(Objects.requireNonNull(entityTypes, "entityTypes must not be null"));
			if (entityTypes.size() > 64 || new HashSet<>(entityTypes).size() != entityTypes.size()) {
				throw new IllegalArgumentException("entityTypes must contain at most 64 unique identifiers");
			}
			for (String entityType : entityTypes) {
				if (entityType == null || !entityType.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
					throw new IllegalArgumentException("entityTypes must contain namespaced identifiers");
				}
			}
			if (count <= 0 || count > MAX_LEAVES) {
				throw new IllegalArgumentException("kill alternative count must be between 1 and 16");
			}
		}
	}

	public record ItemClause(List<ItemAlternative> alternatives) {
		public ItemClause {
			alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives must not be null"));
			if (alternatives.isEmpty()) throw new IllegalArgumentException("item clause alternatives must not be empty");
			alternatives.forEach(value -> Objects.requireNonNull(value, "item alternative must not be null"));
		}
	}

	public record ItemAlternative(List<String> itemIds, int count) {
		public ItemAlternative {
			itemIds = List.copyOf(Objects.requireNonNull(itemIds, "itemIds must not be null"));
			if (itemIds.size() > 64 || new HashSet<>(itemIds).size() != itemIds.size()) {
				throw new IllegalArgumentException("itemIds must contain at most 64 unique identifiers");
			}
			for (String itemId : itemIds) {
				if (itemId == null || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
					throw new IllegalArgumentException("itemIds must contain namespaced identifiers");
				}
			}
			if (count <= 0) throw new IllegalArgumentException("item alternative count must be positive");
		}
	}
}
