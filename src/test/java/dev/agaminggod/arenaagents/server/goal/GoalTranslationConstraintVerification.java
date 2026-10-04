package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.RegistryAccess;

/** Allocation and compound-confirmation regressions, also runnable without a server. */
public final class GoalTranslationConstraintVerification {
	private static final String A = "minecraft:diamond_axe", P = "minecraft:diamond_pickaxe";
	private static final String B = "minecraft:iron_axe", Q = "minecraft:iron_pickaxe";
	private static final String Z = "minecraft:zombie", C = "minecraft:creeper";
	private static int checks;
	private static final java.io.PrintStream OUTPUT = System.out;

	public static int verify() {
		int before = checks;
		confirmation();
		unresolved();
		inventory();
		kills(14);
		allocationOracle();
		return checks - before;
	}

	public static void main(String[] args) {
		var out = System.out;
		try {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		switch (args.length == 0 ? "all" : args[0]) {
			case "f090" -> confirmation();
			case "f091" -> unresolved();
			case "f092" -> inventory();
			case "f093-bounded" -> kills(9);
			case "all" -> verify();
			default -> throw new IllegalArgumentException("Unknown test selection");
		}
		out.println("PASS " + checks + " assertions");
		} catch (Throwable failure) {
			failure.printStackTrace(out);
			throw failure;
		}
	}

	private static void confirmation() {
		var compiler = new GoalCompiler();
		for (String request : List.of("Get dirt and make a diamond pickaxe",
				"Make a diamond pickaxe and get dirt", "Get dirt and make a diamond pickaxe and a diamond shovel",
				"Craft a diamond pickaxe and a diamond shovel")) {
			check(compiler.translationRequiresOperatorConfirmation(request, RegistryAccess.EMPTY), "creation gate: " + request);
			GoalPredicate proposal = all(item("minecraft:dirt", 1), item(P, 1), new GoalPredicate.OperatorConfirmed());
			check(GoalCompiler.requiresConfirmationOnEveryPath(compiler.normalizeTranslatedPredicate(request, proposal)), "normalization preserves gate");
		}
		String factual = "Get dirt and get a diamond pickaxe";
		check(!compiler.translationRequiresOperatorConfirmation(factual, RegistryAccess.EMPTY), "acquisition stays factual");
		check(!GoalCompiler.requiresConfirmationOnEveryPath(compiler.normalizeTranslatedPredicate(factual,
				all(item("minecraft:dirt", 1), item(P, 1), new GoalPredicate.OperatorConfirmed()))), "acquisition strips unnecessary gate");
	}

	private static void unresolved() {
		var compiler = new GoalCompiler();
		for (String request : List.of("Get a diamond pickaxe and go to 100 64 100",
				"Get a diamond pickaxe and survive for 200 ticks",
				"Survive for 200 ticks and get a diamond pickaxe")) {
			check(!compiler.translationRequiresOperatorConfirmation(request, RegistryAccess.EMPTY), "recognized factual clauses remain automatic");
			var constraint = compiler.translationConstraintFor(request, RegistryAccess.EMPTY);
			check(!accepts(constraint, all(item(P, 1), new GoalPredicate.OperatorConfirmed())), "confirmation cannot replace a factual obligation");
			GoalPredicate fact = request.contains("go to") ? new GoalPredicate.PositionWithin(100,64,100,1,20) : new GoalPredicate.SurviveDuration(200);
			check(accepts(constraint, all(item(P,1),fact)), "faithful automatic factual predicate accepted");
		}
		check(!GoalCompiler.requiresConfirmationOnEveryPath(new GoalPredicate.AnyOf(List.of(item(P, 1), new GoalPredicate.OperatorConfirmed()))), "gate in only one alternative is insufficient");
	}

	private static void inventory() {
		var pick = itemClause(List.of(P, Q), 1);
		var diamond = itemClause(List.of(A, P), 2);
		var iron = itemClause(List.of(B, Q), 1);
		var leaves = List.<GoalPredicate>of(item(A, 1), item(P, 1), item(B, 1), item(Q, 1), new GoalPredicate.OperatorConfirmed());
		for (var clauses : permutations(List.of(pick, diamond, iron))) {
			var constraint = new GoalTranslationConstraint(List.of(), clauses);
			for (var order : permutations(leaves)) check(accepts(constraint, new GoalPredicate.AllOf(order)), "feasible allocation independent of both orders");
			check(!accepts(constraint, all(item(A, 1), item(P, 1), item(B, 1))), "quantity deficit rejected");
		}
		var group = List.of(A, P);
		check(!accepts(new GoalTranslationConstraint(List.of(), List.of(itemClause(group, 2), itemClause(group, 3))),
				all(new GoalPredicate.InventoryContainsAny(group, 2), new GoalPredicate.InventoryContainsAny(group.reversed(), 3))), "duplicate guarantees cannot add");
		check(!accepts(new GoalTranslationConstraint(List.of(), List.of(itemClause(List.of(A, P), 2), itemClause(List.of(P, Q), 2))),
				all(new GoalPredicate.InventoryContainsAny(List.of(A, P), 2), new GoalPredicate.InventoryContainsAny(List.of(P, Q), 2))), "overlapping guarantees remain conservative");
		check(!accepts(new GoalTranslationConstraint(List.of(), List.of(itemClause(List.of(A), 1))),
				new GoalPredicate.AnyOf(List.of(item(A, 1), item(B, 1)))), "every proposal alternative must prove obligations");
		var choice = new GoalTranslationConstraint.ItemClause(List.of(new GoalTranslationConstraint.ItemAlternative(List.of(A), 2), new GoalTranslationConstraint.ItemAlternative(List.of(B), 1)));
		check(accepts(new GoalTranslationConstraint(List.of(), List.of(choice)), item(B, 1)), "explicit second item alternative accepted");
		check(!accepts(new GoalTranslationConstraint(List.of(), List.of(choice)), all(item(A, 1), item(P, 1))), "alternatives cannot be mixed");
		check(accepts(new GoalTranslationConstraint(List.of(), List.of(itemClause(List.of(A, P), Integer.MAX_VALUE), itemClause(List.of(A, P), Integer.MAX_VALUE))),
				all(item(A, Integer.MAX_VALUE), item(P, Integer.MAX_VALUE))), "capacities do not overflow or expand per item");
	}

	private static void kills(int count) {
		var clauses = new ArrayList<GoalTranslationConstraint.KillClause>();
		var leaves = new ArrayList<GoalPredicate>();
		for (int i = 0; i < count; i++) { clauses.add(killClause(List.of(Z), 1)); leaves.add(new GoalPredicate.EntityKilledByAgent(Z, true)); }
		clauses.add(killClause(List.of(C), 1));
		var constraint = new GoalTranslationConstraint(clauses);
		long start = System.nanoTime();
		check(!accepts(constraint, new GoalPredicate.AllOf(leaves)), "late missing creeper rejected");
		OUTPUT.println("late-missing count=" + count + " elapsedMs=" + (System.nanoTime() - start) / 1_000_000.0);
		leaves.add(new GoalPredicate.EntityKilledByAgent(C, true));
		check(accepts(constraint, new GoalPredicate.AllOf(leaves)), "distinct full sibling accepted");
		var overlap = new GoalTranslationConstraint(List.of(killClause(List.of(Z, C), 1), killClause(List.of(Z), 1)));
		check(accepts(overlap, all(new GoalPredicate.EntityKilledByAgent(Z, true), new GoalPredicate.EntityKilledByAgent(C, true))), "overlapping requirements reassign evidence");
		check(!accepts(overlap, new GoalPredicate.EntityKilledByAgent(Z, true)), "no kill evidence reuse");
		var choice = new GoalTranslationConstraint.KillClause(List.of(new GoalTranslationConstraint.KillAlternative(List.of(C), 1), new GoalTranslationConstraint.KillAlternative(List.of(Z), 1)));
		check(accepts(new GoalTranslationConstraint(List.of(choice)), new GoalPredicate.EntityKilledByAgent(Z, true)), "explicit second kill alternative accepted");
	}

	/** Independent small exhaustive assignment oracle challenges residual reassignment and alternative selection. */
	private static void allocationOracle() {
		var random = new Random(92193);
		var ids = List.of(A, P, B);
		for (int trial = 0; trial < 240; trial++) {
			var clauses = new ArrayList<GoalTranslationConstraint.ItemClause>();
			for (int c = 0; c < 1 + random.nextInt(4); c++) {
				var alternatives = new ArrayList<GoalTranslationConstraint.ItemAlternative>();
				for (int a = 0; a < 1 + random.nextInt(2); a++) {
					var accepted = new ArrayList<String>();
					for (String id : ids) if (random.nextBoolean()) accepted.add(id);
					if (accepted.isEmpty()) accepted.add(ids.get(random.nextInt(3)));
					alternatives.add(new GoalTranslationConstraint.ItemAlternative(accepted, 1 + random.nextInt(2)));
				}
				clauses.add(new GoalTranslationConstraint.ItemClause(alternatives));
			}
			var units = new ArrayList<String>();
			var evidence = new ArrayList<GoalPredicate>();
			for (String id : ids) { int n = random.nextInt(3); for (int i = 0; i < n; i++) units.add(id); if (n > 0) evidence.add(item(id, n)); }
			if (evidence.isEmpty()) evidence.add(new GoalPredicate.OperatorConfirmed());
			boolean expected = brute(clauses, 0, units, 0);
			check(accepts(new GoalTranslationConstraint(List.of(), clauses), new GoalPredicate.AllOf(evidence)) == expected, "oracle trial " + trial);
			var killClauses = clauses.stream().map(c -> new GoalTranslationConstraint.KillClause(c.alternatives().stream().map(a -> new GoalTranslationConstraint.KillAlternative(a.itemIds(), a.count())).toList())).toList();
			var killEvidence = units.stream().<GoalPredicate>map(id -> new GoalPredicate.EntityKilledByAgent(id, true)).toList();
			check(accepts(new GoalTranslationConstraint(killClauses), killEvidence.isEmpty() ? new GoalPredicate.OperatorConfirmed() : new GoalPredicate.AllOf(killEvidence)) == expected, "kill oracle trial " + trial);
		}
	}

	private static boolean brute(List<GoalTranslationConstraint.ItemClause> clauses, int at, List<String> units, int used) {
		if (at == clauses.size()) return true;
		for (var alternative : clauses.get(at).alternatives()) for (int mask = 0; mask < (1 << units.size()); mask++) {
			if ((mask & used) != 0 || Integer.bitCount(mask) != alternative.count()) continue;
			boolean match = true;
			for (int i = 0; i < units.size(); i++) if ((mask & (1 << i)) != 0 && !alternative.itemIds().contains(units.get(i))) match = false;
			if (match && brute(clauses, at + 1, units, used | mask)) return true;
		}
		return false;
	}

	private static GoalTranslationConstraint.ItemClause itemClause(List<String> ids, int count) { return new GoalTranslationConstraint.ItemClause(List.of(new GoalTranslationConstraint.ItemAlternative(ids, count))); }
	private static GoalTranslationConstraint.KillClause killClause(List<String> ids, int count) { return new GoalTranslationConstraint.KillClause(List.of(new GoalTranslationConstraint.KillAlternative(ids, count))); }
	private static GoalPredicate item(String id, int n) { return new GoalPredicate.InventoryContains(id, n); }
	private static GoalPredicate all(GoalPredicate... values) { return new GoalPredicate.AllOf(List.of(values)); }
	private static boolean accepts(GoalTranslationConstraint constraint, GoalPredicate predicate) {
		try { constraint.validate(predicate); return true; }
		catch (AgentDomainException exception) { if (!exception.code().equals("GOAL_TRANSLATION_CONSTRAINT_MISMATCH")) throw exception; return false; }
	}
	private static <T> List<List<T>> permutations(List<T> values) {
		if (values.isEmpty()) return List.of(List.of());
		var result = new ArrayList<List<T>>();
		for (int i = 0; i < values.size(); i++) { var rest = new ArrayList<>(values); T head = rest.remove(i); for (var tail : permutations(rest)) { var order = new ArrayList<T>(); order.add(head); order.addAll(tail); result.add(order); } }
		return result;
	}
	private static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); checks++; }
}
