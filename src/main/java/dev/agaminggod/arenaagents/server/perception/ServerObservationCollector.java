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
import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class ServerObservationCollector {
	public static final int MAX_ENTITIES = 64;
	public static final int MAX_BLOCKS = 128;
	public static final int BLOCK_RADIUS = 6;
	public static final int MAX_BLOCKS_PER_TYPE = 8;
	/** Sparse first-surface hits let the agent see structures and resources at player-like distances. */
	public static final int MAX_LANDMARKS = 32;
	public static final int LANDMARK_SIGHT_DISTANCE = 256;
	static final int MAX_BLOCK_VISIBILITY_CHECKS = MAX_BLOCKS * 4;
	static final int MAX_BLOCK_VISIBILITY_CHECKS_PER_TYPE = MAX_BLOCKS_PER_TYPE * 4;
	private static final int MAX_LANDMARK_VISIBILITY_CHECKS = MAX_LANDMARKS * 3;
	private static final int[] SIGHT_YAW_OFFSETS = {
		-42, -35, -28, -21, -14, -7, 0, 7, 14, 21, 28, 35, 42
	};
	private static final int[] SIGHT_PITCH_OFFSETS = {-24, -16, -8, 0, 8, 16, 24};
	public static final int MAX_NEARBY_TRANSACTION_TARGETS = 16;
	public static final int MAX_OBSERVATION_TAGS = 32;
	public static final int MAX_TAG_COUNT_ENTRIES = 128;
	private static final int MAX_ENTITY_NAME_CODE_POINTS = 256;
	private static final int SPATIAL_CACHE_CAPACITY = 16;
	/** Position and local mutations invalidate candidates; facing is filtered on every observation. */
	private static final long SPATIAL_CACHE_TICKS = 10L;
	private static final int LANDMARK_CACHE_CAPACITY = 16;
	/** Mutation revision keys provide freshness; this age bounds retained stationary poses. */
	private static final long LANDMARK_CACHE_TICKS = 200L;
	private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();

	private final CodexAgentManager manager;
	private final ServerActionExecutor actionExecutor;
	private final ObservationSectionCache<RawSpatialObservation.Key, RawSpatialObservation> spatialCache =
			new ObservationSectionCache<>(SPATIAL_CACHE_CAPACITY, SPATIAL_CACHE_TICKS, value -> value);
	private final ObservationSectionCache<LandmarkSampleKey, List<VisibleSurfaceCandidate>> landmarkCache =
			new ObservationSectionCache<>(LANDMARK_CACHE_CAPACITY, LANDMARK_CACHE_TICKS, value -> value);
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
		ObservationVisibility.Frame visibility = ObservationVisibility.frame(level, agent);
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
		if (attacker != null && attacker.isAlive() && visibility.canSeeEntity(attacker)) {
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
		observation.add("entities", entities(level, agent, visibility));
		JsonObject spatial = spatialObservation(agentId, level, agent, visibility);
		observation.add("blocks", spatial.get("blocks"));
		observation.add("landmarks", spatial.get("landmarks"));
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
		landmarkCache.invalidateMatching(key -> key.agentId().equals(agentId));
		synchronized (lastRawStates) {
			lastRawStates.remove(agentId);
			lastInventories.remove(agentId);
		}
	}

	/**
	 * Samples the supplied roster, allowing the bridge to reuse the visibility-filtered
	 * roster it already computed for observation scheduling.
	 */
	public List<AgentId> changedActiveAgents(List<AgentRecord> records) {
		Objects.requireNonNull(records, "records must not be null");
		List<AgentId> changed = new ArrayList<>();
		if (manager.server() == null) {
			synchronized (lastRawStates) {
				lastRawStates.clear();
				lastInventories.clear();
			}
			return List.of();
		}
		HashSet<AgentId> tracked = new HashSet<>();
		for (AgentRecord record : records) {
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

	private JsonObject spatialObservation(
			AgentId agentId,
			ServerLevel level,
			ServerPlayer agent,
			ObservationVisibility.Frame visibility
	) {
		BlockPos position = agent.blockPosition();
		RawSpatialObservation.Key key = spatialKey(agentId, level.dimension().identifier().toString(),
				position, worldMutationRevision(level, position, BLOCK_RADIUS));
		RawSpatialObservation.Key previous = spatialKeys.put(agentId, key);
		if (previous != null && !previous.equals(key)) spatialCache.invalidate(previous);
		RawSpatialObservation raw = spatialCache.getOrCompute(
			key, level.getGameTime(), () -> rawSpatialObservation(level, agent, position));
		Vec3 eye = agent.getEyePosition();
		LandmarkSampleKey landmarkKey = new LandmarkSampleKey(
			agentId,
			level.dimension().identifier().toString(),
			position.getX(),
			position.getY(),
			position.getZ(),
			eye.x,
			eye.y,
			eye.z,
			agent.getYRot(),
			agent.getXRot(),
			agent.getY(),
			agent.isDescending(),
			agent.getMainHandItem().getItem(),
			worldMutationRevision(level, position, LANDMARK_SIGHT_DISTANCE + 1)
		);
		List<VisibleSurfaceCandidate> landmarkCandidates = landmarkCache.getOrCompute(
			landmarkKey,
			level.getGameTime(),
			() -> visibleSurfaceCandidates(level, agent, position)
		);
		JsonObject value = new JsonObject();
		value.add("blocks", blocks(level, agent, raw.blocks(), visibility));
		value.add("landmarks", landmarks(level, agent, visibility, landmarkCandidates));
		value.add("nearbyContainers", nearbyTransactionTargets(raw.containers(), level::getBlockState,
				target -> level.hasChunkAt(target) && visibility.canSeeBlock(target), agent.position(),
				target -> agent.isWithinBlockInteractionRange(target, 0.0D)));
		return value;
	}

	static RawSpatialObservation.Key spatialKey(AgentId agentId, String dimension, BlockPos position, long revision) {
		return new RawSpatialObservation.Key(agentId, dimension, position.getX(), position.getY(), position.getZ(), revision);
	}

	private JsonObject currentAction(AgentId agentId) {
		JsonObject json = new JsonObject();
		ServerActionRequest request = actionExecutor.activeRequest(agentId);
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

		var currentInput = AgentInputRuntime.controller(agent).currentState(agentId);
		AgentInputState input = currentInput
				.orElseGet(() -> AgentInputState.idle(
						agent.getYRot(),
						agent.getXRot(),
						agent.getInventory().getSelectedSlot()
				));
		JsonObject inputJson = new JsonObject();
		inputJson.addProperty("active", currentInput.isPresent());
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
		int count = 0;
		for (var effect : agent.getActiveEffects()) {
			if (count++ == 32) break;
			JsonObject json = new JsonObject();
			json.addProperty("effectId", effect.getEffect().unwrapKey()
					.map(key -> key.identifier().toString()).orElse(effect.getDescriptionId()));
			json.addProperty("amplifier", effect.getAmplifier());
			json.addProperty("duration", effect.getDuration());
			values.add(json);
		}
		return values;
	}

	private static JsonObject inventory(ServerPlayer agent) {
		JsonObject inventory = new JsonObject();
		JsonArray items = new JsonArray();
		Map<String, Integer> tagCounts = new HashMap<>();
		for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
			ItemStack stack = agent.getItemBySlot(slot);
			if (stack.isEmpty()) continue;
			List<String> tags = tagValues(stack.typeHolder());
			JsonObject item = item(stack, tags);
			item.addProperty("slot", slot.getName());
			items.add(item);
			addTagCounts(tagCounts, stack.getCount(), tags);
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
			List<String> tags = tagValues(stack.typeHolder());
			JsonObject item = item(stack, tags);
			item.addProperty("slot", slot);
			item.addProperty("hotbar", slot < 9);
			items.add(item);
			addTagCounts(tagCounts, stack.getCount(), tags);
		}
		inventory.add("items", items);
		JsonObject counts = new JsonObject();
		tagCounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(MAX_TAG_COUNT_ENTRIES)
				.forEach(entry -> counts.addProperty(entry.getKey(), entry.getValue()));
		inventory.add("tagCounts", counts);
		inventory.addProperty("selectedItem", BuiltInRegistries.ITEM.getKey(agent.getMainHandItem().getItem()).toString());
		return inventory;
	}

	private static JsonObject item(ItemStack stack, List<String> tags) {
		JsonObject item = new JsonObject();
		item.addProperty("itemId", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		item.addProperty("count", stack.getCount());
		item.addProperty("damage", stack.getDamageValue());
		item.addProperty("maxDamage", stack.getMaxDamage());
		item.add("tags", tags(tags));
		return item;
	}

	private static void addTagCounts(Map<String, Integer> counts, int stackCount, List<String> tags) {
		tags.forEach(tag -> counts.merge(tag, stackCount, Integer::sum));
	}

	static JsonArray tags(Holder<?> holder) {
		return tags(tagValues(holder));
	}

	private static JsonArray tags(List<String> tags) {
		JsonArray values = new JsonArray();
		tags.forEach(values::add);
		return values;
	}

	private static List<String> tagValues(Holder<?> holder) {
		List<String> cached;
		synchronized (TAG_VALUES) {
			cached = TAG_VALUES.get(holder);
			if (cached == null) {
				cached = holder.tags().map(tag -> "#" + tag.location().toString())
						.sorted().limit(MAX_OBSERVATION_TAGS).toList();
				TAG_VALUES.put(holder, cached);
			}
		}
		return cached;
	}

	/** Clears holder-derived tag values after datapack tags are reloaded. */
	public static void clearTagCache() {
		synchronized (TAG_VALUES) {
			TAG_VALUES.clear();
		}
	}

	private static JsonArray entities(
			ServerLevel level,
			ServerPlayer agent,
			ObservationVisibility.Frame visibility
	) {
		JsonArray values = new JsonArray();
		ArrayList<EntityCandidate> candidates = new ArrayList<>();
		for (Entity entity : level.getEntities(agent, agent.getBoundingBox().inflate(32.0D), Entity::isAlive)) {
			if (visibility.isEntityWithinView(entity)) {
				candidates.add(new EntityCandidate(entity, agent.distanceToSqr(entity)));
			}
		}
		for (EntityCandidate candidate : selectNearestVisible(
				candidates,
				Comparator.comparingDouble(EntityCandidate::distanceSquared),
				MAX_ENTITIES,
				entry -> visibility.hasLineOfSight(entry.entity())
		)) {
			Entity entity = candidate.entity();
					JsonObject json = new JsonObject();
					json.addProperty("uuid", entity.getUUID().toString());
					json.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
					json.addProperty("name", boundedEntityName(entity.getName().getString()));
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
		}
		return values;
	}

	static <T> List<T> selectNearestVisible(
			List<T> candidates,
			Comparator<? super T> nearestFirst,
			int maximumEntries,
			Predicate<? super T> visible
	) {
		Objects.requireNonNull(candidates, "candidates must not be null");
		Objects.requireNonNull(nearestFirst, "nearestFirst must not be null");
		Objects.requireNonNull(visible, "visible must not be null");
		if (maximumEntries <= 0) throw new IllegalArgumentException("maximumEntries must be positive");
		ArrayList<T> ordered = new ArrayList<>(candidates);
		ordered.sort(nearestFirst);
		ArrayList<T> selected = new ArrayList<>(Math.min(maximumEntries, ordered.size()));
		for (T candidate : ordered) {
			if (!visible.test(candidate)) continue;
			selected.add(candidate);
			if (selected.size() == maximumEntries) break;
		}
		return List.copyOf(selected);
	}

	private static RawSpatialObservation rawSpatialObservation(ServerLevel level, ServerPlayer agent, BlockPos center) {
		ArrayList<BlockObservationOrdering.Candidate> candidates = new ArrayList<>();
		ArrayList<RawSpatialObservation.ContainerCandidate> containers = new ArrayList<>();
		BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
		for (int y = -3; y <= 3; y++) {
			for (int x = -BLOCK_RADIUS; x <= BLOCK_RADIUS; x++) {
				for (int z = -BLOCK_RADIUS; z <= BLOCK_RADIUS; z++) {
					position.set(center.getX() + x, center.getY() + y, center.getZ() + z);
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
								agent.distanceToSqr(position.getX() + 0.5D, position.getY() + 0.5D,
										position.getZ() + 0.5D)));
					}
				}
			}
		}
		List<BlockObservationOrdering.Candidate> blocks = BlockObservationOrdering.ordered(candidates);
		containers.sort(Comparator.comparingDouble(RawSpatialObservation.ContainerCandidate::distanceSquared)
				.thenComparingInt(RawSpatialObservation.ContainerCandidate::y)
				.thenComparingInt(RawSpatialObservation.ContainerCandidate::x)
				.thenComparingInt(RawSpatialObservation.ContainerCandidate::z));
		return new RawSpatialObservation(blocks,
				containers.stream().limit(MAX_NEARBY_TRANSACTION_TARGETS * 4L).toList());
	}

	private static boolean isVisualLandmark(String blockId) {
		String path = blockId.substring(blockId.indexOf(':') + 1);
		return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_ore")
				|| path.endsWith("_leaves") || path.endsWith("_flower") || path.endsWith("_mushroom")
				|| path.equals("cobblestone") || path.equals("mossy_cobblestone")
				|| path.endsWith("_stone_bricks") || path.endsWith("_brick") || path.endsWith("_bricks")
				|| path.endsWith("_planks") || path.equals("glass") || path.equals("glass_pane")
				|| path.endsWith("_glass") || path.endsWith("_glass_pane")
				|| path.endsWith("_terracotta") || path.endsWith("_concrete")
				|| path.endsWith("_wall") || path.endsWith("_stairs") || path.endsWith("_slab")
				|| path.equals("water") || path.equals("lava") || path.equals("crafting_table")
				|| path.equals("furnace") || path.equals("chest") || path.equals("barrel")
				|| path.contains("portal") || path.equals("hay_block") || path.equals("pumpkin")
				|| path.equals("melon") || path.equals("torch") || path.endsWith("_torch")
				|| path.equals("lantern") || path.equals("soul_lantern") || path.equals("bell")
				|| path.equals("campfire") || path.equals("soul_campfire") || path.equals("bookshelf");
	}

	private static int landmarkPriority(String blockId) {
		String path = blockId.substring(blockId.indexOf(':') + 1);
		if (path.endsWith("_log") || path.endsWith("_wood")) return 0;
		if (isStructureLandmark(path)) return 1;
		if (path.endsWith("_ore")) return 2;
		if (path.equals("water") || path.equals("lava") || path.contains("portal")) return 3;
		if (path.equals("crafting_table") || path.equals("furnace") || path.equals("chest") || path.equals("barrel")) return 4;
		return 5;
	}

	private static boolean isStructureLandmark(String path) {
		return path.equals("cobblestone") || path.equals("mossy_cobblestone")
				|| path.endsWith("_stone_bricks") || path.endsWith("_brick") || path.endsWith("_bricks")
				|| path.endsWith("_planks") || path.equals("glass") || path.equals("glass_pane")
				|| path.endsWith("_glass") || path.endsWith("_glass_pane")
				|| path.endsWith("_terracotta") || path.endsWith("_concrete")
				|| path.endsWith("_wall") || path.endsWith("_stairs") || path.endsWith("_slab")
				|| path.equals("torch") || path.endsWith("_torch") || path.equals("lantern")
				|| path.equals("soul_lantern") || path.equals("bell") || path.equals("campfire")
				|| path.equals("soul_campfire") || path.equals("bookshelf");
	}

	private static JsonArray blocks(
			ServerLevel level,
			ServerPlayer agent,
			List<BlockObservationOrdering.Candidate> candidates,
			ObservationVisibility.Frame visibility
	) {
		BlockPos center = agent.blockPosition();
		JsonArray values = new JsonArray();
		Map<BlockObservationOrdering.Candidate, BlockObservationOrdering.Candidate> currentCandidates = new HashMap<>();
		for (BlockObservationOrdering.Candidate candidate : BlockObservationOrdering.selectOrderedWithVisibilityBudget(
				candidates,
				MAX_BLOCKS,
				MAX_BLOCKS_PER_TYPE,
				MAX_BLOCK_VISIBILITY_CHECKS,
				MAX_BLOCK_VISIBILITY_CHECKS_PER_TYPE,
				selectable -> {
					BlockPos target = center.offset(selectable.x(), selectable.y(), selectable.z());
					if (!level.hasChunkAt(target)) return false;
					BlockObservationOrdering.Candidate current = refreshCurrentBlockCandidate(
							selectable, center, level::getBlockState);
					if (current == null) return false;
					currentCandidates.put(selectable, current);
					return visibility.isBlockWithinView(target);
				},
				selectable -> visibility.hasLineOfSight(
						center.offset(selectable.x(), selectable.y(), selectable.z()))
		)) {
			BlockObservationOrdering.Candidate current = currentCandidates.get(candidate);
			if (current == null) continue;
			BlockPos position = center.offset(candidate.x(), candidate.y(), candidate.z());
			BlockState state = level.getBlockState(position);
			JsonObject json = new JsonObject();
			json.addProperty("x", position.getX());
			json.addProperty("y", position.getY());
			json.addProperty("z", position.getZ());
			json.addProperty("blockId", current.blockId());
			json.add("tags", tags(state.typeHolder()));
			JsonArray placeableFaces = new JsonArray();
			boolean withinInteractionRange = agent.isWithinBlockInteractionRange(position, 1.0D);
			BlockPlacementAttemptPolicy.supportedFaces(face ->
					withinInteractionRange
							&& state.isFaceSturdy(level, position, face)
							&& level.getBlockState(position.relative(face)).canBeReplaced()
			).forEach(face -> placeableFaces.add(face.getSerializedName()));
			json.add("placeableFaces", placeableFaces);
			values.add(json);
		}
		return values;
	}

	private static JsonArray landmarks(
			ServerLevel level,
			ServerPlayer agent,
			ObservationVisibility.Frame visibility,
			List<VisibleSurfaceCandidate> candidates
	) {
		JsonArray values = new JsonArray();
		int visibilityChecks = 0;
		HashSet<String> types = new HashSet<>();
		BlockPos center = agent.blockPosition();
		for (VisibleSurfaceCandidate candidate : candidates) {
			if (values.size() == MAX_LANDMARKS || visibilityChecks >= MAX_LANDMARK_VISIBILITY_CHECKS) break;
			BlockPos position = center.offset(candidate.x(), candidate.y(), candidate.z());
			if (!visibility.isBlockWithinView(position)) continue;
			visibilityChecks++;
			if (!visibility.hasLineOfSight(position)) continue;
			String type = candidate.blockId().substring(candidate.blockId().indexOf(':') + 1);
			String family = type.endsWith("_log") || type.endsWith("_wood") ? "wood"
					: type.endsWith("_ore") ? "ore" : type;
			if (!types.add(family) && values.size() >= MAX_LANDMARKS / 2) continue;
			Vec3 delta = Vec3.atCenterOf(position).subtract(agent.getEyePosition());
			double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
			double targetYaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
			double bearing = net.minecraft.util.Mth.wrapDegrees((float) (targetYaw - agent.getYRot()));
			double elevation = Math.toDegrees(Math.atan2(delta.y, horizontal));
			JsonObject json = new JsonObject();
			json.addProperty("x", position.getX());
			json.addProperty("y", position.getY());
			json.addProperty("z", position.getZ());
			json.addProperty("blockId", candidate.blockId());
			json.addProperty("distance", finite(Math.sqrt(candidate.distanceSquared())));
			json.addProperty("bearing", finite(bearing));
			json.addProperty("elevation", finite(elevation));
			json.add("tags", tags(agent.level().getBlockState(position).typeHolder()));
			values.add(json);
		}
		return values;
	}

	/**
	 * Samples the first visible surface along a sparse camera fan. This scales with the number of
	 * sight rays instead of the cube of the sight distance, so long-range vision remains bounded.
	 */
	private static List<VisibleSurfaceCandidate> visibleSurfaceCandidates(
			ServerLevel level,
			ServerPlayer agent,
			BlockPos center
	) {
		Map<Long, VisibleSurfaceCandidate> candidates = new HashMap<>();
		Map<Long, Boolean> loadedChunks = new HashMap<>();
		Vec3 eye = agent.getEyePosition();
		for (int pitchOffset : SIGHT_PITCH_OFFSETS) {
			float pitch = net.minecraft.util.Mth.clamp(agent.getXRot() + pitchOffset, -90.0F, 90.0F);
			for (int yawOffset : SIGHT_YAW_OFFSETS) {
				float yaw = agent.getYRot() + yawOffset;
				Vec3 direction = Vec3.directionFromRotation(pitch, yaw);
				Vec3 endpoint = loadedSightEndpoint(eye, direction,
						position -> hasLoadedChunk(level, position, loadedChunks));
				if (endpoint == null) continue;
				BlockHitResult hit = level.clip(new ClipContext(
						eye,
						endpoint,
						ClipContext.Block.VISUAL,
						ClipContext.Fluid.ANY,
						agent
				));
				if (hit.getType() != HitResult.Type.BLOCK) continue;
				BlockPos position = hit.getBlockPos();
				if (!hasLoadedChunk(level, position, loadedChunks)) continue;
				double distanceSquared = agent.distanceToSqr(
						position.getX() + 0.5D,
						position.getY() + 0.5D,
						position.getZ() + 0.5D
				);
				if (distanceSquared <= (double) BLOCK_RADIUS * BLOCK_RADIUS) continue;
				String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(position).getBlock()).toString();
				if (!isVisualLandmark(blockId)) continue;
				candidates.putIfAbsent(position.asLong(), new VisibleSurfaceCandidate(
						position.getX() - center.getX(),
						position.getY() - center.getY(),
						position.getZ() - center.getZ(),
						blockId,
						distanceSquared
				));
			}
		}
		ArrayList<VisibleSurfaceCandidate> ordered = new ArrayList<>(candidates.values());
		ordered.sort(Comparator
				.comparingInt((VisibleSurfaceCandidate candidate) -> landmarkPriority(candidate.blockId()))
				.thenComparingDouble(VisibleSurfaceCandidate::distanceSquared)
				.thenComparingInt(VisibleSurfaceCandidate::y)
				.thenComparingInt(VisibleSurfaceCandidate::x)
				.thenComparingInt(VisibleSurfaceCandidate::z));
		return List.copyOf(ordered);
	}

	/** Keep clipping inside the contiguous loaded view instead of making long rays load chunks. */
	static Vec3 loadedSightEndpoint(
			Vec3 origin,
			Vec3 direction,
			Predicate<BlockPos> loaded
	) {
		BlockPos start = BlockPos.containing(origin);
		if (!loaded.test(start)) return null;
		int chunkX = start.getX() >> 4;
		int chunkZ = start.getZ() >> 4;
		// Vanilla clipping extends its start backwards by 1e-7 of the ray length.
		// Check that tiny segment too, including either side of a chunk corner.
		BlockPos clipStart = BlockPos.containing(origin.subtract(direction.scale(LANDMARK_SIGHT_DISTANCE * 1.0E-7D)));
		for (int x = Math.min(chunkX, clipStart.getX() >> 4); x <= Math.max(chunkX, clipStart.getX() >> 4); x++) {
			for (int z = Math.min(chunkZ, clipStart.getZ() >> 4); z <= Math.max(chunkZ, clipStart.getZ() >> 4); z++) {
				if ((x != chunkX || z != chunkZ) && !loaded.test(new BlockPos(x << 4, start.getY(), z << 4))) return null;
			}
		}
		int stepX = (int) Math.signum(direction.x);
		int stepZ = (int) Math.signum(direction.z);
		double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0D / Math.abs(direction.x);
		double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0D / Math.abs(direction.z);
		double nextX = stepX == 0 ? Double.POSITIVE_INFINITY
				: ((chunkX + (stepX > 0 ? 1 : 0)) * 16.0D - origin.x) / direction.x;
		double nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY
				: ((chunkZ + (stepZ > 0 ? 1 : 0)) * 16.0D - origin.z) / direction.z;
		while (true) {
			double boundary = Math.min(nextX, nextZ);
			if (boundary > LANDMARK_SIGHT_DISTANCE) return origin.add(direction.scale(LANDMARK_SIGHT_DISTANCE));
			// Block clipping visits a side cell when two chunk boundaries coincide.
			if (nextX == nextZ && (!loaded.test(new BlockPos((chunkX + stepX) << 4, start.getY(), chunkZ << 4))
					|| !loaded.test(new BlockPos(chunkX << 4, start.getY(), (chunkZ + stepZ) << 4)))) {
				return boundary <= 0.0D ? null : origin.add(direction.scale(Math.max(0.0D, boundary - 1.0E-4D)));
			}
			if (nextX <= boundary) {
				chunkX += stepX;
				nextX += deltaX;
			}
			if (nextZ <= boundary) {
				chunkZ += stepZ;
				nextZ += deltaZ;
			}
			if (!loaded.test(new BlockPos(chunkX << 4, start.getY(), chunkZ << 4))) {
				return boundary <= 0.0D ? null : origin.add(direction.scale(Math.max(0.0D, boundary - 1.0E-4D)));
			}
		}
	}

	private static boolean hasLoadedChunk(ServerLevel level, BlockPos position, Map<Long, Boolean> loadedChunks) {
		long chunkKey = (((long) (position.getX() >> 4)) << 32)
				^ ((long) (position.getZ() >> 4) & 0xffffffffL);
		Boolean loaded = loadedChunks.get(chunkKey);
		if (loaded == null) {
			loaded = level.hasChunkAt(position);
			loadedChunks.put(chunkKey, loaded);
		}
		return loaded;
	}

	private static long worldMutationRevision(ServerLevel level, BlockPos center, int radius) {
		if (level instanceof WorldMutationRevisionAccess revision) {
			return revision.arenaagents$worldMutationRevision(center, radius);
		}
		// Verification doubles may not load the Fabric mixin; changing game time is
		// a conservative fallback that disables cross-tick reuse rather than risking stale facts.
		return level.getGameTime();
	}

	/** Re-reads cached spatial candidates so mutation cannot leave stale block IDs or air entries. */
	static List<BlockObservationOrdering.Candidate> refreshCurrentBlockCandidates(
			List<BlockObservationOrdering.Candidate> candidates,
			BlockPos center,
			Function<BlockPos, BlockState> stateAt
	) {
		Objects.requireNonNull(candidates, "candidates must not be null");
		Objects.requireNonNull(center, "center must not be null");
		Objects.requireNonNull(stateAt, "stateAt must not be null");
		ArrayList<BlockObservationOrdering.Candidate> current = new ArrayList<>(candidates.size());
		for (BlockObservationOrdering.Candidate candidate : candidates) {
			BlockObservationOrdering.Candidate refreshed = refreshCurrentBlockCandidate(candidate, center, stateAt);
			if (refreshed != null) current.add(refreshed);
		}
		return List.copyOf(current);
	}

	private static BlockObservationOrdering.Candidate refreshCurrentBlockCandidate(
			BlockObservationOrdering.Candidate candidate,
			BlockPos center,
			Function<BlockPos, BlockState> stateAt
	) {
		BlockPos position = center.offset(candidate.x(), candidate.y(), candidate.z());
		BlockState state = Objects.requireNonNull(stateAt.apply(position), "stateAt must not return null");
		if (state.isAir()) return null;
		String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		return new BlockObservationOrdering.Candidate(candidate.x(), candidate.y(), candidate.z(), blockId);
	}

	static JsonArray nearbyTransactionTargets(
			List<RawSpatialObservation.ContainerCandidate> candidates,
			Function<BlockPos, BlockState> stateAt,
			Predicate<BlockPos> visible,
			Vec3 observerPosition,
			Predicate<BlockPos> withinInteractionRange
	) {
		JsonArray values = new JsonArray();
		for (RawSpatialObservation.ContainerCandidate candidate : candidates) {
			if (values.size() == MAX_NEARBY_TRANSACTION_TARGETS) break;
			BlockPos position = new BlockPos(candidate.x(), candidate.y(), candidate.z());
			if (!visible.test(position)) continue;
			BlockState state = Objects.requireNonNull(stateAt.apply(position), "stateAt must not return null");
			String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			List<String> currentCapabilities = transactionCapabilities(blockId);
			if (currentCapabilities.isEmpty()) continue;
			double distanceSquared = observerPosition.distanceToSqr(
					candidate.x() + 0.5D, candidate.y() + 0.5D, candidate.z() + 0.5D);
			JsonObject json = new JsonObject();
			json.addProperty("x", candidate.x());
			json.addProperty("y", candidate.y());
			json.addProperty("z", candidate.z());
			json.addProperty("blockId", blockId);
			json.addProperty("distance", finite(Math.sqrt(distanceSquared)));
			json.addProperty("withinInteractionRange", withinInteractionRange.test(position));
			JsonArray capabilities = new JsonArray();
			currentCapabilities.forEach(capabilities::add);
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

	static String boundedEntityName(String value) {
		Objects.requireNonNull(value, "value must not be null");
		int codePointCount = value.codePointCount(0, value.length());
		if (codePointCount <= MAX_ENTITY_NAME_CODE_POINTS) return value;
		return value.substring(0, value.offsetByCodePoints(0, MAX_ENTITY_NAME_CODE_POINTS));
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

	private record EntityCandidate(Entity entity, double distanceSquared) {
		private EntityCandidate {
			Objects.requireNonNull(entity, "entity must not be null");
		}
	}

	record LandmarkSampleKey(
			AgentId agentId,
			String dimension,
			int centerX,
			int centerY,
			int centerZ,
			double eyeX,
			double eyeY,
			double eyeZ,
			float yaw,
			float pitch,
			double feetY,
			boolean descending,
			net.minecraft.world.item.Item heldItem,
			long mutationRevision
	) {
		LandmarkSampleKey {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(heldItem, "heldItem must not be null");
			if (Objects.requireNonNull(dimension, "dimension must not be null").isBlank()) {
				throw new IllegalArgumentException("dimension must not be blank");
			}
			if (!Double.isFinite(eyeX) || !Double.isFinite(eyeY) || !Double.isFinite(eyeZ)
					|| !Double.isFinite(feetY) || !Float.isFinite(yaw) || !Float.isFinite(pitch) || mutationRevision < 0L) {
				throw new IllegalArgumentException("landmark sample key contains invalid geometry");
			}
		}
	}

	/** Exact, allocation-free comparison of the fields used by inventorySignature while shapes are stable. */
	static final class InventorySnapshot {
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

		static InventorySnapshot capture(ServerPlayer agent) {
			return new InventorySnapshot(agent);
		}

		boolean hasSameShape(ServerPlayer agent) {
			Inventory inventory = agent.getInventory();
			boolean currentHasMenu = agent.containerMenu != null;
			int currentMenuSize = currentHasMenu
					? Math.min(agent.containerMenu.slots.size(), MAX_MENU_SLOTS) : 0;
			return inventoryItemIds.length == inventory.getContainerSize()
					&& hasMenu == currentHasMenu
					&& menuSize == currentMenuSize;
		}

		boolean matchesAndUpdate(ServerPlayer agent) {
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
