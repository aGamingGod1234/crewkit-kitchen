package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.combat.CombatTarget;
import dev.agaminggod.arenaagents.client.combat.WeaponCandidate;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.MinecraftWalkabilityView;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftActionContext implements ActionContext {
	private static final int MISSING_HOTBAR_SLOT = -1;
	private static final double TARGET_DISTANCE_EPSILON_SQUARED = 1.0E-8D;
	private static final String CHAT_SENT_REASON = "CHAT_SENT";
	private static final String CHAT_SENT_MESSAGE = "Chat message sent";
	private static final String CHAT_UNAVAILABLE_REASON = "CHAT_UNAVAILABLE";
	private static final String CHAT_UNAVAILABLE_MESSAGE = "Minecraft chat connection is unavailable";
	private static final String CHAT_FAILED_REASON = "CHAT_FAILED";
	private static final String ITEM_SELECTED_REASON = "ITEM_SELECTED";
	private static final String ITEM_NOT_IN_HOTBAR_REASON = "ITEM_NOT_IN_HOTBAR";
	private static final String ITEM_NOT_IN_HOTBAR_MESSAGE = "Requested item is not present in the hotbar";
	private static final String ITEM_SELECTION_FAILED_REASON = "ITEM_SELECTION_FAILED";
	private static final String ITEM_USE_STARTED_REASON = "ITEM_USE_STARTED";
	private static final String ITEM_USE_STARTED_MESSAGE = "Item use started";
	private static final String ITEM_USE_UNAVAILABLE_REASON = "ITEM_USE_UNAVAILABLE";
	private static final String ITEM_USE_UNAVAILABLE_MESSAGE = "Minecraft item interaction is unavailable";
	private static final String ITEM_USE_REJECTED_REASON = "ITEM_USE_REJECTED";
	private static final String ITEM_USE_REJECTED_MESSAGE = "Selected item did not accept the use action";
	private static final String ITEM_USE_FAILED_REASON = "ITEM_USE_FAILED";
	private static final String ATTACK_SENT_REASON = "ATTACK_SENT";
	private static final String ATTACK_UNAVAILABLE_REASON = "ATTACK_UNAVAILABLE";
	private static final String TARGET_GONE_REASON = "TARGET_GONE";
	private static final String TARGET_OUT_OF_REACH_REASON = "TARGET_OUT_OF_REACH";
	private static final String BLOCK_BREAK_REJECTED_REASON = "BLOCK_BREAK_REJECTED";
	private static final String BLOCK_PLACE_REJECTED_REASON = "BLOCK_PLACE_REJECTED";
	private static final String BLOCK_ITEM_REQUIRED_REASON = "BLOCK_ITEM_REQUIRED";

	private final Minecraft minecraft;
	private final WalkabilityView walkabilityView;

	public MinecraftActionContext(Minecraft minecraft) {
		this.minecraft = Objects.requireNonNull(minecraft, "minecraft must not be null");
		walkabilityView = new MinecraftWalkabilityView(minecraft);
	}

	@Override
	public boolean isClientThread() {
		return minecraft.isSameThread();
	}

	@Override
	public long monotonicTimeMs() {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
	}

	@Override
	public long epochTimeMs() {
		return System.currentTimeMillis();
	}

	@Override
	public SafetyState safetyState() {
		ClientPacketListener connection = minecraft.getConnection();
		LocalPlayer player = minecraft.player;
		return classifySafety(
				connection != null,
				connection != null && connection.isAcceptingMessages(),
				minecraft.level != null,
				player != null,
				player != null && player.isAlive(),
				minecraft.screen != null
		);
	}

	@Override
	public NavigationSnapshot navigationSnapshot() {
		requireClientThread();
		LocalPlayer player = requirePlayer();
		BlockPos feetPosition = player.blockPosition();
		return new NavigationSnapshot(
				player.getX(),
				player.getY(),
				player.getZ(),
				player.getYRot(),
				player.getXRot(),
				new GridPosition(feetPosition.getX(), feetPosition.getY(), feetPosition.getZ())
		);
	}

	@Override
	public WalkabilityView walkabilityView() {
		requireClientThread();
		return walkabilityView;
	}

	@Override
	public void setMovement(MovementInput movement) {
		requireClientThread();
		Objects.requireNonNull(movement, "movement must not be null");
		minecraft.options.keyUp.setDown(movement.forward());
		minecraft.options.keyDown.setDown(movement.backward());
		minecraft.options.keyLeft.setDown(movement.left());
		minecraft.options.keyRight.setDown(movement.right());
		minecraft.options.keyJump.setDown(movement.jump());
		minecraft.options.keySprint.setDown(movement.sprint());
	}

	@Override
	public CombatSnapshot combatSnapshot() {
		requireClientThread();
		LocalPlayer player = requirePlayer();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return ActionContext.super.combatSnapshot();
		}
		List<CombatTarget> targets = new ArrayList<>();
		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || entity == player || entity.isRemoved()) {
				continue;
			}
			targets.add(new CombatTarget(
					entity.getUUID(),
					entity.getName().getString(),
					BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
					entity instanceof Player,
					entity instanceof Enemy,
					living.isAlive(),
					entity.getX(),
					entity.getY(),
					entity.getZ(),
					entity.getEyeY(),
					player.distanceToSqr(entity)
			));
		}
		List<WeaponCandidate> hotbarItems = new ArrayList<>();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) {
				hotbarItems.add(new WeaponCandidate(
						BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
						slot
				));
			}
		}
		return new CombatSnapshot(
				targets,
				hotbarItems,
				player.getAttackStrengthScale(0.0F),
				player.entityInteractionRange()
		);
	}

	@Override
	public OperationResult attackTarget(UUID targetId) {
		requireClientThread();
		Objects.requireNonNull(targetId, "targetId must not be null");
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		MultiPlayerGameMode gameMode = minecraft.gameMode;
		if (player == null || level == null || gameMode == null) {
			return OperationResult.failed(ATTACK_UNAVAILABLE_REASON, "Minecraft attack interaction is unavailable");
		}
		Entity target = findEntity(level, targetId);
		if (!(target instanceof LivingEntity living) || !living.isAlive()) {
			return OperationResult.failed(TARGET_GONE_REASON, "Selected combat target is unavailable");
		}
		if (!player.isWithinEntityInteractionRange(target, 0.0D)) {
			return OperationResult.failed(TARGET_OUT_OF_REACH_REASON, "Selected combat target is outside survival reach");
		}
		try {
			gameMode.attack(player, target);
			player.swing(InteractionHand.MAIN_HAND);
			return OperationResult.succeeded(ATTACK_SENT_REASON, "Attack sent through the client interaction manager");
		} catch (RuntimeException exception) {
			return OperationResult.failed(ATTACK_UNAVAILABLE_REASON, safeExceptionMessage(exception));
		}
	}

	@Override
	public BlockInteractionSnapshot inspectBlock(GridPosition position) {
		requireClientThread();
		Objects.requireNonNull(position, "position must not be null");
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || level.isOutsideBuildHeight(position.y())) {
			return new BlockInteractionSnapshot(false, false, false, false, BlockFace.UP);
		}
		ClientChunkCache chunkCache = level.getChunkSource();
		boolean chunkLoaded = chunkCache.getChunk(
				position.x() >> 4,
				position.z() >> 4,
				ChunkStatus.FULL,
				false
		) != null;
		if (!chunkLoaded) {
			return new BlockInteractionSnapshot(false, false, false, false, BlockFace.UP);
		}
		BlockPos blockPosition = toBlockPos(position);
		BlockState state = level.getBlockState(blockPosition);
		HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);
		boolean visible = hit instanceof BlockHitResult blockHit && blockHit.getBlockPos().equals(blockPosition);
		BlockFace visibleFace = visible
				? fromDirection(((BlockHitResult) hit).getDirection())
				: BlockFace.UP;
		return new BlockInteractionSnapshot(
				true,
				player.isWithinBlockInteractionRange(blockPosition, 0.0D),
				visible,
				!state.isAir(),
				visibleFace
		);
	}

	@Override
	public BlockProgress breakBlock(GridPosition position, BlockFace face) {
		requireClientThread();
		Objects.requireNonNull(position, "position must not be null");
		Objects.requireNonNull(face, "face must not be null");
		ClientLevel level = minecraft.level;
		MultiPlayerGameMode gameMode = minecraft.gameMode;
		if (level == null || gameMode == null) {
			return BlockProgress.failed(BLOCK_BREAK_REJECTED_REASON, "Minecraft block breaking is unavailable");
		}
		BlockPos blockPosition = toBlockPos(position);
		if (level.getBlockState(blockPosition).isAir()) {
			return BlockProgress.succeeded("BLOCK_BROKEN", "Target block is absent");
		}
		try {
			boolean accepted = gameMode.isDestroying()
					? gameMode.continueDestroyBlock(blockPosition, toDirection(face))
					: gameMode.startDestroyBlock(blockPosition, toDirection(face));
			return accepted
					? BlockProgress.running("Breaking target block")
					: BlockProgress.failed(BLOCK_BREAK_REJECTED_REASON, "Server rules rejected block breaking");
		} catch (RuntimeException exception) {
			return BlockProgress.failed(BLOCK_BREAK_REJECTED_REASON, safeExceptionMessage(exception));
		}
	}

	@Override
	public OperationResult placeBlock(GridPosition position, BlockFace face, String itemId) {
		requireClientThread();
		Objects.requireNonNull(position, "position must not be null");
		Objects.requireNonNull(face, "face must not be null");
		Objects.requireNonNull(itemId, "itemId must not be null");
		LocalPlayer player = minecraft.player;
		MultiPlayerGameMode gameMode = minecraft.gameMode;
		if (player == null || gameMode == null) {
			return OperationResult.failed(BLOCK_PLACE_REJECTED_REASON, "Minecraft block placement is unavailable");
		}
		ItemStack selected = player.getInventory().getSelectedItem();
		String selectedItemId = BuiltInRegistries.ITEM.getKey(selected.getItem()).toString();
		if (!itemId.equals(selectedItemId) || !(selected.getItem() instanceof BlockItem)) {
			return OperationResult.failed(BLOCK_ITEM_REQUIRED_REASON, "Selected hotbar item is not the requested block item");
		}
		BlockPos blockPosition = toBlockPos(position);
		Direction direction = toDirection(face);
		Vec3 hitLocation = Vec3.atCenterOf(blockPosition).add(
				direction.getStepX() * 0.5D,
				direction.getStepY() * 0.5D,
				direction.getStepZ() * 0.5D
		);
		try {
			InteractionResult result = gameMode.useItemOn(
					player,
					InteractionHand.MAIN_HAND,
					new BlockHitResult(hitLocation, direction, blockPosition, false)
			);
			if (!result.consumesAction()) {
				return OperationResult.failed(BLOCK_PLACE_REJECTED_REASON, "Server rules rejected block placement");
			}
			player.swing(InteractionHand.MAIN_HAND);
			return OperationResult.succeeded("BLOCK_PLACE_SENT", "Block placement sent through the client interaction manager");
		} catch (RuntimeException exception) {
			return OperationResult.failed(BLOCK_PLACE_REJECTED_REASON, safeExceptionMessage(exception));
		}
	}

	@Override
	public LookResult lookAt(
			double x,
			double y,
			double z,
			float maxYawDelta,
			float maxPitchDelta,
			float toleranceDegrees
	) {
		requireClientThread();
		LocalPlayer player = requirePlayer();
		Vec3 eyePosition = player.getEyePosition();
		RotationStep step = rotationStep(
				player.getYRot(),
				player.getXRot(),
				eyePosition.x(),
				eyePosition.y(),
				eyePosition.z(),
				x,
				y,
				z,
				maxYawDelta,
				maxPitchDelta,
				toleranceDegrees
		);
		player.setYRot(step.yaw());
		player.setXRot(step.pitch());
		return new LookResult(step.withinTolerance(), step.yawErrorDegrees(), step.pitchErrorDegrees());
	}

	@Override
	public OperationResult sendChat(String message) {
		requireClientThread();
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null || !connection.isAcceptingMessages()) {
			return OperationResult.failed(CHAT_UNAVAILABLE_REASON, CHAT_UNAVAILABLE_MESSAGE);
		}
		try {
			connection.sendChat(message);
			return OperationResult.succeeded(CHAT_SENT_REASON, CHAT_SENT_MESSAGE);
		} catch (RuntimeException exception) {
			return OperationResult.failed(CHAT_FAILED_REASON, safeExceptionMessage(exception));
		}
	}

	@Override
	public OperationResult selectHotbarItem(String itemId) {
		requireClientThread();
		LocalPlayer player = requirePlayer();
		Inventory inventory = player.getInventory();
		List<String> hotbarItemIds = new ArrayList<>(Inventory.getSelectionSize());
		for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			hotbarItemIds.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		}
		int matchingSlot = findFirstMatchingSlot(hotbarItemIds, itemId);
		if (matchingSlot == MISSING_HOTBAR_SLOT) {
			return OperationResult.failed(ITEM_NOT_IN_HOTBAR_REASON, ITEM_NOT_IN_HOTBAR_MESSAGE);
		}
		try {
			inventory.setSelectedSlot(matchingSlot);
			return OperationResult.succeeded(
					ITEM_SELECTED_REASON,
					"Selected hotbar slot " + matchingSlot
			);
		} catch (RuntimeException exception) {
			return OperationResult.failed(ITEM_SELECTION_FAILED_REASON, safeExceptionMessage(exception));
		}
	}

	@Override
	public OperationResult startUsingItem(Hand hand) {
		requireClientThread();
		Objects.requireNonNull(hand, "hand must not be null");
		LocalPlayer player = minecraft.player;
		MultiPlayerGameMode gameMode = minecraft.gameMode;
		if (player == null || gameMode == null) {
			return OperationResult.failed(ITEM_USE_UNAVAILABLE_REASON, ITEM_USE_UNAVAILABLE_MESSAGE);
		}
		try {
			minecraft.options.keyUse.setDown(true);
			InteractionResult result = gameMode.useItem(player, toMinecraftHand(hand));
			if (!result.consumesAction()) {
				stopUsingItem();
				return OperationResult.failed(ITEM_USE_REJECTED_REASON, ITEM_USE_REJECTED_MESSAGE);
			}
			return OperationResult.succeeded(ITEM_USE_STARTED_REASON, ITEM_USE_STARTED_MESSAGE);
		} catch (RuntimeException exception) {
			try {
				stopUsingItem();
			} catch (RuntimeException releaseException) {
				exception.addSuppressed(releaseException);
			}
			return OperationResult.failed(ITEM_USE_FAILED_REASON, safeExceptionMessage(exception));
		}
	}

	@Override
	public void stopUsingItem() {
		requireClientThread();
		releaseResources(
				() -> minecraft.options.keyUse.setDown(false),
				() -> {
					LocalPlayer player = minecraft.player;
					if (player == null || !player.isUsingItem()) {
						return;
					}
					if (minecraft.gameMode == null) {
						player.stopUsingItem();
					} else {
						minecraft.gameMode.releaseUsingItem(player);
					}
				}
		);
	}

	@Override
	public void releaseAll() {
		requireClientThread();
		releaseResources(
				this::releaseSyntheticKeys,
				this::stopUsingItem,
				this::abortBlockBreaking
		);
	}

	private void releaseSyntheticKeys() {
		releaseKeys(
				() -> minecraft.options.keyUp.setDown(false),
				() -> minecraft.options.keyLeft.setDown(false),
				() -> minecraft.options.keyDown.setDown(false),
				() -> minecraft.options.keyRight.setDown(false),
				() -> minecraft.options.keyJump.setDown(false),
				() -> minecraft.options.keyShift.setDown(false),
				() -> minecraft.options.keySprint.setDown(false),
				() -> minecraft.options.keyUse.setDown(false),
				() -> minecraft.options.keyAttack.setDown(false)
		);
	}

	private void abortBlockBreaking() {
		if (minecraft.gameMode != null) {
			minecraft.gameMode.stopDestroyBlock();
		}
	}

	private static Entity findEntity(ClientLevel level, UUID targetId) {
		for (Entity entity : level.entitiesForRendering()) {
			if (entity.getUUID().equals(targetId)) {
				return entity;
			}
		}
		return null;
	}

	private static BlockPos toBlockPos(GridPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}

	private static Direction toDirection(BlockFace face) {
		return switch (face) {
			case DOWN -> Direction.DOWN;
			case UP -> Direction.UP;
			case NORTH -> Direction.NORTH;
			case SOUTH -> Direction.SOUTH;
			case WEST -> Direction.WEST;
			case EAST -> Direction.EAST;
		};
	}

	private static BlockFace fromDirection(Direction direction) {
		return switch (direction) {
			case DOWN -> BlockFace.DOWN;
			case UP -> BlockFace.UP;
			case NORTH -> BlockFace.NORTH;
			case SOUTH -> BlockFace.SOUTH;
			case WEST -> BlockFace.WEST;
			case EAST -> BlockFace.EAST;
		};
	}

	private LocalPlayer requirePlayer() {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			throw new IllegalStateException("Minecraft player is unavailable");
		}
		return player;
	}

	private void requireClientThread() {
		if (!minecraft.isSameThread()) {
			throw new IllegalStateException("Minecraft action APIs must run on the client thread");
		}
	}

	static SafetyState classifySafety(
			boolean connectionPresent,
			boolean connectionAcceptingMessages,
			boolean worldPresent,
			boolean playerPresent,
			boolean playerAlive,
			boolean screenOpen
	) {
		if (!connectionPresent || !connectionAcceptingMessages) {
			return SafetyState.DISCONNECTED;
		}
		if (!worldPresent) {
			return SafetyState.WORLD_UNAVAILABLE;
		}
		if (!playerPresent) {
			return SafetyState.PLAYER_UNAVAILABLE;
		}
		if (!playerAlive) {
			return SafetyState.PLAYER_DEAD;
		}
		return screenOpen ? SafetyState.SCREEN_OPEN : SafetyState.READY;
	}

	static RotationStep rotationStep(
			float currentYaw,
			float currentPitch,
			double eyeX,
			double eyeY,
			double eyeZ,
			double targetX,
			double targetY,
			double targetZ,
			float maxYawDelta,
			float maxPitchDelta,
			float toleranceDegrees
	) {
		requireFinite(currentYaw, "currentYaw");
		requireFinite(currentPitch, "currentPitch");
		requireFinite(eyeX, "eyeX");
		requireFinite(eyeY, "eyeY");
		requireFinite(eyeZ, "eyeZ");
		requireFinite(targetX, "targetX");
		requireFinite(targetY, "targetY");
		requireFinite(targetZ, "targetZ");
		requirePositiveFinite(maxYawDelta, "maxYawDelta");
		requirePositiveFinite(maxPitchDelta, "maxPitchDelta");
		requirePositiveFinite(toleranceDegrees, "toleranceDegrees");

		double xDifference = targetX - eyeX;
		double yDifference = targetY - eyeY;
		double zDifference = targetZ - eyeZ;
		double distanceSquared = xDifference * xDifference
				+ yDifference * yDifference
				+ zDifference * zDifference;
		if (distanceSquared <= TARGET_DISTANCE_EPSILON_SQUARED) {
			return new RotationStep(currentYaw, currentPitch, 0.0F, 0.0F, true);
		}

		double horizontalDistance = Math.sqrt(xDifference * xDifference + zDifference * zDifference);
		float targetYaw = (float) Math.toDegrees(Math.atan2(zDifference, xDifference)) - 90.0F;
		float targetPitch = (float) -Math.toDegrees(Math.atan2(yDifference, horizontalDistance));
		float yawDelta = Mth.wrapDegrees(targetYaw - currentYaw);
		float pitchDelta = targetPitch - currentPitch;
		float nextYaw = currentYaw + Mth.clamp(yawDelta, -maxYawDelta, maxYawDelta);
		float nextPitch = Mth.clamp(
				currentPitch + Mth.clamp(pitchDelta, -maxPitchDelta, maxPitchDelta),
				-90.0F,
				90.0F
		);
		float yawError = Math.abs(Mth.wrapDegrees(targetYaw - nextYaw));
		float pitchError = Math.abs(targetPitch - nextPitch);
		return new RotationStep(
				nextYaw,
				nextPitch,
				yawError,
				pitchError,
				yawError <= toleranceDegrees && pitchError <= toleranceDegrees
		);
	}

	static int findFirstMatchingSlot(List<String> hotbarItemIds, String itemId) {
		Objects.requireNonNull(hotbarItemIds, "hotbarItemIds must not be null");
		Objects.requireNonNull(itemId, "itemId must not be null");
		for (int slot = 0; slot < hotbarItemIds.size(); slot++) {
			if (itemId.equals(hotbarItemIds.get(slot))) {
				return slot;
			}
		}
		return MISSING_HOTBAR_SLOT;
	}

	static void releaseResources(Runnable... releases) {
		Objects.requireNonNull(releases, "releases must not be null");
		IllegalStateException failure = null;
		for (Runnable release : releases) {
			try {
				Objects.requireNonNull(release, "release must not be null").run();
			} catch (RuntimeException exception) {
				if (failure == null) {
					failure = new IllegalStateException("Could not release all action resources", exception);
				} else {
					failure.addSuppressed(exception);
				}
			}
		}
		if (failure != null) {
			throw failure;
		}
	}

	static void releaseKeys(Runnable... keyReleases) {
		releaseResources(keyReleases);
	}

	private static InteractionHand toMinecraftHand(Hand hand) {
		return switch (hand) {
			case MAIN_HAND -> InteractionHand.MAIN_HAND;
			case OFF_HAND -> InteractionHand.OFF_HAND;
		};
	}

	private static void requirePositiveFinite(float value, String field) {
		requireFinite(value, field);
		if (value <= 0.0F) {
			throw new IllegalArgumentException(field + " must be positive");
		}
	}

	private static void requireFinite(double value, String field) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(field + " must be finite");
		}
	}

	private static String safeExceptionMessage(RuntimeException exception) {
		String message = exception.getMessage();
		String safe = message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
		return safe.length() <= ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH
				? safe
				: safe.substring(0, ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH);
	}

	record RotationStep(
			float yaw,
			float pitch,
			float yawErrorDegrees,
			float pitchErrorDegrees,
			boolean withinTolerance
	) {
		RotationStep {
			requireFinite(yaw, "yaw");
			requireFinite(pitch, "pitch");
			requireFinite(yawErrorDegrees, "yawErrorDegrees");
			requireFinite(pitchErrorDegrees, "pitchErrorDegrees");
			if (yawErrorDegrees < 0.0F || pitchErrorDegrees < 0.0F) {
				throw new IllegalArgumentException("rotation errors must not be negative");
			}
		}
	}
}
