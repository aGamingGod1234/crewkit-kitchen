package dev.agaminggod.arenaagents.client.navigation;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class MinecraftWalkabilityView implements WalkabilityView {
	private final Minecraft minecraft;

	public MinecraftWalkabilityView(Minecraft minecraft) {
		this.minecraft = Objects.requireNonNull(minecraft, "minecraft must not be null");
	}

	@Override
	public Cell cellAt(GridPosition position) {
		Objects.requireNonNull(position, "position must not be null");
		requireClientThread();
		ClientLevel level = minecraft.level;
		if (level == null || level.isOutsideBuildHeight(position.y())) {
			return Cell.UNLOADED;
		}
		ClientChunkCache chunkCache = level.getChunkSource();
		boolean cached = chunkCache.getChunk(
				position.x() >> 4,
				position.z() >> 4,
				ChunkStatus.FULL,
				false
		) != null;
		return inspectCachedCell(cached, () -> readCellFacts(level, position));
	}

	static Cell inspectCachedCell(boolean cached, Supplier<CellFacts> factsSupplier) {
		Objects.requireNonNull(factsSupplier, "factsSupplier must not be null");
		if (!cached) {
			return Cell.UNLOADED;
		}
		CellFacts facts = Objects.requireNonNull(factsSupplier.get(), "cell facts must not be null");
		if (facts.hazardous()) {
			return Cell.HAZARD;
		}
		if (facts.collisionEmpty()) {
			return Cell.CLEAR;
		}
		return facts.safeSupport() ? Cell.SAFE_SUPPORT : Cell.BLOCKED;
	}

	private static CellFacts readCellFacts(ClientLevel level, GridPosition position) {
		BlockPos blockPosition = new BlockPos(position.x(), position.y(), position.z());
		BlockState state = level.getBlockState(blockPosition);
		VoxelShape collisionShape = state.getCollisionShape(level, blockPosition);
		return new CellFacts(
				isHazardous(state),
				collisionShape.isEmpty(),
				Block.isShapeFullBlock(collisionShape)
		);
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

	private void requireClientThread() {
		if (!minecraft.isSameThread()) {
			throw new IllegalStateException("Minecraft walkability reads must run on the client thread");
		}
	}

	record CellFacts(boolean hazardous, boolean collisionEmpty, boolean safeSupport) {
	}
}
