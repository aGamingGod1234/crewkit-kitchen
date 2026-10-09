package dev.agaminggod.arenaagents.crewkit.set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Exterior scenery only: never writes the kitchen footprint or the open camera side. */
public final class Exterior {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
			| Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	private Exterior() {
	}

	/** Call on the server thread after the shell. Repeated calls produce the same block scenery. */
	public static void place(ServerLevel level, BlockPos origin) {
		new Placer(level, origin).placeAll();
	}

	private static final class Placer {
		private final ServerLevel level;
		private final BlockPos origin;

		Placer(ServerLevel level, BlockPos origin) {
			this.level = level;
			this.origin = origin;
		}

		void set(int x, int y, int z, BlockState state) {
			if (x < -6 || x > 33 || y < -1 || y > 10 || z < -6 || z > 21
					|| (x >= 0 && x <= 27 && z >= 0)) {
				throw new IllegalArgumentException("Exterior placement outside its zone: " + x + "," + y + "," + z);
			}
			BlockPos pos = origin.offset(x, y, z);
			if (!level.getBlockState(pos).equals(state)) level.setBlock(pos, state, FLAGS);
		}

		void set(int x, int y, int z, Block block) {
			set(x, y, z, block.defaultBlockState());
		}

		void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
			for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++)
				for (int x = x0; x <= x1; x++) set(x, y, z, block);
		}

		void placeAll() {
			// Clear only the owned exterior strips, including old foliage and smoke fixtures.
			fill(-6, 1, -6, 33, 10, -1, Blocks.AIR);
			fill(-6, 1, 0, -1, 10, 21, Blocks.AIR);
			fill(28, 1, 0, 33, 10, 21, Blocks.AIR);
			fill(-6, -1, -6, 33, -1, -1, Blocks.STONE_BRICKS);
			fill(-6, -1, 0, -1, -1, 21, Blocks.STONE_BRICKS);
			fill(28, -1, 0, 33, -1, 21, Blocks.STONE_BRICKS);
			fill(-6, 0, -6, 33, 0, -1, Blocks.MOSS_BLOCK);
			fill(-6, 0, 0, -1, 0, 21, Blocks.MOSS_BLOCK);
			fill(28, 0, 0, 33, 0, 21, Blocks.MOSS_BLOCK);
			wallsAndRoof();
			deliveryLane();
			courtyard(-1);
			courtyard(28);
		}

		private void wallsAndRoof() {
			fill(-1, 1, -1, 28, 7, -1, Blocks.BRICKS);
			fill(-1, 0, -1, 28, 0, -1, Blocks.STONE_BRICKS);
			for (int x : new int[] {-1, 28}) {
				fill(x, 1, 0, x, 7, 21, Blocks.BRICKS);
				fill(x, 0, 0, x, 0, 21, Blocks.STONE_BRICKS);
				// Broad reveal accommodates the shell windows without adding a second glass layer.
				fill(x, 1, 10, x, 5, 18, Blocks.AIR);
				fill(x, 0, 10, x, 0, 18, Blocks.SMOOTH_STONE);
				fill(x, 6, 10, x, 6, 18, Blocks.SMOOTH_STONE);
				for (int z : new int[] {0, 9, 19, 21}) fill(x, 1, z, x, 7, z, Blocks.STONE_BRICKS);
				fill(x, 8, 0, x, 8, 21, Blocks.DEEPSLATE_TILES);
				for (int z = 0; z <= 21; z++) set(x, 9, z, slab(Blocks.DEEPSLATE_TILE_SLAB));
			}
			fill(28, 1, 4, 28, 3, 7, Blocks.AIR);
			fill(28, 4, 4, 28, 4, 7, Blocks.SMOOTH_STONE);
			fill(-1, 8, -2, 28, 8, -1, Blocks.DEEPSLATE_TILES);
			for (int x = -1; x <= 28; x++) {
				set(x, 8, -3, Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
						.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
						.setValue(BlockStateProperties.HALF, Half.BOTTOM));
				set(x, 9, -1, slab(Blocks.DEEPSLATE_TILE_SLAB));
			}
			// The flue sits outside z=0, aligned with the v2 stove at x=12..14.
			fill(12, 8, -2, 14, 8, -1, Blocks.STONE_BRICKS);
			fill(12, 9, -2, 14, 9, -1, Blocks.STONE_BRICK_SLAB);
			set(13, 9, -1, Blocks.HAY_BLOCK);
			set(13, 10, -1, Blocks.CAMPFIRE.defaultBlockState()
					.setValue(BlockStateProperties.LIT, true)
					.setValue(BlockStateProperties.SIGNAL_FIRE, true));
			for (int x : new int[] {2, 13, 25}) {
				set(x, 0, -4, Blocks.STONE_BRICKS);
				set(x, 1, -4, Blocks.FLOWERING_AZALEA);
				set(x + 1, 1, -4, Blocks.LANTERN);
			}
		}

		private void deliveryLane() {
			fill(28, 0, 3, 33, 0, 8, Blocks.STONE_BRICKS);
			fill(29, 0, 0, 30, 0, 10, Blocks.STONE_BRICKS);
			fill(31, 0, 1, 33, 0, 3, Blocks.POLISHED_ANDESITE);
			BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
			set(32, 1, 2, barrel);
			set(32, 2, 2, barrel);
			set(31, 1, 2, barrel);
			set(33, 1, 2, Blocks.HAY_BLOCK);
			set(33, 1, 3, Blocks.HAY_BLOCK);
			post(32, 4);
			// A stationary block cart avoids duplicate vehicle entities on repeated builds.
			for (int x : new int[] {30, 32}) for (int z : new int[] {8, 10})
				set(x, 1, z, Blocks.POLISHED_BLACKSTONE);
			for (int z = 8; z <= 10; z++) {
				set(31, 1, z, Blocks.SPRUCE_PLANKS);
				set(30, 2, z, slab(Blocks.SPRUCE_SLAB));
				set(32, 2, z, slab(Blocks.SPRUCE_SLAB));
			}
			set(31, 2, 8, barrel);
			set(31, 2, 9, Blocks.HAY_BLOCK);
			set(31, 1, 7, fence(false, false, true, true));
			set(33, 1, 0, Blocks.FLOWERING_AZALEA);
			set(33, 1, 11, Blocks.FLOWERING_AZALEA);
		}

		private void courtyard(int wallX) {
			boolean east = wallX == 28;
			int edge = east ? 33 : -6;
			int treeX = east ? 31 : -4;
			int pathX = east ? 29 : -2;
			for (int z = 11; z <= 20; z++) {
				set(pathX, 0, z, (z % 3 == 0) ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS);
				set(edge, 1, z, fence(false, false, z > 11, z < 20));
			}
			for (int x = east ? 29 : -5; x <= (east ? 32 : -2); x++) {
				set(x, 1, 20, fence(true, true, false, false));
			}
			post(edge, 11);
			post(edge, 20);
			for (int z : new int[] {12, 16, 18}) {
				set(east ? 32 : -5, 1, z, Blocks.FLOWERING_AZALEA);
				set(east ? 30 : -3, 1, z, Blocks.AZALEA);
			}
			fill(treeX, 1, 14, treeX, 4, 14, Blocks.CHERRY_LOG);
			BlockState leaves = Blocks.CHERRY_LEAVES.defaultBlockState()
					.setValue(BlockStateProperties.PERSISTENT, true);
			for (int y = 4; y <= 6; y++) for (int dx = -2; dx <= 2; dx++)
				for (int dz = -2; dz <= 2; dz++) {
					if (Math.abs(dx) + Math.abs(dz) <= (y == 6 ? 2 : 3)
							&& !(dx == 0 && dz == 0 && y == 4)) set(treeX + dx, y, 14 + dz, leaves);
				}
			for (int z : new int[] {12, 17}) {
				set(east ? 32 : -5, 0, z, Blocks.SMOOTH_STONE);
				set(east ? 32 : -5, 1, z, Blocks.END_ROD.defaultBlockState()
						.setValue(BlockStateProperties.FACING, Direction.UP));
			}
			set(treeX, 1, 18, Blocks.OAK_LEAVES.defaultBlockState()
					.setValue(BlockStateProperties.PERSISTENT, true));
			set(treeX, 2, 18, Blocks.LANTERN);
		}

		private void post(int x, int z) {
			set(x, 0, z, Blocks.STONE_BRICKS);
			fill(x, 1, z, x, 3, z, Blocks.SPRUCE_LOG);
			set(x, 4, z, Blocks.LANTERN);
		}

		private static BlockState slab(Block block) {
			return block.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
		}

		private static BlockState fence(boolean east, boolean west, boolean north, boolean south) {
			return Blocks.SPRUCE_FENCE.defaultBlockState()
					.setValue(BlockStateProperties.EAST, east).setValue(BlockStateProperties.WEST, west)
					.setValue(BlockStateProperties.NORTH, north).setValue(BlockStateProperties.SOUTH, south);
		}
	}
}
