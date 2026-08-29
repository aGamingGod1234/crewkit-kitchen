package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Server-authored factual requirements that a translated predicate may not weaken. */
public record GoalTranslationConstraint(List<KillClause> killClauses) {
	private static final int MAX_LEAVES = 16;
	private static final GoalTranslationConstraint NONE = new GoalTranslationConstraint(List.of());

	public GoalTranslationConstraint {
		killClauses = List.copyOf(Objects.requireNonNull(killClauses, "killClauses must not be null"));
		int leaves = 0;
		for (KillClause clause : killClauses) {
			Objects.requireNonNull(clause, "kill clause must not be null");
			for (KillAlternative alternative : clause.alternatives()) {
				leaves = Math.addExact(leaves, alternative.count());
			}
		}
		if (leaves > MAX_LEAVES) throw new IllegalArgumentException("kill constraints may require at most 16 leaves");
	}

	public static GoalTranslationConstraint none() {
		return NONE;
	}

	public void validate(GoalPredicate predicate) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		if (killClauses.isEmpty()) return;
		for (List<String> killPath : killPaths(predicate)) {
			if (!satisfiesClauses(killPath, 0, new boolean[killPath.size()])) {
				throw new AgentDomainException(
						"GOAL_TRANSLATION_CONSTRAINT_MISMATCH",
						"Translated predicate does not preserve every requested kill count"
				);
			}
		}
	}

	private boolean satisfiesClauses(List<String> kills, int clauseIndex, boolean[] used) {
		if (clauseIndex == killClauses.size()) return true;
		for (KillAlternative alternative : killClauses.get(clauseIndex).alternatives()) {
			if (claimAlternative(kills, used, alternative, 0, 0, clauseIndex)) return true;
		}
		return false;
	}

	private boolean claimAlternative(
			List<String> kills,
			boolean[] used,
			KillAlternative alternative,
			int searchFrom,
			int claimed,
			int clauseIndex
	) {
		if (claimed == alternative.count()) return satisfiesClauses(kills, clauseIndex + 1, used);
		for (int index = searchFrom; index < kills.size(); index++) {
			if (used[index] || !alternative.entityTypes().contains(kills.get(index))) continue;
			used[index] = true;
			if (claimAlternative(kills, used, alternative, index + 1, claimed + 1, clauseIndex)) return true;
			used[index] = false;
		}
		return false;
	}

	private static List<List<String>> killPaths(GoalPredicate predicate) {
		return switch (predicate) {
			case GoalPredicate.EntityKilledByAgent value -> List.of(List.of(value.entityType()));
			case GoalPredicate.AllOf value -> allOfPaths(value.predicates());
			case GoalPredicate.AnyOf value -> value.predicates().stream()
					.flatMap(child -> killPaths(child).stream())
					.toList();
			default -> List.of(List.of());
		};
	}

	private static List<List<String>> allOfPaths(List<GoalPredicate> predicates) {
		List<List<String>> paths = List.of(List.of());
		for (GoalPredicate predicate : predicates) {
			ArrayList<List<String>> combined = new ArrayList<>();
			for (List<String> left : paths) {
				for (List<String> right : killPaths(predicate)) {
					ArrayList<String> path = new ArrayList<>(left.size() + right.size());
					path.addAll(left);
					path.addAll(right);
					combined.add(List.copyOf(path));
				}
			}
			paths = List.copyOf(combined);
		}
		return paths;
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
}
