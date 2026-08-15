package dev.agaminggod.arenaagents.server.runtime;

import carpet.helpers.EntityPlayerActionPack;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleReducer;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.AgentChatReporter;
import dev.agaminggod.arenaagents.server.AgentRuntimeRouter;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import dev.agaminggod.arenaagents.server.perception.ObservationVisibility;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerController;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerBuildSequenceController;
import dev.agaminggod.arenaagents.server.runtime.controller.CombatIntent;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerCombatController;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerNavigationController;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerItemPickupController;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class ServerActionExecutor {
	private static final Set<ActionType> ARENA_SCRIPT_PRIMITIVES = Set.of(
			ActionType.MOVE_TO, ActionType.NAVIGATE_TO, ActionType.LOOK_AT, ActionType.ATTACK,
			ActionType.SELECT_ITEM, ActionType.USE_ITEM, ActionType.BREAK_BLOCK, ActionType.PLACE_BLOCK,
			ActionType.CHAT, ActionType.WAIT, ActionType.SET_DOOR, ActionType.DROP_ITEM,
			ActionType.TRANSFER_CONTAINER, ActionType.CRAFT_INVENTORY, ActionType.CRAFT_TABLE,
			ActionType.FURNACE_TRANSACTION, ActionType.EQUIP_ITEM, ActionType.SELECT_TOOL,
			ActionType.BLOCK_WITH_SHIELD, ActionType.USE_RANGED
	);
	private static final double MAX_INTERACTION_DISTANCE_SQUARED = 36.0D;
	private static final Direction[] HORIZONTAL_PLACEMENT_DIRECTIONS = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};
	private static final long DEFAULT_TIMEOUT_MS = 60_000L;
	private static final long MOVEMENT_STALL_TIMEOUT_MS = 4_000L;
	private static final long PLACE_TIMEOUT_MS = 5_000L;
	private static final double PROGRESS_EMISSION_DELTA = 0.05D;
	private static final long PROGRESS_HEARTBEAT_MS = 1_000L;

	private final CodexAgentManager manager;
	private final AgentRuntimeRouter router;
	private final Consumer<ServerActionResult> resultSink;
	private final Consumer<ServerActionProgress> progressSink;
	private final ServerProtectionPolicy protection;
	private final ResourceLeaseManager resourceLeases;
	private final AdvancedInteractionService advancedInteractions;
	private final Map<AgentId, ActiveAction> active = new LinkedHashMap<>();
	private final Map<AgentId, CleanupRetry<ServerActionResult>> pendingCompletions = new LinkedHashMap<>();
	private final Map<AgentId, ServerActionResult> lastResults = new LinkedHashMap<>();

	public ServerActionExecutor(CodexAgentManager manager, Consumer<ServerActionResult> resultSink) {
		this(manager, resultSink, progress -> { }, ServerProtectionPolicy.TRUSTED_LOCAL_OPERATOR);
	}

	public ServerActionExecutor(
			CodexAgentManager manager,
			Consumer<ServerActionResult> resultSink,
			Consumer<ServerActionProgress> progressSink
	) {
		this(manager, resultSink, progressSink, ServerProtectionPolicy.TRUSTED_LOCAL_OPERATOR);
	}

	public ServerActionExecutor(
			CodexAgentManager manager,
			Consumer<ServerActionResult> resultSink,
			ServerProtectionPolicy protection
	) {
		this(manager, resultSink, progress -> { }, protection);
	}

	public ServerActionExecutor(
			CodexAgentManager manager,
			Consumer<ServerActionResult> resultSink,
			Consumer<ServerActionProgress> progressSink,
			ServerProtectionPolicy protection
	) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
		this.router = new AgentRuntimeRouter(manager);
		this.resultSink = Objects.requireNonNull(resultSink, "resultSink must not be null");
		this.progressSink = Objects.requireNonNull(progressSink, "progressSink must not be null");
		this.protection = Objects.requireNonNull(protection, "protection must not be null");
		this.resourceLeases = new ResourceLeaseManager();
		this.advancedInteractions = new AdvancedInteractionService(protection, resourceLeases);
	}

	/** Legacy scenario/operator path. Model-authored commands must use submitProgramPrimitive. */
	synchronized void submitLegacy(ServerActionRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		if (active.containsKey(request.agentId()) || pendingCompletions.containsKey(request.agentId())) {
			throw new AgentDomainException("ACTION_ALREADY_ACTIVE", "Agent already has an active action");
		}
		if (request.type() == ActionType.COMPLETE_GOAL) {
			var record = manager.registry().require(request.agentId());
			AgentLifecycleReducer.completeGoal(
					record,
					request.goalRevision(),
					System.currentTimeMillis()
			);
			AgentChatReporter.completed(manager, record, string(request.arguments(), "summary"));
			emit(request, ServerActionState.SUCCEEDED, "GOAL_COMPLETED", string(request.arguments(), "summary"), 0L);
			router.goalCompleted(request.agentId(), request.goalRevision());
			return;
		}

		router.actionAccepted(request.agentId(), request.goalRevision());
		ServerPlayer player = null;
		try {
			player = manager.findAgentPlayer(request.agentId()).orElseThrow(
					() -> new AgentDomainException("AGENT_PLAYER_MISSING", "Agent player is not loaded")
			);
			AgentChatReporter.acting(manager, manager.registry().require(request.agentId()), request);
			active.put(request.agentId(), createAction(request, player));
		} catch (RuntimeException exception) {
			try {
				if (player != null) OfflineAgentPlayers.stop(player);
			} catch (RuntimeException stopFailure) {
				exception.addSuppressed(stopFailure);
			}
			try {
				router.actionFinished(request.agentId(), request.goalRevision());
			} catch (AgentDomainException stale) { }
			String reason = exception instanceof AgentDomainException domain ? domain.code() : "ACTION_REJECTED";
			emit(request, ServerActionState.FAILED, reason, safeMessage(exception), 0L);
		}
	}

	/** Executes only a model-authored physical primitive received through the coordinator bridge. */
	public synchronized void submitProgramPrimitive(ServerActionRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		requireArenaScriptPrimitive(request.type());
		submitLegacy(request);
	}

	public static boolean isArenaScriptPrimitive(ActionType type) {
		return ARENA_SCRIPT_PRIMITIVES.contains(Objects.requireNonNull(type, "type must not be null"));
	}

	public static void requireArenaScriptPrimitive(ActionType type) {
		if (!isArenaScriptPrimitive(type)) {
			throw new AgentDomainException("UNSUPPORTED_ARENA_SCRIPT_ACTION", "ArenaScript cannot invoke " + type.wireName());
		}
	}

	public synchronized void tick() {
		long now = System.currentTimeMillis();
		for (ActiveAction action : new ArrayList<>(active.values())) {
			CleanupRetry<ServerActionResult> pending = pendingCompletions.get(action.request().agentId());
			if (pending != null) {
				finish(action, pending.pending());
				continue;
			}
			ServerActionResult result;
			try {
				result = action.tick(now);
			} catch (RuntimeException exception) {
				result = action.result(ServerActionState.FAILED, failureReason(exception), safeMessage(exception), now);
			}
			if (result != null) {
				finish(action, result);
			} else {
				ServerActionProgress progress = action.progress(now);
				if (progress != null) publishProgressBestEffort(progressSink, progress);
			}
		}
	}

	static void publishProgressBestEffort(
			Consumer<ServerActionProgress> progressSink,
			ServerActionProgress progress
	) {
		try {
			progressSink.accept(progress);
		} catch (RuntimeException ignored) {
			// Progress is advisory telemetry; bridge backpressure must never abort the server tick.
		}
	}

	public synchronized boolean cancel(AgentId agentId, String reason) {
		ActiveAction action = active.get(agentId);
		if (action == null) return false;
		String cancellationReason = reason == null ? "Action cancelled" : reason;
		CleanupRetry<ServerActionResult> pending = pendingCompletions.get(agentId);
		if (pending != null) {
			finish(action, pending.pending());
			return true;
		}
		ServerActionResult result = action.result(
				ServerActionState.CANCELLED, "ACTION_CANCELLED", cancellationReason, System.currentTimeMillis());
		try {
			action.cancel(cancellationReason);
		} catch (RuntimeException teardownFailure) {
			CleanupRetry<ServerActionResult> retry = new CleanupRetry<>();
			retry.retain(result);
			pendingCompletions.put(agentId, retry);
			return true;
		}
		complete(action, result);
		return true;
	}

	public synchronized boolean cancel(AgentId agentId, long goalRevision, String actionId, String reason) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(actionId, "actionId must not be null");
		ActiveAction action = active.get(agentId);
		if (action == null) return false;
		if (!matchesCancellation(action.request(), goalRevision, actionId)) {
			throw new AgentDomainException("STALE_ACTION", "Cancellation does not match the active action");
		}
		return cancel(agentId, reason);
	}

	static boolean matchesCancellation(ServerActionRequest request, long goalRevision, String actionId) {
		Objects.requireNonNull(request, "request must not be null");
		return request.goalRevision() == goalRevision && request.actionId().equals(actionId);
	}

	private void complete(ActiveAction action, ServerActionResult result) {
		active.remove(action.request().agentId(), action);
		pendingCompletions.remove(action.request().agentId());
		releaseResourceLease(action);
		try {
			router.actionFinished(action.request().agentId(), action.request().goalRevision());
		} catch (AgentDomainException stale) { }
		publish(result);
	}

	public synchronized ServerActionResult lastResult(AgentId agentId) {
		return lastResults.get(agentId);
	}

	public synchronized List<ServerActionRequest> activeRequests() {
		return active.values().stream().map(ActiveAction::request).toList();
	}

	private ActiveAction createAction(ServerActionRequest request, ServerPlayer player) {
		if (player.gameMode.getGameModeForPlayer() == GameType.ADVENTURE
				&& (request.type() == ActionType.BREAK_BLOCK || request.type() == ActionType.PLACE_BLOCK
				|| request.type() == ActionType.BUILD_SEQUENCE)) {
			throw new AgentDomainException("GAME_MODE_RESTRICTED", "Adventure agents cannot break or place blocks");
		}
		JsonObject arguments = request.arguments();
		return switch (request.type()) {
			case MOVE_TO, NAVIGATE_TO -> ActiveAction.controller(
					request,
					player,
					new ServerNavigationController(
							new Vec3(number(arguments, "x"), number(arguments, "y"), number(arguments, "z")),
							number(arguments, "tolerance"),
							bool(arguments, "sprint"),
							System.currentTimeMillis(),
							arguments.has("timeoutMs") ? integer(arguments, "timeoutMs") : DEFAULT_TIMEOUT_MS
					)
				);
			case LOOK_AT -> ActiveAction.immediate(request, player, () ->
					OfflineAgentPlayers.actions(player).lookAt(new Vec3(
							number(arguments, "x"),
							number(arguments, "y"),
							number(arguments, "z")
					)));
			case ATTACK -> ActiveAction.immediate(request, player,
					() -> attack(player, string(arguments, "targetSelector")));
			case SELECT_ITEM -> ActiveAction.immediate(request, player,
					() -> selectItem(player, string(arguments, "itemId")));
			case USE_ITEM -> ActiveAction.use(request, player, integer(arguments, "durationMs"));
			case BREAK_BLOCK -> ActiveAction.breakBlock(
					request,
					player,
					blockPosition(arguments),
					integer(arguments, "timeoutMs")
			);
			case PLACE_BLOCK -> {
				BlockPos position = blockPosition(arguments);
				String itemId = string(arguments, "itemId");
				String initialBlockId = blockId(player.level().getBlockState(position));
				String expectedBlockId = expectedBlockId(itemId);
				DesiredBlockState desiredBlockState = DesiredBlockState.parse(
						arguments.has("desiredState") && !arguments.get("desiredState").isJsonNull()
								? arguments.get("desiredState").getAsString() : null,
						expectedBlockId
				);
				Direction requestedFace = Direction.byName(string(arguments, "face"));
				if (!initialBlockId.equals(expectedBlockId)) {
					validatePlaceBlock(protection, player, position, requestedFace, itemId);
				}
				int initialItemCount = inventoryItemCount(player, itemId);
				String resourceKey = blockResourceKey(player, position);
				if (!resourceLeases.acquire(
						resourceKey,
						request.agentId(),
						System.currentTimeMillis(),
						PLACE_TIMEOUT_MS
				)) {
					throw new AgentDomainException("RESOURCE_BUSY", "Another agent is already placing at this target");
				}
				try {
					ActiveAction action = ActiveAction.placeBlock(
							request,
							player,
							() -> placeBlock(protection, player, position, requestedFace, itemId, desiredBlockState),
							position,
							initialBlockId,
							expectedBlockId,
							desiredBlockState,
							requestedFace,
							itemId,
							initialItemCount
					);
					action.resourceLeaseKey = resourceKey;
					yield action;
				} catch (RuntimeException exception) {
					resourceLeases.release(resourceKey, request.agentId());
					throw exception;
				}
			}
			case BUILD_SEQUENCE -> ActiveAction.controller(
					request,
					player,
					new ServerBuildSequenceController(
							buildPlacements(arguments),
							System.currentTimeMillis(),
							integer(arguments, "timeoutMs"),
							new BuildSequencePlacementDriver(request)
					)
			);
			case CHAT -> ActiveAction.immediate(request, player, () ->
					player.level().getServer().getPlayerList().broadcastSystemMessage(
							Component.literal("<" + player.getScoreboardName() + "> " + string(arguments, "message")),
							false
					));
			case WAIT -> ActiveAction.waitFor(request, player, integer(arguments, "durationMs"));
			case SET_DOOR -> ActiveAction.immediate(request, player, () -> requireResult(
					advancedInteractions.setDoor(
							request.agentId(),
							player,
							blockPosition(arguments),
							bool(arguments, "open")
					)
			));
			case PICK_UP_ITEM -> {
				ItemEntity item = findItem(player, string(arguments, "targetSelector"));
				requireResult(advancedInteractions.validatePickUp(player, item));
				yield ActiveAction.controller(request, player,
						new ServerItemPickupController(player, item, System.currentTimeMillis()));
			}
			case DROP_ITEM -> ActiveAction.immediate(request, player, () -> requireResult(
					advancedInteractions.drop(player, integer(arguments, "slot"), integer(arguments, "count"))
			));
			case FIGHT_TARGET -> ActiveAction.controller(
					request,
					player,
					new ServerCombatController(
							findTarget(player, string(arguments, "targetSelector")),
							new CombatIntent(
									string(arguments, "targetSelector"),
									number(arguments, "desiredRange"),
									integer(arguments, "timeoutMs"),
									CombatIntent.Mode.FIGHT
							),
							System.currentTimeMillis()
					)
			);
			case FLEE_FROM -> ActiveAction.controller(
					request,
					player,
					new ServerCombatController(
							findTarget(player, string(arguments, "targetSelector")),
							new CombatIntent(
									string(arguments, "targetSelector"),
									number(arguments, "distance"),
									integer(arguments, "timeoutMs"),
									CombatIntent.Mode.FLEE
							),
							System.currentTimeMillis()
					)
			);
			case FOLLOW_ENTITY -> ActiveAction.controller(
					request,
					player,
					new ServerCombatController(
							findTarget(player, string(arguments, "targetSelector")),
							new CombatIntent(
									string(arguments, "targetSelector"),
									number(arguments, "distance"),
									integer(arguments, "timeoutMs"),
									CombatIntent.Mode.FOLLOW
							),
							System.currentTimeMillis()
					)
			);
			case TRANSFER_CONTAINER, CRAFT_INVENTORY, CRAFT_TABLE, FURNACE_TRANSACTION,
					EQUIP_ITEM, SELECT_TOOL, BLOCK_WITH_SHIELD, USE_RANGED -> ActiveAction.transaction(
					request,
					player,
					advancedInteractions.begin(player, request, arguments)
			);
			case COMPLETE_GOAL -> throw new IllegalStateException("complete_goal is handled before action creation");
		};
	}

	private void finish(ActiveAction action, ServerActionResult result) {
		AgentId agentId = action.request().agentId();
		CleanupRetry<ServerActionResult> retry = pendingCompletions.computeIfAbsent(agentId, ignored -> new CleanupRetry<>());
		retry.retain(result);
		ServerActionResult completed;
		try {
			completed = retry.complete(action::cleanup);
		} catch (RuntimeException teardownFailure) {
			return;
		}
		complete(action, completed);
	}

	static final class CleanupRetry<T> {
		private T pending;

		synchronized void retain(T candidate) {
			Objects.requireNonNull(candidate, "candidate must not be null");
			if (pending == null) pending = candidate;
		}

		synchronized boolean hasPending() {
			return pending != null;
		}

		synchronized T pending() {
			if (pending == null) throw new IllegalStateException("no cleanup result is pending");
			return pending;
		}

		synchronized T complete(Runnable cleanup) {
			Objects.requireNonNull(cleanup, "cleanup must not be null");
			T retained = pending();
			cleanup.run();
			pending = null;
			return retained;
		}
	}

	private void releaseResourceLease(ActiveAction action) {
		if (action.resourceLeaseKey != null) {
			resourceLeases.release(action.resourceLeaseKey, action.request().agentId());
		}
	}

	private void emit(
			ServerActionRequest request,
			ServerActionState state,
			String reasonCode,
			String message,
			long elapsedMs
	) {
		publish(new ServerActionResult(
				request.agentId(),
				request.goalRevision(),
				request.actionId(),
				request.type(),
				state,
				reasonCode,
				message == null ? "" : message,
				elapsedMs,
				System.currentTimeMillis()
		));
	}

	private void publish(ServerActionResult result) {
		lastResults.put(result.agentId(), result);
		try {
			AgentChatReporter.result(manager, manager.registry().require(result.agentId()), result);
		} catch (AgentDomainException ignored) {
			// The agent may have been removed while its terminal result was in flight.
		}
		resultSink.accept(result);
	}

	private static void attack(ServerPlayer player, String selector) {
		Entity target = findTarget(player, selector);
		if (target instanceof ServerPlayer targetPlayer
				&& (targetPlayer.isCreative() || targetPlayer.isSpectator())) {
			throw new AgentDomainException("TARGET_INVULNERABLE", "Creative and spectator players cannot be valid combat targets");
		}
		if (player.distanceToSqr(target) > MAX_INTERACTION_DISTANCE_SQUARED) {
			throw new AgentDomainException("TARGET_TOO_FAR", "Attack target is out of reach");
		}
		player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
		player.attack(target);
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
	}

	private static Entity findTarget(ServerPlayer player, String selector) {
		ServerLevel level = player.level();
		String normalized = EntityTargetSelector.normalize(selector);
		java.util.function.Predicate<Entity> predicate;
		if ("nearest_hostile".equals(normalized)) {
			predicate = entity -> entity instanceof Enemy && entity.isAlive();
		} else if ("nearest_player".equals(normalized)) {
			predicate = entity -> entity instanceof ServerPlayer && entity != player && entity.isAlive();
		} else if ("nearest_living".equals(normalized)) {
			predicate = entity -> entity instanceof LivingEntity && entity != player && entity.isAlive();
		} else {
			ServerPlayer named = level.getServer().getPlayerList().getPlayerByName(normalized);
			if (named != null && named != player && named.isAlive()) return named;
			for (var record : managerRecords(level)) {
				if (!AgentIdentity.displayName(record.profile()).equalsIgnoreCase(normalized)) continue;
				ServerPlayer agentPlayer = OfflineAgentPlayers.find(level.getServer(), record.agentId(), record.profile())
						.orElse(null);
				if (agentPlayer != null && agentPlayer != player && agentPlayer.isAlive()) return agentPlayer;
			}
			try {
				UUID uuid = UUID.fromString(normalized);
				Entity direct = level.getEntity(uuid);
				if (direct != null && direct.isAlive()) return direct;
			} catch (IllegalArgumentException ignored) {
			}
			throw new AgentDomainException("TARGET_NOT_FOUND", "Unknown or unavailable target selector: " + selector);
		}
		return level.getEntities(player, player.getBoundingBox().inflate(128.0D), predicate)
				.stream()
				.filter(entity -> ObservationVisibility.canSeeEntity(player, entity))
				.min(Comparator.comparingDouble(player::distanceToSqr))
				.orElseThrow(() -> new AgentDomainException("TARGET_NOT_FOUND", "No matching target is nearby"));
	}

	private static List<dev.agaminggod.arenaagents.agent.AgentRecord> managerRecords(ServerLevel level) {
		return CodexAgentManager.get(level.getServer()).records();
	}

	private static void selectItem(ServerPlayer player, String itemId) {
		Identifier identifier = Identifier.tryParse(itemId);
		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
			throw new AgentDomainException("UNKNOWN_ITEM", "Unknown item: " + itemId);
		}
		int found = -1;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(identifier)) {
				found = slot;
				break;
			}
		}
		if (found < 0 && player.isCreative()) {
			player.getInventory().setItem(0, new ItemStack(BuiltInRegistries.ITEM.getValue(identifier)));
			found = 0;
		}
		if (found < 0) throw new AgentDomainException("ITEM_NOT_FOUND", "Agent does not have " + itemId);
		if (found > 8) {
			ItemStack hotbar = player.getInventory().getItem(0);
			player.getInventory().setItem(0, player.getInventory().getItem(found));
			player.getInventory().setItem(found, hotbar);
			found = 0;
		}
		OfflineAgentPlayers.actions(player).setSlot(found + 1);
	}

	private static void validatePlaceBlock(
			ServerProtectionPolicy protection,
			ServerPlayer player,
			BlockPos position,
			Direction requestedFace,
			String itemId
	) {
		requirePlacementProtection(protection, player, player.level(), position);
		String currentBlockId = blockId(player.level().getBlockState(position));
		if (!player.level().getBlockState(position).canBeReplaced()) {
			throw new AgentDomainException("TARGET_OCCUPIED", "Placement target contains " + currentBlockId);
		}
		selectItem(player, itemId);
		if (!(player.getMainHandItem().getItem() instanceof BlockItem)) {
			throw new AgentDomainException("ITEM_NOT_PLACEABLE", itemId + " is not a block item");
		}
		if (placementFace(player, position, requestedFace) == null) {
			throw new AgentDomainException("NO_PLACEMENT_SUPPORT", "No adjacent solid face can support this placement");
		}
		BlockHitResult hit = placementHit(player, position, requestedFace);
		if (hit == null) {
			throw new AgentDomainException("NO_PLACEMENT_SUPPORT", "No adjacent solid face can support this placement");
		}
		requirePlacementGates(protection, player, position, hit);
	}

	static void requirePlacementProtection(
			ServerProtectionPolicy policy,
			ServerPlayer player,
			ServerLevel level,
			BlockPos position
	) {
		if (policy == null || !policy.mayModifyBlock(player, level, position)) {
			throw new AgentDomainException("PROTECTION_DENIED", "Block placement denied");
		}
	}

	private static void placeBlock(
			ServerProtectionPolicy protection,
			ServerPlayer player,
			BlockPos position,
			Direction requestedFace,
			String itemId,
			DesiredBlockState desiredBlockState
	) {
		validatePlaceBlock(protection, player, position, requestedFace, itemId);
		BlockHitResult hit = placementHit(player, position, requestedFace);
		if (hit == null) {
			throw new AgentDomainException("NO_PLACEMENT_SUPPORT", "No adjacent solid face can support this placement");
		}
		ItemStack stack = player.getMainHandItem();
		orientPlayerForDesiredState(player, (BlockItem) stack.getItem(), stack, hit, desiredBlockState);
		InteractionResult result = player.gameMode.useItemOn(
				player,
				player.level(),
				stack,
				InteractionHand.MAIN_HAND,
				hit
		);
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
	}

	private static BlockHitResult placementHit(ServerPlayer player, BlockPos position, Direction requestedFace) {
		Direction face = placementFace(player, position, requestedFace);
		if (face == null) return null;
		BlockPos support = position.relative(face.getOpposite());
		return new BlockHitResult(
				placementLookTarget(support, face),
				face,
				support,
				false
		);
	}

	private static void requirePlacementGates(
			ServerProtectionPolicy protection,
			ServerPlayer player,
			BlockPos target,
			BlockHitResult hit
	) {
		ServerLevel level = player.level();
		BlockPos support = hit.getBlockPos();
		requirePlacementProtection(protection, player, level, target);
		requirePlacementProtection(protection, player, level, support);
		if (level.getServer().isUnderSpawnProtection(level, target, player)
				|| level.getServer().isUnderSpawnProtection(level, support, player)
				|| !level.mayInteract(player, target)
				|| !level.mayInteract(player, support)) {
			throw new AgentDomainException("PROTECTION_DENIED", "Vanilla placement protection denied the target or support block");
		}
		if (!player.isWithinBlockInteractionRange(support, 1.0D)
				|| !isValidPlacementHit(support, hit.getLocation())) {
			throw new AgentDomainException("TARGET_TOO_FAR", "Placement hit is out of reach");
		}
	}

	static boolean isValidPlacementHit(BlockPos support, Vec3 hitLocation) {
		Vec3 offset = hitLocation.subtract(Vec3.atCenterOf(support));
		return Math.abs(offset.x()) < 1.0000001D
				&& Math.abs(offset.y()) < 1.0000001D
				&& Math.abs(offset.z()) < 1.0000001D;
	}

	static float directionalPlacementYaw(Direction direction) {
		if (direction == null || !direction.getAxis().isHorizontal()) {
			throw new IllegalArgumentException("direction must be horizontal");
		}
		return direction.toYRot();
	}

	private static void orientPlayerForDesiredState(
			ServerPlayer player,
			BlockItem blockItem,
			ItemStack stack,
			BlockHitResult hit,
			DesiredBlockState desiredBlockState
	) {
		String requestedFacing = desiredBlockState.properties().get("facing");
		Direction requestedDirection = requestedFacing == null ? null : Direction.byName(requestedFacing);
		float originalYaw = player.getYRot();
		float originalHeadYaw = player.getYHeadRot();
		if (requestedDirection == null || !requestedDirection.getAxis().isHorizontal()) {
			BlockState predicted = predictedPlacementState(player, blockItem, stack, hit);
			if (predicted == null || !desiredBlockState.matches(predicted)) {
				throw new AgentDomainException(
						"PLACEMENT_STATE_MISMATCH",
						"Requested block state cannot be produced by vanilla placement context"
				);
			}
			return;
		}
		for (Direction direction : HORIZONTAL_PLACEMENT_DIRECTIONS) {
			float yaw = directionalPlacementYaw(direction);
			player.setYRot(yaw);
			player.setYHeadRot(yaw);
			BlockState predicted = predictedPlacementState(player, blockItem, stack, hit);
			if (predicted != null && desiredBlockState.matches(predicted)) return;
		}
		player.setYRot(originalYaw);
		player.setYHeadRot(originalHeadYaw);
		throw new AgentDomainException(
				"PLACEMENT_STATE_MISMATCH",
				"Requested directional state cannot be produced by vanilla placement context"
		);
	}

	private static BlockState predictedPlacementState(
			ServerPlayer player,
			BlockItem blockItem,
			ItemStack stack,
			BlockHitResult hit
	) {
		BlockPlaceContext context = blockItem.updatePlacementContext(
				new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit)
		);
		return context == null ? null : blockItem.getBlock().getStateForPlacement(context);
	}

	private static Direction placementFace(ServerPlayer player, BlockPos position, Direction requestedFace) {
		return BlockPlacementAttemptPolicy.chooseFace(requestedFace, candidate -> {
			BlockPos support = position.relative(candidate.getOpposite());
			return player.level().getBlockState(support).isFaceSturdy(player.level(), support, candidate);
		});
	}

	private static int inventoryItemCount(ServerPlayer player, String itemId) {
		Identifier identifier = Identifier.tryParse(itemId);
		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) return 0;
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(identifier)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	static Vec3 placementLookTarget(BlockPos support, Direction face) {
		Vec3 center = Vec3.atCenterOf(support);
		return center.add(
				face.getStepX() * 0.499D,
				face.getStepY() * 0.499D,
				face.getStepZ() * 0.499D
		);
	}

	private static String expectedBlockId(String itemId) {
		Identifier identifier = Identifier.tryParse(itemId);
		if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
			throw new AgentDomainException("UNKNOWN_ITEM", "Unknown item: " + itemId);
		}
		if (!(BuiltInRegistries.ITEM.getValue(identifier) instanceof BlockItem blockItem)) {
			throw new AgentDomainException("ITEM_NOT_PLACEABLE", itemId + " is not a block item");
		}
		return BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
	}

	private static String blockId(net.minecraft.world.level.block.state.BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static String blockResourceKey(ServerPlayer player, BlockPos position) {
		return "block:" + player.level().dimension().identifier() + ":" + position.asLong();
	}

	private static ItemEntity findItem(ServerPlayer player, String selector) {
		if (!"nearest_item".equals(EntityTargetSelector.normalize(selector))) {
			try {
				Entity entity = player.level().getEntity(UUID.fromString(selector));
				if (entity instanceof ItemEntity item && item.isAlive()) return item;
			} catch (IllegalArgumentException exception) {
				throw new AgentDomainException("ITEM_NOT_FOUND", "Invalid item target selector: " + selector);
			}
			throw new AgentDomainException("ITEM_NOT_FOUND", "The selected item entity is unavailable");
		}
		return player.level().getEntitiesOfClass(
						ItemEntity.class,
						player.getBoundingBox().inflate(64.0D),
						Entity::isAlive
				).stream()
				.filter(item -> ObservationVisibility.canSeeEntity(player, item))
				.min(Comparator.comparingDouble(player::distanceToSqr))
				.orElseThrow(() -> new AgentDomainException("ITEM_NOT_FOUND", "No item entity is nearby"));
	}

	private static void requireResult(AdvancedInteractionService.Result result) {
		if (!result.succeeded()) throw new AgentDomainException(result.reasonCode(), result.message());
	}

	private static BlockPos blockPosition(JsonObject arguments) {
		return BlockPos.containing(number(arguments, "x"), number(arguments, "y"), number(arguments, "z"));
	}

	private static List<ServerBuildSequenceController.Placement> buildPlacements(JsonObject arguments) {
		List<ServerBuildSequenceController.Placement> placements = new ArrayList<>();
		arguments.getAsJsonArray("placements").forEach(element -> {
			JsonObject placement = element.getAsJsonObject();
			Direction face = Direction.byName(string(placement, "face"));
			if (face == null) throw new AgentDomainException("INVALID_FIELD", "Unknown placement face");
			placements.add(new ServerBuildSequenceController.Placement(
					integer(placement, "x"),
					integer(placement, "y"),
					integer(placement, "z"),
					face,
					string(placement, "itemId"),
					placement.has("desiredState") && !placement.get("desiredState").isJsonNull()
							? string(placement, "desiredState") : null
			));
		});
		return List.copyOf(placements);
	}

	private static String string(JsonObject object, String field) {
		return object.get(field).getAsString();
	}

	private static int integer(JsonObject object, String field) {
		return object.get(field).getAsInt();
	}

	private static double number(JsonObject object, String field) {
		return object.get(field).getAsDouble();
	}

	private static boolean bool(JsonObject object, String field) {
		return object.get(field).getAsBoolean();
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}

	static String failureReason(Throwable throwable) {
		return throwable instanceof AgentDomainException domain ? domain.code() : "ACTION_EXCEPTION";
	}

	private final class BuildSequencePlacementDriver implements ServerBuildSequenceController.PlacementDriver {
		private final ServerActionRequest parent;
		private ActiveAction child;

		private BuildSequencePlacementDriver(ServerActionRequest parent) {
			this.parent = parent;
		}

		@Override
		public boolean isInRange(ServerPlayer player, ServerBuildSequenceController.Placement placement) {
			if (player == null) return false;
			BlockPos target = new BlockPos(placement.x(), placement.y(), placement.z());
			BlockPos support = target.relative(placement.face().getOpposite());
			return player.isWithinBlockInteractionRange(support, 1.0D);
		}

		@Override
		public ServerController.TickResult tick(
				ServerPlayer player,
				ServerBuildSequenceController.Placement placement,
				int index,
				long nowEpochMs
		) {
			try {
				if (child == null) {
					JsonObject arguments = new JsonObject();
					arguments.addProperty("x", placement.x());
					arguments.addProperty("y", placement.y());
					arguments.addProperty("z", placement.z());
					arguments.addProperty("face", placement.face().getName());
					arguments.addProperty("itemId", placement.itemId());
					if (placement.desiredState() == null) arguments.add("desiredState", com.google.gson.JsonNull.INSTANCE);
					else arguments.addProperty("desiredState", placement.desiredState());
					child = createAction(new ServerActionRequest(
							parent.agentId(), parent.goalRevision(), parent.actionId(), ActionType.PLACE_BLOCK, arguments, parent.provenance()
					), player);
				}
				ServerActionResult result = child.tick(nowEpochMs);
				if (result == null) return ServerController.TickResult.running(child.lastProgress);
				ActiveAction completed = child;
				double completedProgress = child.lastProgress;
				child = null;
				cleanupChild(completed);
				if (result.state() == ServerActionState.SUCCEEDED) {
					return ServerController.TickResult.succeeded(result.reasonCode(), result.message());
				}
				return ServerController.TickResult.failed(result.reasonCode(), result.message(), completedProgress);
			} catch (RuntimeException failure) {
				if (child != null) {
					ActiveAction failed = child;
					child = null;
					try { cleanupChild(failed); } catch (RuntimeException ignored) { }
				}
				return ServerController.TickResult.failed(failureReason(failure), safeMessage(failure), 0.0D);
			}
		}

		@Override
		public void cancel(ServerPlayer player) {
			if (child == null) return;
			ActiveAction cancelled = child;
			child = null;
			try {
				cancelled.cancel("Build sequence cancelled");
			} finally {
				releaseResourceLease(cancelled);
			}
		}

		private void cleanupChild(ActiveAction action) {
			try {
				action.cleanup();
			} finally {
				releaseResourceLease(action);
			}
		}
	}

	private static final class ActiveAction {
		private enum Mode { IMMEDIATE, MOVE, USE, BREAK, PLACE, WAIT, CONTROLLER, TRANSACTION }

		private final ServerActionRequest request;
		private final ServerPlayer player;
		private final Mode mode;
		private final long startedAt;
		private final long timeoutMs;
		private final Runnable immediate;
		private final Vec3 destination;
		private final double tolerance;
		private final boolean sprint;
		private final BlockPos block;
		private final ActionProgressTracker progress;
		private final ActionProgressEmissionPolicy progressEmission =
				new ActionProgressEmissionPolicy(PROGRESS_EMISSION_DELTA, PROGRESS_HEARTBEAT_MS);
		private ServerController controller;
		private ServerTransactionAdapter.ActiveTransaction transaction;
		private String initialBlockId;
		private String expectedBlockId;
		private DesiredBlockState desiredBlockState;
		private Direction placementRequestedFace;
		private String placementItemId;
		private int initialPlacementItemCount;
		private String resourceLeaseKey;
		private int placementAttempts;
		private boolean started;
		private double lastProgress;

		private ActiveAction(
				ServerActionRequest request,
				ServerPlayer player,
				Mode mode,
				long timeoutMs,
				Runnable immediate,
				Vec3 destination,
				double tolerance,
				boolean sprint,
				BlockPos block
		) {
			this.request = request;
			this.player = player;
			this.mode = mode;
			this.startedAt = System.currentTimeMillis();
			this.timeoutMs = Math.max(1L, timeoutMs);
			this.immediate = immediate;
			this.destination = destination;
			this.tolerance = tolerance;
			this.sprint = sprint;
			this.block = block;
			this.progress = mode == Mode.MOVE
					? new ActionProgressTracker(player.position().distanceTo(destination), startedAt, MOVEMENT_STALL_TIMEOUT_MS)
					: null;
		}

		static ActiveAction immediate(ServerActionRequest request, ServerPlayer player, Runnable operation) {
			return new ActiveAction(request, player, Mode.IMMEDIATE, DEFAULT_TIMEOUT_MS, operation, null, 0.0D, false, null);
		}

		static ActiveAction move(
				ServerActionRequest request,
				ServerPlayer player,
				Vec3 destination,
				double tolerance,
				boolean sprint,
				long timeoutMs
		) {
			return new ActiveAction(request, player, Mode.MOVE, timeoutMs, null, destination, tolerance, sprint, null);
		}

		static ActiveAction use(ServerActionRequest request, ServerPlayer player, long durationMs) {
			return new ActiveAction(request, player, Mode.USE, durationMs, null, null, 0.0D, false, null);
		}

		static ActiveAction breakBlock(
				ServerActionRequest request,
				ServerPlayer player,
				BlockPos block,
				long timeoutMs
		) {
			return new ActiveAction(request, player, Mode.BREAK, timeoutMs, null, null, 0.0D, false, block);
		}

		static ActiveAction placeBlock(
				ServerActionRequest request,
				ServerPlayer player,
				Runnable operation,
				BlockPos block,
				String initialBlockId,
				String expectedBlockId,
				DesiredBlockState desiredBlockState,
				Direction placementRequestedFace,
				String itemId,
				int initialItemCount
		) {
			ActiveAction action = new ActiveAction(
					request,
					player,
					Mode.PLACE,
					PLACE_TIMEOUT_MS,
					operation,
					null,
					0.0D,
					false,
					block
			);
			action.initialBlockId = Objects.requireNonNull(initialBlockId, "initialBlockId must not be null");
			action.expectedBlockId = Objects.requireNonNull(expectedBlockId, "expectedBlockId must not be null");
			action.desiredBlockState = Objects.requireNonNull(desiredBlockState, "desiredBlockState must not be null");
			action.placementRequestedFace = placementRequestedFace;
			action.placementItemId = Objects.requireNonNull(itemId, "itemId must not be null");
			action.initialPlacementItemCount = Math.max(0, initialItemCount);
			return action;
		}

		static ActiveAction waitFor(ServerActionRequest request, ServerPlayer player, long durationMs) {
			return new ActiveAction(request, player, Mode.WAIT, durationMs, null, null, 0.0D, false, null);
		}

		static ActiveAction controller(
				ServerActionRequest request,
				ServerPlayer player,
				ServerController controller
		) {
			ActiveAction action = new ActiveAction(
					request,
					player,
					Mode.CONTROLLER,
					DEFAULT_TIMEOUT_MS,
					null,
					null,
					0.0D,
					false,
					null
			);
			action.controller = Objects.requireNonNull(controller, "controller must not be null");
			return action;
		}

		static ActiveAction transaction(
				ServerActionRequest request,
				ServerPlayer player,
				ServerTransactionAdapter.ActiveTransaction transaction
		) {
			ActiveAction action = new ActiveAction(
					request,
					player,
					Mode.TRANSACTION,
					DEFAULT_TIMEOUT_MS,
					null,
					null,
					0.0D,
					false,
					null
			);
			action.transaction = Objects.requireNonNull(transaction, "transaction must not be null");
			return action;
		}

		ServerActionRequest request() {
			return request;
		}

		ServerActionResult tick(long now) {
			if (!player.isAlive()) return result(ServerActionState.FAILED, "AGENT_DEAD", "Agent player died", now);
			long elapsed = Math.max(0L, now - startedAt);
			if (!started) {
				started = true;
				switch (mode) {
					case IMMEDIATE -> immediate.run();
					case MOVE -> {
						EntityPlayerActionPack actions = OfflineAgentPlayers.actions(player);
						actions.lookAt(destination).setSprinting(sprint).setForward(1.0F);
						actions.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.interval(10));
					}
					case USE -> OfflineAgentPlayers.actions(player)
							.start(EntityPlayerActionPack.ActionType.USE, EntityPlayerActionPack.Action.continuous());
					case BREAK -> {
						if (player.distanceToSqr(Vec3.atCenterOf(block)) > MAX_INTERACTION_DISTANCE_SQUARED) {
							throw new AgentDomainException("TARGET_TOO_FAR", "Block is out of reach");
						}
						OfflineAgentPlayers.actions(player)
								.lookAt(Vec3.atCenterOf(block))
								.start(EntityPlayerActionPack.ActionType.ATTACK, EntityPlayerActionPack.Action.continuous());
					}
					case PLACE -> {
					}
					case WAIT -> {
					}
					case CONTROLLER -> {
					}
					case TRANSACTION -> {
					}
				}
				if (mode == Mode.IMMEDIATE) return result(ServerActionState.SUCCEEDED, "ACTION_COMPLETED", "Action completed", now);
			}

			if (mode == Mode.MOVE) {
				double distance = player.position().distanceTo(destination);
				lastProgress = progress.progress(distance);
				OfflineAgentPlayers.actions(player).lookAt(destination).setSprinting(sprint).setForward(1.0F);
				if (distance <= tolerance) {
					return result(ServerActionState.SUCCEEDED, "DESTINATION_REACHED", "Destination reached", now);
				}
				if (progress.stalled(distance, now)) {
					return result(
							ServerActionState.FAILED,
							"PATH_BLOCKED",
							"Agent made no progress for 4 seconds; replanning around the obstacle",
							now
					);
				}
			} else if (mode == Mode.BREAK && player.level().getBlockState(block).isAir()) {
				return result(ServerActionState.SUCCEEDED, "BLOCK_BROKEN", "Block broken", now);
			} else if (mode == Mode.PLACE) {
				boolean placementOwned = player.isCreative()
						? placementAttempts > 0
						: inventoryItemCount(player, placementItemId) == initialPlacementItemCount - 1;
				BlockPlacementPostcondition.Decision decision = BlockPlacementPostcondition.evaluate(
						initialBlockId,
						player.level().getBlockState(block),
						desiredBlockState,
										placementOwned,
										elapsed >= timeoutMs,
										placementAttempts
								);
				if (decision == BlockPlacementPostcondition.Decision.ALREADY_SATISFIED) {
					return result(ServerActionState.SUCCEEDED, "TARGET_ALREADY_SATISFIED", "Requested block was already present", now);
				}
				if (decision == BlockPlacementPostcondition.Decision.SUCCEEDED) {
					return result(ServerActionState.SUCCEEDED, "BLOCK_PLACED", "Block placement confirmed", now);
				}
				if (decision == BlockPlacementPostcondition.Decision.CONFLICT) {
					return result(ServerActionState.FAILED, "PLACEMENT_CONFLICT", placementFailureMessage(
							"A different block or state occupied the target"), now);
				}
				if (decision == BlockPlacementPostcondition.Decision.TIMED_OUT
						|| decision == BlockPlacementPostcondition.Decision.EXHAUSTED) {
					return result(ServerActionState.TIMED_OUT, "PLACEMENT_NOT_CONFIRMED", placementFailureMessage(
							"Block placement was not confirmed"), now);
				}
				if (BlockPlacementAttemptPolicy.shouldAttempt(elapsed, placementAttempts)) {
					immediate.run();
					placementAttempts += 1;
				}
			} else if ((mode == Mode.USE || mode == Mode.WAIT) && elapsed >= timeoutMs) {
				return result(ServerActionState.SUCCEEDED, "ACTION_COMPLETED", "Action completed", now);
			}
			if (mode == Mode.CONTROLLER) {
				ServerController.TickResult controllerResult = requireControllerResult(
						controller.tick(player, now), lastProgress, controller.getClass().getSimpleName());
				lastProgress = controllerResult.progress();
				return switch (controllerResult.state()) {
					case RUNNING -> null;
					case SUCCEEDED -> result(
							ServerActionState.SUCCEEDED,
							controllerResult.reasonCode(),
							controllerResult.message(),
							now
					);
					case FAILED -> result(
							ServerActionState.FAILED,
							controllerResult.reasonCode(),
							controllerResult.message(),
							now
					);
				};
			}
			if (mode == Mode.TRANSACTION) {
				ServerTransactionAdapter.TickResult transactionResult = transaction.tick(now);
				lastProgress = timedProgress(elapsed);
				return switch (transactionResult.state()) {
					case RUNNING -> null;
					case SUCCEEDED -> result(ServerActionState.SUCCEEDED, transactionResult.reasonCode(), transactionResult.message(), now);
					case FAILED -> result(ServerActionState.FAILED, transactionResult.reasonCode(), transactionResult.message(), now);
					case CANCELLED -> result(ServerActionState.CANCELLED, transactionResult.reasonCode(), transactionResult.message(), now);
					case TIMED_OUT -> result(ServerActionState.TIMED_OUT, transactionResult.reasonCode(), transactionResult.message(), now);
				};
			}

			if (elapsed >= timeoutMs) {
				return result(ServerActionState.TIMED_OUT, "ACTION_TIMED_OUT", "Action timed out", now);
			}
			if (mode != Mode.MOVE && mode != Mode.CONTROLLER && mode != Mode.TRANSACTION) {
				lastProgress = timedProgress(elapsed);
			}
			return null;
		}

		ServerActionProgress progress(long now) {
			double bounded = Math.max(0.0D, Math.min(0.99D, lastProgress));
			if (!progressEmission.shouldEmit(bounded, now)) return null;
			return new ServerActionProgress(
					request.agentId(),
					request.goalRevision(),
					request.actionId(),
					request.type(),
					bounded,
					Math.max(0L, now - startedAt),
					now
			);
		}

		private double timedProgress(long elapsed) {
			return Math.max(0.0D, Math.min(0.99D, (double) elapsed / timeoutMs));
		}

		private String placementFailureMessage(String prefix) {
			var currentState = player.level().getBlockState(block);
			String actual = blockId(currentState) + DesiredBlockState.stableProperties(currentState);
			Direction face = placementFace(player, block, placementRequestedFace);
			BlockPos support = face == null ? null : block.relative(face.getOpposite());
			double distance = Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(block)));
			return prefix
					+ "; expected=" + desiredBlockState.blockId() + desiredBlockState.properties()
					+ ", actual=" + actual
					+ ", support=" + (support == null ? "none" : support + " face=" + face)
					+ ", distance=" + String.format(java.util.Locale.ROOT, "%.3f", distance)
					+ ", itemCount=" + inventoryItemCount(player, placementItemId);
		}

		void cancel(String reason) {
			ServerTransactionAdapter.runBestEffort(
					() -> { if (transaction != null) transaction.cancel(reason); },
					() -> { if (controller != null) controller.cancel(player); },
					() -> OfflineAgentPlayers.stop(player)
			);
		}

		void cleanup() {
			ServerTransactionAdapter.runBestEffort(
					() -> { if (transaction != null) transaction.cleanup(); },
					() -> { if (controller != null) controller.cancel(player); },
					() -> OfflineAgentPlayers.stop(player)
			);
		}

		ServerActionResult result(ServerActionState state, String reasonCode, String message, long now) {
			return new ServerActionResult(
					request.agentId(),
					request.goalRevision(),
					request.actionId(),
					request.type(),
					state,
					reasonCode,
					message,
					Math.max(0L, now - startedAt),
					now
			);
		}
	}

	static ServerController.TickResult requireControllerResult(
			ServerController.TickResult result,
			double lastProgress,
			String controllerName
	) {
		if (result != null) return result;
		String readable = controllerName == null || controllerName.isBlank()
				? "Controller" : controllerName.replace("Server", "").replace("Controller", " controller").trim();
		if (!readable.toLowerCase(java.util.Locale.ROOT).endsWith("controller")) readable += " controller";
		return ServerController.TickResult.failed(
				"CONTROLLER_NO_RESULT",
				readable + " returned no result",
				Math.max(0.0D, Math.min(1.0D, lastProgress))
		);
	}
}
