package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/** Verifies only bounded, server-observed world facts. It never interprets model prose as proof. */
public final class GoalCompletionVerifier {
	public VerificationResult verify(
			AgentRecord record,
			ServerPlayer player,
			GoalCompletionContract contract,
			ActionSuccessLedger actionLedger
	) {
		Objects.requireNonNull(record, "record must not be null");
		if (contract == null) return failure(record.goalRevision(), "MALFORMED_CONTRACT", List.of());
		if (record.currentGoal().isEmpty()) return failure(record.goalRevision(), "NO_ACTIVE_GOAL", List.of());
		if (contract.goalRevision() != record.goalRevision()) return failure(record.goalRevision(), "STALE_REVISION", List.of());
		if (player == null) return failure(record.goalRevision(), "NO_PLAYER", List.of());
		ArrayList<Fact> facts = new ArrayList<>();
		boolean allSatisfied = true;
		for (int index = 0; index < contract.predicates().size(); index++) {
			GoalCompletionContract.Predicate predicate = contract.predicates().get(index);
			Fact fact = evaluate(record.agentId(), contract.goalRevision(), player, predicate, index, actionLedger);
			facts.add(fact);
			allSatisfied &= fact.satisfied();
		}
		return new VerificationResult(allSatisfied, contract.goalRevision(), allSatisfied ? "COMPLETION_VERIFIED" : "PREDICATE_FAILED", List.copyOf(facts));
	}

	private Fact evaluate(AgentId agentId, long goalRevision, ServerPlayer player, GoalCompletionContract.Predicate predicate, int index, ActionSuccessLedger actionLedger) {
		return switch (predicate.type()) {
			case "inventory_min" -> {
				int observed = inventoryCount(player, predicate.itemId());
				yield fact(index, predicate.type(), observed >= predicate.count(), Integer.toString(observed));
			}
			case "position_within" -> {
				double distanceSquared = player.position().distanceToSqr(predicate.x(), predicate.y(), predicate.z());
				yield fact(index, predicate.type(), distanceSquared <= predicate.radius() * predicate.radius(), Double.toString(Math.sqrt(distanceSquared)));
			}
			case "block_matches" -> {
				BlockPos position = new BlockPos(predicate.x().intValue(), predicate.y().intValue(), predicate.z().intValue());
				String observed = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(position).getBlock()).toString();
				yield fact(index, predicate.type(), observed.equals(predicate.blockId()), observed);
			}
			case "entity_state" -> {
				Entity entity = player.level().getEntity(predicate.entityId());
				boolean satisfied = entity != null && (predicate.entityState().equals("alive") == entity.isAlive());
				yield fact(index, predicate.type(), satisfied, entity == null ? "missing" : (entity.isAlive() ? "alive" : "dead"));
			}
			case "action_success_count" -> {
				int observed = actionLedger == null ? 0 : actionLedger.count(agentId, goalRevision, predicate.actionType());
				yield fact(index, predicate.type(), observed >= predicate.count(), Integer.toString(observed));
			}
			default -> fact(index, predicate.type(), false, "unsupported");
		};
	}

	private static Fact fact(int index, String type, boolean satisfied, String observedValue) {
		return new Fact(index, type, satisfied, observedValue.length() > 128 ? observedValue.substring(0, 128) : observedValue);
	}

	private static int inventoryCount(ServerPlayer player, String itemId) {
		int count = 0;
		for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
			ItemStack stack = player.getInventory().getItem(index);
			if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) count += stack.getCount();
		}
		return count;
	}

	private static VerificationResult failure(long revision, String reasonCode, List<Fact> facts) {
		return new VerificationResult(false, revision, reasonCode, facts);
	}

	public record Fact(int predicateIndex, String type, boolean satisfied, String observedValue) {
		public Fact {
			if (predicateIndex < 0) throw new IllegalArgumentException("predicateIndex must be nonnegative");
			Objects.requireNonNull(type, "type must not be null");
			Objects.requireNonNull(observedValue, "observedValue must not be null");
		}
	}

	public record VerificationResult(boolean verified, long goalRevision, String reasonCode, List<Fact> facts) {
		public VerificationResult {
			Objects.requireNonNull(reasonCode, "reasonCode must not be null");
			facts = List.copyOf(Objects.requireNonNull(facts, "facts must not be null"));
		}

		public JsonObject toJson() {
			JsonObject result = new JsonObject();
			result.addProperty("verified", verified);
			result.addProperty("goalRevision", goalRevision);
			result.addProperty("reasonCode", reasonCode);
			JsonArray factsJson = new JsonArray();
			for (Fact fact : facts) {
				JsonObject entry = new JsonObject();
				entry.addProperty("predicateIndex", fact.predicateIndex());
				entry.addProperty("type", fact.type());
				entry.addProperty("satisfied", fact.satisfied());
				entry.addProperty("observedValue", fact.observedValue());
				factsJson.add(entry);
			}
			result.add("facts", factsJson);
			return result;
		}
	}
}
