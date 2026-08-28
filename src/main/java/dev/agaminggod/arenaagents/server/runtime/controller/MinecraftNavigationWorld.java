package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Objects;

/**
 * Read-only, chunk-safe terrain view used by the bounded local planner.
 */
public final class MinecraftNavigationWorld implements WalkabilityView {
	static final int MAX_SHALLOW_WATER_CROSSING = 4;
	private static final Direction[][] CROSSING_AXES = {
			{Direction.WEST, Direction.EAST},
			{Direction.NORTH, Direction.SOUTH}
	};

	private final ServerLevel level;

	public MinecraftNavigationWorld(ServerLevel level) {
		this.level = Objects.requireNonNull(level, "level must not be null");
	}

	@Override
	public Cell cellAt(GridPosition position) {
		Objects.requireNonNull(position, "position must not be null");
		if (level.isOutsideBuildHeight(position.y())
				|| !level.hasChunk(position.x() >> 4, position.z() >> 4)) {
			return Cell.UNLOADED;
		}
		BlockPos blockPosition = new BlockPos(position.x(), position.y(), position.z());
		BlockState state = level.getBlockState(blockPosition);
		VoxelShape collision = state.getCollisionShape(level, blockPosition);
		return classifyCell(state, collision, state.is(Blocks.WATER) && isBoundedShallowWater(blockPosition));
	}

	boolean isShallowWater(GridPosition position) {
		Objects.requireNonNull(position, "position must not be null");
		return cellAt(position) == Cell.CLEAR
				&& level.getBlockState(new BlockPos(position.x(), position.y(), position.z()))
				.getFluidState().is(FluidTags.WATER);
	}

	static Cell classifyCell(BlockState state, VoxelShape collision, boolean boundedShallowWater) {
		Objects.requireNonNull(state, "state must not be null");
		Objects.requireNonNull(collision, "collision must not be null");
		if (isIntrinsicHazard(state)) return Cell.HAZARD;
		if (!state.getFluidState().isEmpty()) {
			return state.is(Blocks.WATER) && boundedShallowWater
					? Cell.CLEAR
					: Cell.HAZARD;
		}
		if (isOpenDoor(state)) return Cell.CLEAR;
		if (collision.isEmpty()) return Cell.CLEAR;
		if (Block.isShapeFullBlock(collision) || isWalkablePartialSupport(state)) return Cell.SAFE_SUPPORT;
		return Cell.BLOCKED;
	}

	private boolean isBoundedShallowWater(BlockPos position) {
		if (!isOneBlockDeepWater(position)) return false;
		for (Direction[] axis : CROSSING_AXES) {
			int negativeBank = distanceToDryBank(position, axis[0]);
			int positiveBank = distanceToDryBank(position, axis[1]);
			if (negativeBank > 0 && positiveBank > 0
					&& negativeBank + positiveBank - 1 <= MAX_SHALLOW_WATER_CROSSING) return true;
		}
		return false;
	}

	private int distanceToDryBank(BlockPos origin, Direction direction) {
		for (int distance = 1; distance <= MAX_SHALLOW_WATER_CROSSING; distance++) {
			BlockPos candidate = origin.relative(direction, distance);
			if (!level.hasChunk(candidate.getX() >> 4, candidate.getZ() >> 4)) return -1;
			if (isOneBlockDeepWater(candidate)) continue;
			return isDryStandableBank(candidate) ? distance : -1;
		}
		return -1;
	}

	private boolean isOneBlockDeepWater(BlockPos position) {
		if (level.isOutsideBuildHeight(position.getY()) || level.isOutsideBuildHeight(position.getY() - 1)
				|| !level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) return false;
		BlockState water = level.getBlockState(position);
		if (!water.getFluidState().is(FluidTags.WATER)
				|| !water.getCollisionShape(level, position).isEmpty()) return false;
		BlockPos abovePosition = position.above();
		BlockState above = level.getBlockState(abovePosition);
		if (!above.getFluidState().isEmpty() || !above.getCollisionShape(level, abovePosition).isEmpty()) return false;
		BlockPos supportPosition = position.below();
		BlockState support = level.getBlockState(supportPosition);
		return classifyCell(support, support.getCollisionShape(level, supportPosition), false) == Cell.SAFE_SUPPORT;
	}

	private boolean isDryStandableBank(BlockPos feetPosition) {
		BlockState feet = level.getBlockState(feetPosition);
		BlockPos headPosition = feetPosition.above();
		BlockState head = level.getBlockState(headPosition);
		if (!feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty()
				|| !feet.getCollisionShape(level, feetPosition).isEmpty()
				|| !head.getCollisionShape(level, headPosition).isEmpty()) return false;
		BlockPos supportPosition = feetPosition.below();
		BlockState support = level.getBlockState(supportPosition);
		return classifyCell(support, support.getCollisionShape(level, supportPosition), false) == Cell.SAFE_SUPPORT;
	}

	private static boolean isWalkablePartialSupport(BlockState state) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return path.endsWith("_slab")
				|| path.endsWith("_stairs")
				|| path.equals("farmland")
				|| path.equals("dirt_path");
	}

	private static boolean isOpenDoor(BlockState state) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return path.endsWith("_door")
				&& !path.endsWith("_trapdoor")
				&& state.hasProperty(BlockStateProperties.OPEN)
				&& state.getValue(BlockStateProperties.OPEN);
	}

	private static boolean isIntrinsicHazard(BlockState state) {
		return state.is(Blocks.FIRE)
				|| state.is(Blocks.SOUL_FIRE)
				|| state.is(Blocks.CACTUS)
				|| state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.CAMPFIRE)
				|| state.is(Blocks.SOUL_CAMPFIRE)
				|| state.is(Blocks.SWEET_BERRY_BUSH)
				|| state.is(Blocks.WITHER_ROSE)
				|| state.is(Blocks.POWDER_SNOW);
	}
}
