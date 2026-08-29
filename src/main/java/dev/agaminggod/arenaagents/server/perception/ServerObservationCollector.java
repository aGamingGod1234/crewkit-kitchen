package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.runtime.BlockPlacementAttemptPolicy;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuCapabilityRegistry;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class ServerObservationCollector {
	public static final int MAX_ENTITIES = 64;
	public static final int MAX_BLOCKS = 128;
	public static final int BLOCK_RADIUS = 6;
	public static final int MAX_BLOCKS_PER_TYPE = 8;
	public static final int MAX_NEARBY_TRANSACTION_TARGETS = 16;
	public static final int MAX_OBSERVATION_TAGS = 32;
	public static final int MAX_TAG_COUNT_ENTRIES = 128;
	private static final int SPATIAL_CACHE_CAPACITY = 16;
	/** Spatial block/container scans are expensive; movement and view changes still invalidate the key immediately. */
	private static final long SPATIAL_CACHE_TICKS = 10L;
	private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();

	private final CodexAgentManager manager;
	private final ServerActionExecutor actionExecutor;
	private final ObservationSectionCache<RawSpatialObservation.Key, RawSpatialObservation> spatialCache =
			new ObservationSectionCache<>(SPATIAL_CACHE_CAPACITY, SPATIAL_CACHE_TICKS, value -> value);
	private final Map<AgentId, RawSpatialObservation.Key> spatialKeys = new HashMap<>();
	private final Map<AgentId, RawPlayerState> lastRawStates = new HashMap<>();
	private final Map<AgentId, InventorySnapshot> lastInventories = new HashMap<>();
	private static final IdentityHashMap<Holder<?>, List<String>> TAG_VALUES = new IdentityHashMap<>();

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
		if (!ready) {
			invalidate(agentId);
			return observation;
		}

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
		LivingEntity attacker = agent.getLastHurtByMob();
		if (attacker != null && attacker.isAlive() && ObservationVisibility.canSeeEntity(agent, attacker)) {
			JsonObject threat = new JsonObject();
			threat.addProperty("uuid", attacker.getUUID().toString());
			threat.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString());
			threat.addProperty("distance", finite(agent.distanceTo(attacker)));
			player.add("lastAttacker", threat);
		}
		player.add("effects", effects(agent));
		observation.add("player", player);
		observation.add("interaction", interaction(agentId, agent));

		observation.add("inventory", inventory(agent));
		observation.add("entities", entities(level, agent));
		JsonObject spatial = spatialObservation(agentId, level, agent);
		observation.add("blocks", spatial.get("blocks"));
		observation.add("nearbyContainers", spatial.get("nearbyContainers"));
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

	public void invalidate(AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		spatialKeys.remove(agentId);
		spatialCache.invalidateMatching(key -> key.agentId().equals(agentId));
		synchronized (lastRawStates) {
			lastRawStates.remove(agentId);
			lastInventories.remove(agentId);
		}
	}

	/** Returns loaded agents whose compact factual player or inventory state changed since the last sample. */
	public List<AgentId> changedActiveAgents() {
		List<AgentId> changed = new ArrayList<>();
		if (manager.server() == null) {
			synchronized (lastRawStates) {
				lastRawStates.clear();
				lastInventories.clear();
			}
			return List.of();
		}
		HashSet<AgentId> tracked = new HashSet<>();
		for (AgentRecord record : manager.records()) {
			AgentId agentId = record.agentId();
			tracked.add(agentId);
			ServerPlayer agent = manager.findAgentPlayer(agentId).orElse(null);
			if (agent == null || !agent.isAlive()) {
				synchronized (lastRawStates) {
					lastRawStates.remove(agentId);
					lastInventories.remove(agentId);
				}
				continue;
			}
			RawPlayerState current = rawPlayerState(agent);
			synchronized (lastRawStates) {
				RawPlayerState previous = lastRawStates.put(agentId, current);
				boolean inventoryChanged = updateInventory(agentId, agent);
				if (previous != null && (!current.equals(previous) || inventoryChanged)) changed.add(agentId);
			}
		}
		synchronized (lastRawStates) {
			lastRawStates.keySet().removeIf(agentId -> !tracked.contains(agentId));
			lastInventories.keySet().removeIf(agentId -> !tracked.contains(agentId));
		}
		return List.copyOf(changed);
	}

	private JsonObject spatialObservation(AgentId agentId, ServerLevel level, ServerPlayer agent) {
		BlockPos position = agent.blockPosition();
		RawSpatialObservation.Key key = new RawSpatialObservation.Key(
				agentId,
				level.dimension().identifier().toString(),
				position.getX(),
				position.getY(),
				position.getZ()
		);
		RawSpatialObservation.Key previous = spatialKeys.put(agentId, key);
		if (previous != null && !previous.equals(key)) spatialCache.invalidate(previous);
		RawSpatialObservation raw = spatialCache.getOrCompute(
				key, level.getGameTime(), () -> rawSpatialObservation(level, agent, position));
		JsonObject value = new JsonObject();
		value.add("blocks", blocks(level, agent, raw.blocks()));
		value.add("nearbyContainers", nearbyTransactionTargets(level, agent, raw.containers()));
		return value;
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

	private JsonObject interaction(AgentId agentId, ServerPlayer agent) {
		JsonObject interaction = new JsonObject();
		interaction.addProperty("mainHandItemId", itemId(agent.getMainHandItem()));
		interaction.addProperty("offHandItemId", itemId(agent.getOffhandItem()));
		interaction.addProperty("usingItem", agent.isUsingItem());
		interaction.addProperty(
				"activeHand",
				agent.isUsingItem() ? agent.getUsedItemHand().name().toLowerCase(java.util.Locale.ROOT) : "none"
		);
		interaction.addProperty("useRemainingTicks", Math.max(0, agent.getUseItemRemainingTicks()));
		interaction.addProperty("attackCooldown", finite(agent.getAttackStrengthScale(0.0F)));

		AgentInputState input = AgentInputRuntime.controller(agent).currentState(agentId)
				.orElseGet(() -> AgentInputState.idle(
						agent.getYRot(),
						agent.getXRot(),
						agent.getInventory().getSelectedSlot()
				));
		JsonObject inputJson = new JsonObject();
		inputJson.addProperty("active", AgentInputRuntime.controller(agent).currentState(agentId).isPresent());
		inputJson.addProperty("forward", finite(input.forward()));
		inputJson.addProperty("strafe", finite(input.strafe()));
		inputJson.addProperty("jump", input.jump());
		inputJson.addProperty("sneak", input.sneak());
		inputJson.addProperty("sprint", input.sprint());
		inputJson.addProperty("attack", input.attack());
		inputJson.addProperty("use", input.use());
		inputJson.addProperty("yaw", finite(input.yaw()));
		inputJson.addProperty("pitch", finite(input.pitch()));
		inputJson.addProperty("selectedSlot", input.selectedSlot());
		inputJson.addProperty("hand", input.hand().name().toLowerCase(java.util.Locale.ROOT));
		interaction.add("input", inputJson);

		JsonObject menu = new JsonObject();
		String menuType;
		try {
			menuType = BuiltInRegistries.MENU.getKey(agent.containerMenu.getType()).toString();
		} catch (RuntimeException exception) {
			menuType = agent.containerMenu == agent.inventoryMenu ? "minecraft:inventory" : "minecraft:unknown";
		}
		menu.addProperty("type", menuType);
		menu.add("cursor", compactItem(agent.containerMenu.getCarried()));
		JsonArray menuSlots = new JsonArray();
		for (int index = 0; index < agent.containerMenu.slots.size() && index < 64; index++) {
			JsonObject slot = compactItem(agent.containerMenu.getSlot(index).getItem());
			slot.addProperty("slot", index);
			menuSlots.add(slot);
		}
		menu.add("slots", menuSlots);
		JsonArray menuCapabilities = new JsonArray();
		MenuCapabilityRegistry.capabilities(menuType).orElse(List.of()).forEach(menuCapabilities::add);
		menu.add("capabilities", menuCapabilities);
		interaction.add("menu", menu);

		HitResult hit = agent.pick(agent.blockInteractionRange(), 0.0F, false);
		interaction.add("rayTarget", rayTarget(hit, position ->
				BuiltInRegistries.BLOCK.getKey(agent.level().getBlockState(position).getBlock()).toString()
		));
		return interaction;
	}

	static JsonObject rayTarget(HitResult hit, Function<BlockPos, String> blockIdAt) {
		JsonObject rayTarget = new JsonObject();
		rayTarget.addProperty("type", hit.getType().name().toLowerCase(java.util.Locale.ROOT));
		if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
			BlockPos position = blockHit.getBlockPos();
			rayTarget.addProperty("x", position.getX());
			rayTarget.addProperty("y", position.getY());
			rayTarget.addProperty("z", position.getZ());
			rayTarget.addProperty("face", blockHit.getDirection().getName());
			rayTarget.addProperty("blockId", blockIdAt.apply(position));
		}
		return rayTarget;
	}

	private static JsonObject compactItem(ItemStack stack) {
		JsonObject item = new JsonObject();
		item.addProperty("itemId", itemId(stack));
		item.addProperty("count", stack.isEmpty() ? 0 : stack.getCount());
		return item;
	}

	private static String itemId(ItemStack stack) {
		return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
		Map<String, Integer> tagCounts = new HashMap<>();
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			ItemStack stack = agent.getItemBySlot(slot);
			if (stack.isEmpty()) continue;
			JsonObject item = item(stack);
			item.addProperty("slot", slot.getName());
			items.add(item);
			addTagCounts(tagCounts, stack);
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
			addTagCounts(tagCounts, stack);
		}
		inventory.add("items", items);
		JsonObject counts = new JsonObject();
		tagCounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(MAX_TAG_COUNT_ENTRIES)
				.forEach(entry -> counts.addProperty(entry.getKey(), entry.getValue()));
		inventory.add("tagCounts", counts);
		inventory.addProperty("selectedItem", BuiltInRegistries.ITEM.getKey(agent.getMainHandItem().getItem()).toString());
		return inventory;
	}

	private static JsonObject item(ItemStack stack) {
		JsonObject item = new JsonObject();
		item.addProperty("itemId", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		item.addProperty("count", stack.getCount());
		item.addProperty("damage", stack.getDamageValue());
		item.addProperty("maxDamage", stack.getMaxDamage());
		item.add("tags", tags(stack.typeHolder()));
		return item;
	}

	static void addTagCounts(Map<String, Integer> counts, ItemStack stack) {
		tags(stack.typeHolder()).forEach(tag -> counts.merge(tag.getAsString(), stack.getCount(), Integer::sum));
	}

	static JsonArray tags(Holder<?> holder) {
		List<String> cached;
		synchronized (TAG_VALUES) {
			cached = TAG_VALUES.get(holder);
			if (cached == null) {
				cached = holder.tags().map(tag -> "#" + tag.location().toString())
						.sorted().limit(MAX_OBSERVATION_TAGS).toList();
				TAG_VALUES.put(holder, cached);
			}
		}
		JsonArray values = new JsonArray();
		cached.forEach(values::add);
		return values;
	}

	/** Clears holder-derived tag values after datapack tags are reloaded. */
	public static void clearTagCache() {
		synchronized (TAG_VALUES) {
			TAG_VALUES.clear();
		}
	}

	private static JsonArray entities(ServerLevel level, ServerPlayer agent) {
		JsonArray values = new JsonArray();
		level.getEntities(agent, agent.getBoundingBox().inflate(32.0D), Entity::isAlive).stream()
				.filter(entity -> ObservationVisibility.canSeeEntity(agent, entity))
				.sorted(Comparator.comparingDouble(agent::distanceToSqr))
				.limit(MAX_ENTITIES)
				.forEach(entity -> {
					JsonObject json = new JsonObject();
					json.addProperty("uuid", entity.getUUID().toString());
					json.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
					json.addProperty("name", entity.getName().getString());
					json.addProperty("distance", finite(agent.distanceTo(entity)));
					json.add("position", vector(entity.position()));
					if (entity instanceof ServerPlayer player) {
						json.addProperty("isPlayer", true);
					}
					if (entity instanceof ItemEntity itemEntity) {
						json.addProperty("itemId", BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem()).toString());
						json.addProperty("count", itemEntity.getItem().getCount());
						json.add("tags", tags(itemEntity.getItem().typeHolder()));
					}
					values.add(json);
				});
		return values;
	}

	private static RawSpatialObservation rawSpatialObservation(ServerLevel level, ServerPlayer agent, BlockPos center) {
		ArrayList<BlockObservationOrdering.Candidate> candidates = new ArrayList<>();
		ArrayList<RawSpatialObservation.ContainerCandidate> containers = new ArrayList<>();
		for (int y = -3; y <= 3; y++) {
			for (int x = -BLOCK_RADIUS; x <= BLOCK_RADIUS; x++) {
				for (int z = -BLOCK_RADIUS; z <= BLOCK_RADIUS; z++) {
					BlockPos position = center.offset(x, y, z);
					if (!level.hasChunkAt(position)) continue;
					BlockState state = level.getBlockState(position);
					if (state.isAir()) continue;
					String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
					candidates.add(new BlockObservationOrdering.Candidate(
							x, y, z, blockId
					));
					List<String> capabilities = transactionCapabilities(blockId);
					if (!capabilities.isEmpty()) {
						containers.add(new RawSpatialObservation.ContainerCandidate(
								position.getX(), position.getY(), position.getZ(), blockId, capabilities,
								agent.distanceToSqr(Vec3.atCenterOf(position))));
					}
				}
			}
		}
		List<RawSpatialObservation.BlockCandidate> blocks = BlockObservationOrdering.select(
				candidates, MAX_BLOCKS * 4, MAX_BLOCKS_PER_TYPE * 4).stream()
				.map(candidate -> new RawSpatialObservation.BlockCandidate(
						center.getX() + candidate.x(), center.getY() + candidate.y(), center.getZ() + candidate.z(),
						candidate.blockId()))
				.toList();
		containers.sort(Comparator.comparingDouble(RawSpatialObservation.ContainerCandidate::distanceSquared)
				.thenComparingInt(RawSpatialObservation.ContainerCandidate::y)
				.thenComparingInt(RawSpatialObservation.ContainerCandidate::x)
				.thenComparingInt(RawSpatialObservation.ContainerCandidate::z));
		return new RawSpatialObservation(blocks,
				containers.stream().limit(MAX_NEARBY_TRANSACTION_TARGETS * 4L).toList());
	}

	private static JsonArray blocks(
			ServerLevel level,
			ServerPlayer agent,
			List<RawSpatialObservation.BlockCandidate> candidates
	) {
		ArrayList<BlockObservationOrdering.Candidate> relative = new ArrayList<>(candidates.size());
		BlockPos center = agent.blockPosition();
		for (RawSpatialObservation.BlockCandidate candidate : candidates) {
			relative.add(new BlockObservationOrdering.Candidate(
					candidate.x() - center.getX(), candidate.y() - center.getY(), candidate.z() - center.getZ(),
					candidate.blockId()));
		}
		JsonArray values = new JsonArray();
		for (BlockObservationOrdering.Candidate candidate : BlockObservationOrdering.select(
				relative,
				MAX_BLOCKS,
				MAX_BLOCKS_PER_TYPE,
				selectable -> ObservationVisibility.canSeeBlock(
						level, agent, center.offset(selectable.x(), selectable.y(), selectable.z()))
		)) {
			BlockPos position = center.offset(candidate.x(), candidate.y(), candidate.z());
			BlockState state = level.getBlockState(position);
			JsonObject json = new JsonObject();
			json.addProperty("x", position.getX());
			json.addProperty("y", position.getY());
			json.addProperty("z", position.getZ());
			json.addProperty("blockId", candidate.blockId());
			json.add("tags", tags(state.typeHolder()));
			JsonArray placeableFaces = new JsonArray();
			BlockPlacementAttemptPolicy.supportedFaces(face ->
					state.isFaceSturdy(level, position, face)
							&& level.getBlockState(position.relative(face)).canBeReplaced()
							&& agent.isWithinBlockInteractionRange(position, 1.0D)
			).forEach(face -> placeableFaces.add(face.getSerializedName()));
			json.add("placeableFaces", placeableFaces);
			values.add(json);
		}
		return values;
	}

	private static JsonArray nearbyTransactionTargets(
			ServerLevel level,
			ServerPlayer agent,
			List<RawSpatialObservation.ContainerCandidate> candidates
	) {
		JsonArray values = new JsonArray();
		for (RawSpatialObservation.ContainerCandidate candidate : candidates) {
			if (values.size() == MAX_NEARBY_TRANSACTION_TARGETS) break;
			BlockPos position = new BlockPos(candidate.x(), candidate.y(), candidate.z());
			if (!ObservationVisibility.canSeeBlock(level, agent, position)) continue;
			double distanceSquared = agent.distanceToSqr(Vec3.atCenterOf(position));
			JsonObject json = new JsonObject();
			json.addProperty("x", candidate.x());
			json.addProperty("y", candidate.y());
			json.addProperty("z", candidate.z());
			json.addProperty("blockId", candidate.blockId());
			json.addProperty("distance", finite(Math.sqrt(distanceSquared)));
			json.addProperty("withinInteractionRange", distanceSquared <= 36.0D);
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
			case "minecraft:brewing_stand" -> List.of("menu_transfer", "brewing");
			case "minecraft:anvil" -> List.of("menu_transfer", "anvil");
			case "minecraft:smithing_table" -> List.of("menu_transfer", "smithing");
			case "minecraft:loom" -> List.of("menu_transfer", "loom");
			case "minecraft:stonecutter" -> List.of("menu_transfer", "stonecutter");
			case "minecraft:enchanting_table" -> List.of("menu_transfer", "enchanting");
			default -> List.of();
		};
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

	private static RawPlayerState rawPlayerState(ServerPlayer agent) {
		LivingEntity attacker = agent.getLastHurtByMob();
		return new RawPlayerState(
				finite(agent.getHealth()),
				agent.getFoodData().getFoodLevel(),
				finite(agent.getFoodData().getSaturationLevel()),
				agent.isOnFire(),
				agent.isInWater(),
				Math.max(0, agent.getAirSupply()),
				agent.isInWall(),
				agent.onGround(),
				finite(agent.fallDistance),
				attacker != null && attacker.isAlive() ? attacker.getUUID() : null
			);
	}

	private boolean updateInventory(AgentId agentId, ServerPlayer agent) {
		InventorySnapshot previous = lastInventories.get(agentId);
		if (previous == null || !previous.hasSameShape(agent)) {
			lastInventories.put(agentId, InventorySnapshot.capture(agent));
			return previous != null;
		}
		return previous.matchesAndUpdate(agent);
	}

	private record RawPlayerState(
		double health,
		int foodLevel,
		double saturation,
		boolean onFire,
		boolean inWater,
		int air,
		boolean suffocating,
		boolean onGround,
		double fallDistance,
		java.util.UUID lastAttacker
	) {
	}

	/** Exact, allocation-free comparison of the fields used by inventorySignature while shapes are stable. */
	private static final class InventorySnapshot {
		private static final int MAX_MENU_SLOTS = 64;
		private final int[] inventoryItemIds;
		private final int[] inventoryCounts;
		private final int[] inventoryDamages;
		private final int[] equipmentItemIds;
		private final int[] equipmentCounts;
		private final int[] equipmentDamages;
		private final boolean hasMenu;
		private final int menuSize;
		private final int[] menuItemIds;
		private final int[] menuCounts;
		private final int[] menuDamages;
		private int selectedSlot;
		private int carriedItemId;
		private int carriedCount;
		private int carriedDamage;

		private InventorySnapshot(ServerPlayer agent) {
			Inventory inventory = agent.getInventory();
			this.inventoryItemIds = new int[inventory.getContainerSize()];
			this.inventoryCounts = new int[inventoryItemIds.length];
			this.inventoryDamages = new int[inventoryItemIds.length];
			this.equipmentItemIds = new int[EQUIPMENT_SLOTS.length];
			this.equipmentCounts = new int[equipmentItemIds.length];
			this.equipmentDamages = new int[equipmentItemIds.length];
			this.hasMenu = agent.containerMenu != null;
			this.menuSize = hasMenu ? Math.min(agent.containerMenu.slots.size(), MAX_MENU_SLOTS) : 0;
			this.menuItemIds = new int[menuSize];
			this.menuCounts = new int[menuSize];
			this.menuDamages = new int[menuSize];
			captureValues(agent);
		}

		private static InventorySnapshot capture(ServerPlayer agent) {
			return new InventorySnapshot(agent);
		}

		private boolean hasSameShape(ServerPlayer agent) {
			Inventory inventory = agent.getInventory();
			boolean currentHasMenu = agent.containerMenu != null;
			int currentMenuSize = currentHasMenu
					? Math.min(agent.containerMenu.slots.size(), MAX_MENU_SLOTS) : 0;
			return inventoryItemIds.length == inventory.getContainerSize()
					&& hasMenu == currentHasMenu
					&& menuSize == currentMenuSize;
		}

		private boolean matchesAndUpdate(ServerPlayer agent) {
			boolean changed = false;
			Inventory inventory = agent.getInventory();
			changed |= selectedSlot != inventory.getSelectedSlot();
			selectedSlot = inventory.getSelectedSlot();
			for (int slot = 0; slot < inventoryItemIds.length; slot++) {
				changed |= update(inventoryItemIds, inventoryCounts, inventoryDamages, slot, inventory.getItem(slot));
			}
			for (int slot = 0; slot < EQUIPMENT_SLOTS.length; slot++) {
				changed |= update(equipmentItemIds, equipmentCounts, equipmentDamages, slot,
						agent.getItemBySlot(EQUIPMENT_SLOTS[slot]));
			}
			if (hasMenu) {
				changed |= updateCarried(agent.containerMenu.getCarried());
				for (int slot = 0; slot < menuSize; slot++) {
					changed |= update(menuItemIds, menuCounts, menuDamages, slot,
							agent.containerMenu.getSlot(slot).getItem());
				}
			}
			return changed;
		}

		private void captureValues(ServerPlayer agent) {
			Inventory inventory = agent.getInventory();
			selectedSlot = inventory.getSelectedSlot();
			for (int slot = 0; slot < inventoryItemIds.length; slot++) {
				update(inventoryItemIds, inventoryCounts, inventoryDamages, slot, inventory.getItem(slot));
			}
			for (int slot = 0; slot < EQUIPMENT_SLOTS.length; slot++) {
				update(equipmentItemIds, equipmentCounts, equipmentDamages, slot,
						agent.getItemBySlot(EQUIPMENT_SLOTS[slot]));
			}
			if (hasMenu) {
				updateCarried(agent.containerMenu.getCarried());
				for (int slot = 0; slot < menuSize; slot++) {
					update(menuItemIds, menuCounts, menuDamages, slot, agent.containerMenu.getSlot(slot).getItem());
				}
			}
		}

		private boolean updateCarried(ItemStack stack) {
			int itemId = normalizedItemId(stack);
			int count = stack.isEmpty() ? 0 : stack.getCount();
			int damage = stack.isEmpty() ? 0 : stack.getDamageValue();
			boolean changed = carriedItemId != itemId || carriedCount != count || carriedDamage != damage;
			carriedItemId = itemId;
			carriedCount = count;
			carriedDamage = damage;
			return changed;
		}

		private static boolean update(int[] itemIds, int[] counts, int[] damages, int slot, ItemStack stack) {
			int itemId = normalizedItemId(stack);
			int count = stack.isEmpty() ? 0 : stack.getCount();
			int damage = stack.isEmpty() ? 0 : stack.getDamageValue();
			boolean changed = itemIds[slot] != itemId || counts[slot] != count || damages[slot] != damage;
			itemIds[slot] = itemId;
			counts[slot] = count;
			damages[slot] = damage;
			return changed;
		}

		private static int normalizedItemId(ItemStack stack) {
			return stack.isEmpty() ? -1 : BuiltInRegistries.ITEM.getId(stack.getItem());
		}
	}
}
