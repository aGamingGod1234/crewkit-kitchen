package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class ServerObservationCollector {
	public static final int MAX_ENTITIES = 64;
	public static final int MAX_BLOCKS = 128;
	public static final int BLOCK_RADIUS = 6;
	public static final int MAX_BLOCKS_PER_TYPE = 8;
	public static final int MAX_NEARBY_TRANSACTION_TARGETS = 16;

	private final CodexAgentManager manager;
	private final ServerActionExecutor actionExecutor;

	public ServerObservationCollector(CodexAgentManager manager, ServerActionExecutor actionExecutor) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
		this.actionExecutor = Objects.requireNonNull(actionExecutor, "actionExecutor must not be null");
	}

	public JsonObject collect(AgentId agentId) {
		AgentRecord record = manager.registry().require(agentId);
		ServerPlayer agent = manager.findAgentPlayer(agentId).orElse(null);
		JsonObject observation = new JsonObject();
		observation.addProperty("goalRevision", record.goalRevision());
		observation.addProperty("observedAtEpochMs", System.currentTimeMillis());
		boolean ready = agent != null && agent.isAlive();
		observation.addProperty("ready", ready);
		observation.addProperty("status", agent == null ? "PLAYER_UNAVAILABLE"
				: ready ? record.state().name() : "PLAYER_DEAD");
		if (!ready) return observation;

		ServerLevel level = agent.level();
		observation.add("position", vector(agent.position()));
		observation.add("velocity", vector(agent.getDeltaMovement()));
		JsonObject view = new JsonObject();
		view.addProperty("yaw", finite(agent.getYRot()));
		view.addProperty("pitch", finite(agent.getXRot()));
		observation.add("view", view);

		JsonObject player = new JsonObject();
		player.addProperty("health", finite(agent.getHealth()));
		player.addProperty("maxHealth", finite(agent.getMaxHealth()));
		player.addProperty("armor", Math.max(0, agent.getArmorValue()));
		player.addProperty("foodLevel", agent.getFoodData().getFoodLevel());
		player.addProperty("saturation", finite(agent.getFoodData().getSaturationLevel()));
		player.addProperty("gameMode", agent.gameMode.getGameModeForPlayer().getName());
		player.addProperty("onGround", agent.onGround());
		player.addProperty("inWater", agent.isInWater());
		player.addProperty("onFire", agent.isOnFire());
		player.addProperty("air", Math.max(0, agent.getAirSupply()));
		player.addProperty("maxAir", Math.max(1, agent.getMaxAirSupply()));
		player.addProperty("suffocating", agent.isInWall());
		player.addProperty("fallDistance", finite(agent.fallDistance));
		player.addProperty("dangerousFall", !agent.onGround() && agent.fallDistance > 6.0F);
		LivingEntity attacker = agent.getLastHurtByMob();
		if (attacker != null && attacker.isAlive()) {
			JsonObject threat = new JsonObject();
			threat.addProperty("uuid", attacker.getUUID().toString());
			threat.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
			threat.addProperty("distance", finite(agent.distanceTo(attacker)));
			threat.addProperty("health", finite(attacker.getHealth()));
			player.add("lastAttacker", threat);
		}
		player.add("effects", effects(agent));
		observation.add("player", player);

		observation.add("inventory", inventory(agent));
		observation.add("entities", entities(level, agent));
		observation.add("blocks", blocks(level, agent.blockPosition()));
		observation.add("nearbyContainers", nearbyTransactionTargets(level, agent));
		JsonObject world = new JsonObject();
		world.addProperty("dimension", level.dimension().identifier().toString());
		world.addProperty("gameTime", level.getGameTime());
		world.addProperty("dayTime", level.getDefaultClockTime());
		world.addProperty("raining", level.isRaining());
		world.addProperty("thundering", level.isThundering());
		observation.add("world", world);
		observation.add("currentAction", currentAction(agentId));
		observation.add("lastResult", lastResult(agentId));
		return observation;
	}

	private JsonObject currentAction(AgentId agentId) {
		JsonObject json = new JsonObject();
		ServerActionRequest request = actionExecutor.activeRequests().stream()
				.filter(candidate -> candidate.agentId().equals(agentId))
				.findFirst()
				.orElse(null);
		json.addProperty("active", request != null);
		if (request != null) {
			json.addProperty("actionId", request.actionId());
			json.addProperty("actionType", request.type().wireName());
		}
		return json;
	}

	private JsonObject lastResult(AgentId agentId) {
		JsonObject json = new JsonObject();
		ServerActionResult result = actionExecutor.lastResult(agentId);
		json.addProperty("present", result != null);
		if (result != null) {
			json.addProperty("actionId", result.actionId());
			json.addProperty("actionType", result.actionType().wireName());
			json.addProperty("state", result.state().name());
			json.addProperty("reasonCode", result.reasonCode());
			json.addProperty("message", result.message());
		}
		return json;
	}

	private static JsonArray effects(ServerPlayer agent) {
		JsonArray values = new JsonArray();
		agent.getActiveEffects().stream().limit(32).forEach(effect -> {
			JsonObject json = new JsonObject();
			json.addProperty("effectId", effect.getEffect().unwrapKey()
					.map(key -> key.identifier().toString()).orElse(effect.getDescriptionId()));
			json.addProperty("amplifier", effect.getAmplifier());
			json.addProperty("duration", effect.getDuration());
			values.add(json);
		});
		return values;
	}

	private static JsonObject inventory(ServerPlayer agent) {
		JsonObject inventory = new JsonObject();
		JsonArray items = new JsonArray();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack stack = agent.getItemBySlot(slot);
			if (stack.isEmpty()) continue;
			JsonObject item = item(stack);
			item.addProperty("slot", slot.getName());
			items.add(item);
		}
		Inventory playerInventory = agent.getInventory();
		int selectedMainHandSlot = playerInventory.getSelectedSlot();
		for (int slot = 0; slot < playerInventory.getContainerSize() && items.size() < 64; slot++) {
			if (!InventoryObservationSlots.shouldEmitNumeric(
					slot,
					selectedMainHandSlot,
					Inventory.EQUIPMENT_SLOT_MAPPING.containsKey(slot)
			)) continue;
			ItemStack stack = playerInventory.getItem(slot);
			if (stack.isEmpty()) continue;
			JsonObject item = item(stack);
			item.addProperty("slot", slot);
			item.addProperty("hotbar", slot < 9);
			items.add(item);
		}
		inventory.add("items", items);
		inventory.addProperty("selectedItem", BuiltInRegistries.ITEM.getKey(agent.getMainHandItem().getItem()).toString());
		return inventory;
	}

	private static JsonObject item(ItemStack stack) {
		JsonObject item = new JsonObject();
		item.addProperty("itemId", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		item.addProperty("count", stack.getCount());
		item.addProperty("damage", stack.getDamageValue());
		item.addProperty("maxDamage", stack.getMaxDamage());
		return item;
	}

	private static JsonArray entities(ServerLevel level, ServerPlayer agent) {
		JsonArray values = new JsonArray();
		level.getEntities(agent, agent.getBoundingBox().inflate(32.0D), Entity::isAlive).stream()
				.sorted(Comparator.comparingDouble(agent::distanceToSqr))
				.limit(MAX_ENTITIES)
				.forEach(entity -> {
					JsonObject json = new JsonObject();
					json.addProperty("uuid", entity.getUUID().toString());
					json.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
					json.addProperty("name", entity.getName().getString());
					json.addProperty("distance", finite(agent.distanceTo(entity)));
					json.add("position", vector(entity.position()));
					json.addProperty("hostile", entity instanceof Enemy);
					if (entity instanceof LivingEntity living) {
						json.addProperty("health", finite(living.getHealth()));
						json.addProperty("maxHealth", finite(living.getMaxHealth()));
					}
					if (entity instanceof ServerPlayer player) {
						String gameMode = player.gameMode.getGameModeForPlayer().getName();
						json.addProperty("isPlayer", true);
						json.addProperty("gameMode", gameMode);
						json.addProperty("canBeHarmed", !player.isCreative() && !player.isSpectator());
					}
					values.add(json);
				});
		return values;
	}

	private static JsonArray blocks(ServerLevel level, BlockPos center) {
		ArrayList<BlockObservationOrdering.Candidate> candidates = new ArrayList<>();
		for (int y = -3; y <= 3; y++) {
			for (int x = -BLOCK_RADIUS; x <= BLOCK_RADIUS; x++) {
				for (int z = -BLOCK_RADIUS; z <= BLOCK_RADIUS; z++) {
					BlockPos position = center.offset(x, y, z);
					if (!level.hasChunkAt(position)) continue;
					BlockState state = level.getBlockState(position);
					if (state.isAir()) continue;
					candidates.add(new BlockObservationOrdering.Candidate(
							x,
							y,
							z,
							BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()
					));
				}
			}
		}
		JsonArray values = new JsonArray();
		for (BlockObservationOrdering.Candidate candidate :
				BlockObservationOrdering.select(candidates, MAX_BLOCKS, MAX_BLOCKS_PER_TYPE)) {
			BlockPos position = center.offset(candidate.x(), candidate.y(), candidate.z());
			JsonObject json = new JsonObject();
			json.addProperty("x", position.getX());
			json.addProperty("y", position.getY());
			json.addProperty("z", position.getZ());
			json.addProperty("blockId", candidate.blockId());
			values.add(json);
		}
		return values;
	}

	private static JsonArray nearbyTransactionTargets(ServerLevel level, ServerPlayer agent) {
		BlockPos center = agent.blockPosition();
		ArrayList<TransactionTarget> candidates = new ArrayList<>();
		for (int y = -3; y <= 3; y++) {
			for (int x = -BLOCK_RADIUS; x <= BLOCK_RADIUS; x++) {
				for (int z = -BLOCK_RADIUS; z <= BLOCK_RADIUS; z++) {
					BlockPos position = center.offset(x, y, z);
					if (!level.hasChunkAt(position)) continue;
					String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock()).toString();
					List<String> capabilities = transactionCapabilities(blockId);
					if (capabilities.isEmpty()) continue;
					candidates.add(new TransactionTarget(position, blockId, capabilities,
							agent.distanceToSqr(Vec3.atCenterOf(position))));
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(TransactionTarget::distanceSquared)
				.thenComparingInt(candidate -> candidate.position().getY())
				.thenComparingInt(candidate -> candidate.position().getX())
				.thenComparingInt(candidate -> candidate.position().getZ()));
		JsonArray values = new JsonArray();
		for (TransactionTarget candidate : candidates.stream().limit(MAX_NEARBY_TRANSACTION_TARGETS).toList()) {
			JsonObject json = new JsonObject();
			json.addProperty("x", candidate.position().getX());
			json.addProperty("y", candidate.position().getY());
			json.addProperty("z", candidate.position().getZ());
			json.addProperty("blockId", candidate.blockId());
			json.addProperty("distance", finite(Math.sqrt(candidate.distanceSquared())));
			json.addProperty("withinInteractionRange", candidate.distanceSquared() <= 36.0D);
			JsonArray capabilities = new JsonArray();
			candidate.capabilities().forEach(capabilities::add);
			json.add("capabilities", capabilities);
			values.add(json);
		}
		return values;
	}

	public static List<String> transactionCapabilities(String blockId) {
		return switch (Objects.requireNonNull(blockId, "blockId must not be null")) {
			case "minecraft:chest", "minecraft:trapped_chest" -> List.of("transfer_container");
			case "minecraft:crafting_table" -> List.of("craft_table");
			case "minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace" ->
					List.of("furnace_transaction");
			default -> List.of();
		};
	}

	private record TransactionTarget(
			BlockPos position,
			String blockId,
			List<String> capabilities,
			double distanceSquared
	) {
	}

	private static JsonObject vector(Vec3 vector) {
		JsonObject json = new JsonObject();
		json.addProperty("x", finite(vector.x));
		json.addProperty("y", finite(vector.y));
		json.addProperty("z", finite(vector.z));
		return json;
	}

	private static double finite(double value) {
		return Double.isFinite(value) ? value : 0.0D;
	}
}
