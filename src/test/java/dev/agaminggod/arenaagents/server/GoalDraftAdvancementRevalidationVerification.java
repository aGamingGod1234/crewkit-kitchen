package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class GoalDraftAdvancementRevalidationVerification {
	private GoalDraftAdvancementRevalidationVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		Set<String> liveAdvancements = new LinkedHashSet<>(List.of(
				"minecraft:story/root",
				"example:quest/finish"
		));
		GoalPredicate nested = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.AdvancementGranted("minecraft:story/root"),
				new GoalPredicate.AnyOf(List.of(
						new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
						new GoalPredicate.AdvancementGranted("example:quest/finish")
				))
		));

		CodexAgentManager.validateLiveAdvancementIdentifiers(nested, liveAdvancements::contains);
		pass("valid nested advancement leaves survive confirmation revalidation");

		liveAdvancements.remove("example:quest/finish");
		expectCode(
				"UNKNOWN_GOAL_IDENTIFIER",
				() -> CodexAgentManager.validateLiveAdvancementIdentifiers(nested, liveAdvancements::contains),
				"reload removal rejects a stale advancement nested under any-of"
		);

		liveAdvancements.clear();
		expectCode(
				"UNKNOWN_GOAL_IDENTIFIER",
				() -> CodexAgentManager.validateLiveAdvancementIdentifiers(nested, liveAdvancements::contains),
				"restart removal rejects every stale advancement leaf before activation"
		);

		GoalPredicate nonAdvancement = new GoalPredicate.AllOf(List.of(
				new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1),
				new GoalPredicate.SurviveDuration(20)
		));
		CodexAgentManager.validateLiveAdvancementIdentifiers(nonAdvancement, ignored -> false);
		pass("non-advancement predicates remain valid when no advancements are registered");
		return 4;
	}

	private static void expectCode(String code, Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + ": expected " + code);
		} catch (AgentDomainException exception) {
			if (!code.equals(exception.code())) {
				throw new AssertionError(label + ": expected=" + code + ", actual=" + exception.code());
			}
			pass(label);
		}
	}

	private static void pass(String label) {
		System.out.println("PASS: " + label);
	}
}
