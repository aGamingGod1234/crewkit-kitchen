package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Objects;

/**
 * Read-only, chunk-safe terrain view used by the bounded local planner.
 */
public final class MinecraftNavigationWorld implements WalkabilityView {
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
		if (isHazardous(state)) {
			return Cell.HAZARD;
		}
		VoxelShape collision = state.getCollisionShape(level, blockPosition);
		if (collision.isEmpty()) {
			return Cell.CLEAR;
		}
		return Block.isShapeFullBlock(collision) ? Cell.SAFE_SUPPORT : Cell.BLOCKED;
	}

	private static boolean isHazardous(BlockState state) {
		return !state.getFluidState().isEmpty()
				|| state.is(Blocks.FIRE)
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
