package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.agent.goal.MinecraftCoordinateBounds;
import dev.agaminggod.arenaagents.server.goal.AgentKillLedger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/** Evaluates only the immutable goal stored by Minecraft against live server facts. */
public final class GoalCompletionVerifier {
	private final Map<PredicateKey, PositionCounter> stablePositionTicks = new HashMap<>();
	private final Map<PredicateKey, SurvivalCounter> survivalCounters = new HashMap<>();

	public VerificationResult verify(
			AgentRecord record,
			FactSource facts,
			AgentKillLedger killLedger,
			long serverTick,
			boolean operatorConfirmed
	) {
		Objects.requireNonNull(record, "record must not be null");
		if (record.currentGoal().isEmpty()) return failure(record.goalRevision(), "NO_ACTIVE_GOAL");
		if (facts == null) return failure(record.goalRevision(), "NO_PLAYER");
		if (serverTick < 0L) throw new IllegalArgumentException("serverTick must be nonnegative");
		AgentGoal goal = record.currentGoal().orElseThrow();
		Evaluation evaluation = evaluate(
				goal.goalId(), goal.spec().completion(), "root", facts,
				killLedger == null ? new AgentKillLedger() : killLedger,
				record, serverTick, operatorConfirmed, new Counter(), new KillAllocation()
		);
		return new VerificationResult(
				evaluation.satisfied(), record.goalRevision(),
				evaluation.satisfied() ? "COMPLETION_VERIFIED" : "PREDICATE_FAILED",
				List.copyOf(evaluation.facts())
		);
	}

	public void retainGoals(Set<UUID> goalIds) {
		Set<UUID> retained = Set.copyOf(Objects.requireNonNull(goalIds, "goalIds must not be null"));
		stablePositionTicks.keySet().removeIf(key -> !retained.contains(key.goalId()));
		survivalCounters.keySet().removeIf(key -> !retained.contains(key.goalId()));
	}

	public static FactSource minecraftFacts(ServerPlayer player) {
		Objects.requireNonNull(player, "player must not be null");
		return new FactSource() {
			@Override public String dimensionId() {
				return player.level().dimension().identifier().toString();
			}

			@Override public boolean isCoordinateReachable(double x, double y, double z) {
				return MinecraftCoordinateBounds.isReachable(x, y, z)
						&& player.level().isInWorldBounds(BlockPos.containing(x, y, z));
			}

			@Override public boolean isCoordinateReachable(int x, int y, int z) {
				return MinecraftCoordinateBounds.isReachable(x, y, z)
						&& player.level().isInWorldBounds(new BlockPos(x, y, z));
			}

			@Override public int inventoryCount(String itemId) {
				int count = 0;
				for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
					ItemStack stack = player.getInventory().getItem(index);
					if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) count += stack.getCount();
				}
				return count;
			}

			@Override public Position position() {
				return new Position(player.getX(), player.getY(), player.getZ());
			}

			@Override public BlockFact blockAt(int x, int y, int z) {
				BlockState state = player.level().getBlockState(new BlockPos(x, y, z));
				LinkedHashMap<String, String> properties = new LinkedHashMap<>();
				state.getValues()
						.sorted(java.util.Comparator.comparing(entry -> entry.property().getName()))
						.forEach(entry -> properties.put(entry.property().getName(), entry.valueName()));
				return new BlockFact(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), properties);
			}

			@Override public boolean advancementGranted(String advancementId) {
				MinecraftServer server = player.level().getServer();
				var advancement = server.getAdvancements().get(Identifier.tryParse(advancementId));
				return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
			}

			@Override public boolean alive() {
				return player.isAlive();
			}
		};
	}

	private Evaluation evaluate(
			UUID goalId,
			GoalPredicate predicate,
			String path,
			FactSource source,
			AgentKillLedger kills,
			AgentRecord record,
			long tick,
			boolean operatorConfirmed,
			Counter counter,
			KillAllocation killAllocation
	) {
		if (predicate instanceof GoalPredicate.AllOf all) {
			boolean satisfied = true;
			ArrayList<GoalEvidence.Fact> facts = new ArrayList<>();
			for (int index = 0; index < all.predicates().size(); index++) {
				Evaluation child = evaluate(goalId, all.predicates().get(index), path + "." + index, source, kills,
						record, tick, operatorConfirmed, counter, killAllocation);
				satisfied &= child.satisfied();
				facts.addAll(child.facts());
			}
			return new Evaluation(satisfied, facts);
		}
		if (predicate instanceof GoalPredicate.AnyOf any) {
			boolean satisfied = false;
			ArrayList<GoalEvidence.Fact> facts = new ArrayList<>();
			KillAllocation baseAllocation = killAllocation.copy();
			KillAllocation selectedAllocation = null;
			for (int index = 0; index < any.predicates().size(); index++) {
				KillAllocation branchAllocation = baseAllocation.copy();
				Evaluation child = evaluate(goalId, any.predicates().get(index), path + "." + index, source, kills,
						record, tick, operatorConfirmed, counter, branchAllocation);
				if (!satisfied && child.satisfied()) selectedAllocation = branchAllocation;
				satisfied |= child.satisfied();
				facts.addAll(child.facts());
			}
			if (selectedAllocation != null) killAllocation.replaceWith(selectedAllocation);
			return new Evaluation(satisfied, facts);
		}
		if (counter.next() > 16) throw new IllegalStateException("Goal predicate leaf limit was not enforced");
		PredicateKey key = new PredicateKey(goalId, path);
		if (predicate instanceof GoalPredicate.InventoryContains inventory) {
			int observed = source.inventoryCount(inventory.itemId());
			return leaf("inventory_contains", observed >= inventory.count(), inventory.itemId() + " x" + inventory.count(), inventory.itemId() + " x" + observed);
		}
		if (predicate instanceof GoalPredicate.PositionWithin position) {
			if (!position.dimensionId().equals(source.dimensionId())) {
				return leaf("position_dimension", false, position.dimensionId(), source.dimensionId());
			}
			if (!source.isCoordinateReachable(position.x(), position.y(), position.z())) {
				return leaf("position_reachable", false, formatPosition(position.x(), position.y(), position.z()), "unreachable");
			}
			Position observed = source.position();
			double dx = observed.x() - position.x();
			double dy = observed.y() - position.y();
			double dz = observed.z() - position.z();
			boolean inside = dx * dx + dy * dy + dz * dz <= position.radius() * position.radius();
			PositionCounter previous = stablePositionTicks.getOrDefault(key, new PositionCounter(0, tick - 1L));
			int stable = inside
					? (previous.lastTick() == tick ? previous.ticks() : previous.lastTick() == tick - 1L ? previous.ticks() + 1 : 1)
					: 0;
			stablePositionTicks.put(key, new PositionCounter(stable, tick));
			return leaf("position_within", stable >= position.stableTicks(),
					formatPosition(position.x(), position.y(), position.z()) + " radius=" + position.radius() + " stableTicks=" + position.stableTicks(),
					formatPosition(observed.x(), observed.y(), observed.z()) + " stableTicks=" + stable);
		}
		if (predicate instanceof GoalPredicate.AdvancementGranted advancement) {
			boolean granted = source.advancementGranted(advancement.advancementId());
			return leaf("advancement_granted", granted, advancement.advancementId(), granted ? "granted" : "not granted");
		}
		if (predicate instanceof GoalPredicate.EntityKilledByAgent killed) {
			long afterTime = killed.afterGoalStart() ? record.currentGoal().orElseThrow().createdAtEpochMs() : Long.MIN_VALUE;
			int required = killAllocation.claim(new KillRequirement(killed.entityType(), afterTime));
			int observed = kills.count(record.agentId(), killed.entityType(), afterTime);
			return leaf("entity_killed_by_agent", observed >= required,
					killed.entityType() + " x" + required, killed.entityType() + " x" + observed);
		}
		if (predicate instanceof GoalPredicate.BlockMatches block) {
			if (!block.dimensionId().equals(source.dimensionId())) {
				return leaf("block_dimension", false, block.dimensionId(), source.dimensionId());
			}
			if (!source.isCoordinateReachable(block.x(), block.y(), block.z())) {
				return leaf("block_reachable", false, block.x() + "," + block.y() + "," + block.z(), "unreachable");
			}
			BlockFact observed = source.blockAt(block.x(), block.y(), block.z());
			boolean satisfied = observed.blockId().equals(block.blockId())
					&& block.properties().entrySet().stream().allMatch(entry -> entry.getValue().equals(observed.properties().get(entry.getKey())));
			return leaf("block_matches", satisfied, block.blockId() + sortedProperties(block.properties()), observed.blockId() + sortedProperties(observed.properties()));
		}
		if (predicate instanceof GoalPredicate.SurviveDuration survive) {
			SurvivalCounter previous = survivalCounters.getOrDefault(key, new SurvivalCounter(0L, tick - 1L));
			long observed = source.alive()
					? (previous.lastTick() == tick ? previous.ticks() : previous.lastTick() == tick - 1L ? previous.ticks() + 1L : 1L)
					: 0L;
			survivalCounters.put(key, new SurvivalCounter(observed, tick));
			return leaf("survive_duration", observed >= survive.ticks(), survive.ticks() + " ticks", observed + " ticks");
		}
		if (predicate instanceof GoalPredicate.OperatorConfirmed) {
			return leaf("operator_confirmed", operatorConfirmed, "operator confirmation", operatorConfirmed ? "confirmed" : "not confirmed");
		}
		throw new IllegalStateException("Unsupported stored goal predicate: " + predicate.getClass().getName());
	}

	private static Evaluation leaf(String type, boolean satisfied, String expected, String observed) {
		return new Evaluation(satisfied, List.of(new GoalEvidence.Fact(type, satisfied, expected, observed)));
	}

	private static String sortedProperties(Map<String, String> properties) {
		return properties.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.map(entry -> entry.getKey() + "=" + entry.getValue())
				.collect(java.util.stream.Collectors.joining(",", "[", "]"));
	}

	private static String formatPosition(double x, double y, double z) {
		return x + "," + y + "," + z;
	}

	private static VerificationResult failure(long revision, String reasonCode) {
		return new VerificationResult(false, revision, reasonCode, List.of());
	}

	public interface FactSource {
		int inventoryCount(String itemId);
		Position position();
		BlockFact blockAt(int x, int y, int z);
		boolean advancementGranted(String advancementId);
		boolean alive();
		default String dimensionId() { return GoalPredicate.DEFAULT_DIMENSION; }
		default boolean isCoordinateReachable(double x, double y, double z) {
			return MinecraftCoordinateBounds.isReachable(x, y, z);
		}
		default boolean isCoordinateReachable(int x, int y, int z) {
			return MinecraftCoordinateBounds.isReachable(x, y, z);
		}
	}

	public record Position(double x, double y, double z) { }

	public record BlockFact(String blockId, Map<String, String> properties) {
		public BlockFact {
			blockId = Objects.requireNonNull(blockId, "blockId must not be null");
			properties = Map.copyOf(Objects.requireNonNull(properties, "properties must not be null"));
		}
	}

	public record VerificationResult(boolean verified, long goalRevision, String reasonCode, List<GoalEvidence.Fact> facts) {
		public VerificationResult {
			Objects.requireNonNull(reasonCode, "reasonCode must not be null");
			facts = List.copyOf(Objects.requireNonNull(facts, "facts must not be null"));
		}

		public GoalEvidence evidence(long verifiedAtTick) {
			if (!verified) throw new IllegalStateException("Failed verification has no accepted evidence");
			return new GoalEvidence(verifiedAtTick, reasonCode, facts);
		}

		public JsonObject toJson() {
			JsonObject result = new JsonObject();
			result.addProperty("verified", verified);
			result.addProperty("goalRevision", goalRevision);
			result.addProperty("reasonCode", reasonCode);
			JsonArray jsonFacts = new JsonArray();
			for (GoalEvidence.Fact fact : facts) {
				JsonObject entry = new JsonObject();
				entry.addProperty("type", fact.type());
				entry.addProperty("satisfied", fact.satisfied());
				entry.addProperty("expectedValue", fact.expectedValue());
				entry.addProperty("observedValue", fact.observedValue());
				jsonFacts.add(entry);
			}
			result.add("facts", jsonFacts);
			return result;
		}
	}

	private record PredicateKey(UUID goalId, String path) { }
	private record PositionCounter(int ticks, long lastTick) { }
	private record SurvivalCounter(long ticks, long lastTick) { }
	private record KillRequirement(String entityType, long afterTime) { }
	private record Evaluation(boolean satisfied, List<GoalEvidence.Fact> facts) { }
	private static final class Counter { private int value; int next() { return ++value; } }
	private static final class KillAllocation {
		private final Map<KillRequirement, Integer> required = new HashMap<>();

		private KillAllocation copy() {
			KillAllocation copy = new KillAllocation();
			copy.required.putAll(required);
			return copy;
		}

		private int claim(KillRequirement requirement) {
			return required.merge(requirement, 1, Math::addExact);
		}

		private void replaceWith(KillAllocation selected) {
			required.clear();
			required.putAll(selected.required);
		}
	}
}
