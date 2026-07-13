package dev.agaminggod.arenaagents.client.perception;

import dev.agaminggod.arenaagents.protocol.ActionResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ObservationCollector {
	public static final String STATUS_NOT_CLIENT_THREAD = "not_client_thread";
	public static final String STATUS_WORLD_NOT_READY = "world_not_ready";
	public static final String STATUS_COLLECTION_FAILED = "collection_failed";

	private static final int MAX_ENTITY_NAME_LENGTH = 256;
	private static final int[][] CARDINAL_OFFSETS = {
			{1, 0, 0},
			{-1, 0, 0},
			{0, 1, 0},
			{0, -1, 0},
			{0, 0, 1},
			{0, 0, -1}
	};
	private static final Comparator<BlockOffset> OFFSET_ORDER = Comparator
			.comparingLong(BlockOffset::distanceSquared)
			.thenComparingInt(BlockOffset::x)
			.thenComparingInt(BlockOffset::y)
			.thenComparingInt(BlockOffset::z);

	private final Minecraft minecraft;
	private final int observationRadius;
	private final Supplier<Observation.ActionStatus> currentActionSupplier;
	private final Supplier<ActionResult> lastResultSupplier;

	public ObservationCollector(
			Minecraft minecraft,
			int observationRadius,
			Supplier<Observation.ActionStatus> currentActionSupplier,
			Supplier<ActionResult> lastResultSupplier
	) {
		this.minecraft = Objects.requireNonNull(minecraft, "minecraft must not be null");
		this.observationRadius = ObservationLimits.clampObservationRadius(observationRadius);
		this.currentActionSupplier = Objects.requireNonNull(
				currentActionSupplier,
				"currentActionSupplier must not be null"
		);
		this.lastResultSupplier = Objects.requireNonNull(lastResultSupplier, "lastResultSupplier must not be null");
	}

	public Observation collect() {
		if (!minecraft.isSameThread()) {
			return Observation.unavailable(STATUS_NOT_CLIENT_THREAD);
		}
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level == null || player == null) {
			return Observation.unavailable(STATUS_WORLD_NOT_READY);
		}

		try {
			return collectReady(level, player);
		} catch (RuntimeException exception) {
			return Observation.unavailable(STATUS_COLLECTION_FAILED);
		}
	}

	private Observation collectReady(ClientLevel level, LocalPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		Observation.ActionStatus currentAction = currentActionSupplier.get();
		ActionResult lastResult = lastResultSupplier.get();
		return new Observation(
				true,
				Observation.READY_STATUS,
				new Observation.Position(
						finiteOrZero(player.getX()),
						finiteOrZero(player.getY()),
						finiteOrZero(player.getZ())
				),
				new Observation.Velocity(
						finiteOrZero(velocity.x()),
						finiteOrZero(velocity.y()),
						finiteOrZero(velocity.z())
				),
				new Observation.View(finiteOrZero(player.getYRot()), finiteOrZero(player.getXRot())),
				collectPlayerStatus(player),
				collectInventory(player.getInventory()),
				collectEntities(level, player),
				collectBlocks(level, player),
				new Observation.WorldStatus(
						level.dimension().identifier().toString(),
						level.getGameTime(),
						level.getDefaultClockTime(),
						level.isRaining(),
						level.isThundering()
				),
				currentAction == null ? Observation.ActionStatus.none() : currentAction,
				lastResult == null ? Observation.ResultStatus.none() : Observation.ResultStatus.from(lastResult)
		);
	}

	private static Observation.PlayerStatus collectPlayerStatus(LocalPlayer player) {
		List<Observation.EffectStatus> effects = player.getActiveEffects().stream()
				.map(ObservationCollector::snapshotEffect)
				.sorted(Comparator
						.comparing(Observation.EffectStatus::effectId)
						.thenComparingInt(Observation.EffectStatus::amplifier)
						.thenComparingInt(Observation.EffectStatus::durationTicks))
				.toList();
		return new Observation.PlayerStatus(
				finiteNonNegativeOrZero(player.getHealth()),
				finiteNonNegativeOrZero(player.getMaxHealth()),
				Math.max(0, player.getFoodData().getFoodLevel()),
				Math.max(0, player.getArmorValue()),
				ObservationLimits.truncateEffects(effects)
		);
	}

	private static Observation.EffectStatus snapshotEffect(MobEffectInstance effect) {
		String effectId = effect.getEffect().unwrapKey()
				.map(key -> key.identifier().toString())
				.orElseGet(effect::getDescriptionId);
		return new Observation.EffectStatus(
				effectId,
				Math.max(0, effect.getAmplifier()),
				Math.max(0, effect.getDuration()),
				effect.isAmbient(),
				effect.isVisible()
		);
	}

	private static InventorySnapshot collectInventory(Inventory inventory) {
		Map<String, Integer> countsByItem = new TreeMap<>();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) {
				countsByItem.merge(itemId(stack), stack.getCount(), Integer::sum);
			}
		}
		List<InventorySnapshot.ItemSummary> summaries = countsByItem.entrySet().stream()
				.map(entry -> new InventorySnapshot.ItemSummary(entry.getKey(), entry.getValue()))
				.toList();
		ItemStack selected = inventory.getSelectedItem();
		return new InventorySnapshot(
				inventory.getSelectedSlot(),
				selected.isEmpty() ? InventorySnapshot.EMPTY_ITEM_ID : itemId(selected),
				selected.isEmpty() ? 0 : selected.getCount(),
				ObservationLimits.truncateInventorySummaries(summaries)
		);
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private List<EntitySnapshot> collectEntities(ClientLevel level, LocalPlayer player) {
		List<LivingEntity> nearbyEntities = level.getEntitiesOfClass(
				LivingEntity.class,
				player.getBoundingBox().inflate(observationRadius),
				entity -> entity != player && entity.isAlive()
		);
		List<EntitySnapshot> entities = new ArrayList<>(nearbyEntities.size());
		for (LivingEntity entity : nearbyEntities) {
			double distanceSquared = player.distanceToSqr(entity);
			if (isEntityDistanceInRadius(distanceSquared, observationRadius)) {
				entities.add(snapshotEntity(entity, distanceSquared));
			}
		}
		return ObservationLimits.truncateEntities(ObservationOrdering.entities(entities));
	}

	static boolean isEntityDistanceInRadius(double distanceSquared, int radius) {
		int boundedRadius = ObservationLimits.clampObservationRadius(radius);
		long radiusSquared = (long) boundedRadius * boundedRadius;
		return Double.isFinite(distanceSquared)
				&& distanceSquared >= 0.0D
				&& distanceSquared <= radiusSquared;
	}

	private static EntitySnapshot snapshotEntity(LivingEntity entity, double distanceSquared) {
		return new EntitySnapshot(
				entity.getUUID().toString(),
				BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
				truncate(entity.getName().getString(), MAX_ENTITY_NAME_LENGTH),
				finiteOrZero(entity.getX()),
				finiteOrZero(entity.getY()),
				finiteOrZero(entity.getZ()),
				distanceSquared,
				finiteNonNegativeOrZero(entity.getHealth()),
				finiteNonNegativeOrZero(entity.getMaxHealth()),
				entity instanceof Enemy
		);
	}

	private List<BlockSnapshot> collectBlocks(ClientLevel level, LocalPlayer player) {
		BlockPos center = player.blockPosition();
		BlockPos.MutableBlockPos mutablePosition = new BlockPos.MutableBlockPos();
		return collectNearbyBlocks(
				center.getX(),
				center.getY(),
				center.getZ(),
				observationRadius,
				new BlockProbe() {
					@Override
					public boolean isChunkCached(int chunkX, int chunkZ) {
						return hasCachedChunk(level.getChunkSource(), chunkX, chunkZ);
					}

					@Override
					public BlockSnapshot snapshot(int x, int y, int z, double distanceSquared) {
						mutablePosition.set(x, y, z);
						BlockState state = level.getBlockState(mutablePosition);
						if (state.isAir()) {
							return null;
						}
						FluidState fluidState = state.getFluidState();
						VoxelShape collisionShape = state.getCollisionShape(level, mutablePosition);
						return new BlockSnapshot(
								x,
								y,
								z,
								BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
								BuiltInRegistries.FLUID.getKey(fluidState.getType()).toString(),
								collisionShape.isEmpty(),
								Block.isShapeFullBlock(collisionShape),
								distanceSquared
						);
					}
				}
		);
	}

	static boolean hasCachedChunk(ClientChunkCache chunkCache, int chunkX, int chunkZ) {
		Objects.requireNonNull(chunkCache, "chunkCache must not be null");
		return chunkCache.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
	}

	static List<BlockSnapshot> collectNearbyBlocks(
			int centerX,
			int centerY,
			int centerZ,
			int requestedRadius,
			BlockProbe probe
	) {
		Objects.requireNonNull(probe, "probe must not be null");
		int radius = ObservationLimits.clampObservationRadius(requestedRadius);
		long radiusSquared = (long) radius * radius;
		PriorityQueue<BlockOffset> pending = new PriorityQueue<>(OFFSET_ORDER);
		Set<BlockOffset> visited = new HashSet<>();
		BlockOffset origin = new BlockOffset(0, 0, 0);
		pending.add(origin);
		visited.add(origin);
		List<BlockSnapshot> blocks = new ArrayList<>(ObservationLimits.MAX_BLOCKS);
		int sampledPositions = 0;

		while (!pending.isEmpty()
				&& sampledPositions < ObservationLimits.MAX_BLOCK_SCAN_POSITIONS
				&& blocks.size() < ObservationLimits.MAX_BLOCKS) {
			BlockOffset offset = pending.remove();
			sampledPositions++;
			int x = centerX + offset.x();
			int y = centerY + offset.y();
			int z = centerZ + offset.z();
			if (probe.isChunkCached(x >> 4, z >> 4)) {
				BlockSnapshot snapshot = probe.snapshot(x, y, z, offset.distanceSquared());
				if (snapshot != null) {
					blocks.add(snapshot);
				}
			}
			enqueueNeighbors(offset, radiusSquared, visited, pending);
		}

		return ObservationLimits.truncateBlocks(ObservationOrdering.blocks(blocks), ObservationLimits.MAX_BLOCKS);
	}

	private static void enqueueNeighbors(
			BlockOffset offset,
			long radiusSquared,
			Set<BlockOffset> visited,
			PriorityQueue<BlockOffset> pending
	) {
		for (int[] cardinalOffset : CARDINAL_OFFSETS) {
			BlockOffset neighbor = new BlockOffset(
					offset.x() + cardinalOffset[0],
					offset.y() + cardinalOffset[1],
					offset.z() + cardinalOffset[2]
			);
			if (neighbor.distanceSquared() <= radiusSquared && visited.add(neighbor)) {
				pending.add(neighbor);
			}
		}
	}

	private static String truncate(String value, int maximumLength) {
		return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
	}

	private static double finiteOrZero(double value) {
		return Double.isFinite(value) ? value : 0.0D;
	}

	private static float finiteOrZero(float value) {
		return Float.isFinite(value) ? value : 0.0F;
	}

	private static double finiteNonNegativeOrZero(double value) {
		return Double.isFinite(value) && value >= 0.0D ? value : 0.0D;
	}

	private static float finiteNonNegativeOrZero(float value) {
		return Float.isFinite(value) && value >= 0.0F ? value : 0.0F;
	}

	interface BlockProbe {
		boolean isChunkCached(int chunkX, int chunkZ);

		BlockSnapshot snapshot(int x, int y, int z, double distanceSquared);
	}

	private record BlockOffset(int x, int y, int z) {
		private long distanceSquared() {
			return (long) x * x + (long) y * y + (long) z * z;
		}
	}
}
