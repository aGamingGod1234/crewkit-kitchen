package dev.agaminggod.arenaagents.crewkit.set;

import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;

/**
 * Set dressing for the CrewKit kitchen: the cooking line along the north wall and the dining-room props.
 * Call {@link #place} after the shell is built. Every block state is written explicitly, so running it twice
 * gives the same result; item frames carry {@link #TAG} and are replaced, not duplicated.
 */
public final class KitchenDecor {
	/** Entity tag on the item frames this class spawns. */
	public static final String TAG = "ck_decor";

	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	/** North-west corner of each 4x2 table (A, B, C, D, E), matching the shell's tables and CrewkitAnchors.SEATS. */
	static final int[][] TABLES = {{4, 12}, {12, 12}, {20, 12}, {8, 16}, {16, 16}};
	static final int TABLE_B = 1;

	private KitchenDecor() {
	}

	public static void place(ServerLevel level, BlockPos origin) {
		removeEntities(level, origin);
		Decor d = new Decor(level, origin);
		d.kitchenLine();
		d.dining();
		d.sideboard();
	}

	/** Removes the decor item frames (call from teardown so they don't pop off as items). */
	public static void removeEntities(ServerLevel level, BlockPos origin) {
		AABB box = new AABB(origin.getX() - 1, origin.getY() - 1, origin.getZ() - 1,
				origin.getX() + SetBuilder.WIDTH + 1, origin.getY() + SetBuilder.CEILING + 1, origin.getZ() + SetBuilder.DEPTH + 1);
		List<Entity> doomed = new ArrayList<>();
		// getAllEntities, not an area query: frames spawned earlier in the same tick are not in the section index yet.
		for (Entity e : level.getAllEntities()) {
			if (e.entityTags().contains(TAG) && box.contains(e.position())) doomed.add(e);
		}
		doomed.forEach(Entity::discard);
	}

	private static final class Decor {
		private final ServerLevel level;
		private final BlockPos origin;

		Decor(ServerLevel level, BlockPos origin) {
			this.level = level;
			this.origin = origin;
		}

		// ---- kitchen line ----

		// Zones follow docs/crewkit/SET-ZONES.md: the boards need x 2..11 and 16..26 kept low, so everything tall
		// lives in the hood column (x 12..14), the west wall column (x=1) and the east pantry (x=26, z 2..3).

		void kitchenLine() {
			hood();
			counterTops();
			westWall();
			eastPantry();
		}

		/** Backsplash, under-light, stair hood and flue over the shell's stove (smoker/furnace/smoker at x 12..14, z=4). */
		void hood() {
			BlockState andesite = Blocks.POLISHED_ANDESITE.defaultBlockState();
			for (int x = 12; x <= 14; x++) {
				// Filler behind the backsplash, then the backsplash face at z=2 looking over the counter.
				set(x, 1, 1, Blocks.QUARTZ_BRICKS);
				set(x, 2, 1, Blocks.QUARTZ_BRICKS);
				set(x, 1, 2, Blocks.QUARTZ_BRICKS);
				set(x, 2, 2, x == 13 ? Blocks.CYAN_TERRACOTTA : Blocks.QUARTZ_BLOCK);
				// Light strip: a sea lantern recessed behind white stained glass, both full cubes, so no seams.
				set(x, 3, 1, Blocks.SEA_LANTERN);
				set(x, 3, 2, Blocks.WHITE_STAINED_GLASS);
				// Hood body, back to front.
				set(x, 4, 1, andesite);
				set(x, 4, 2, andesite);
				set(x, 4, 3, x == 13 ? Blocks.SEA_LANTERN.defaultBlockState() : andesite);
				set(x, 4, 4, stairs(Blocks.POLISHED_ANDESITE_STAIRS, Direction.NORTH, Half.TOP));
				set(x, 5, 1, andesite);
				set(x, 5, 2, andesite);
				set(x, 5, 3, andesite);
				set(x, 5, 4, stairs(Blocks.POLISHED_ANDESITE_STAIRS, Direction.NORTH, Half.BOTTOM));
				// Cooking clearance over the stove and the counter behind it.
				set(x, 2, 4, Blocks.AIR);
				set(x, 3, 3, Blocks.AIR);
				set(x, 3, 4, Blocks.AIR);
			}
			// Taper into a one-block flue that runs through the coffer at (13,7,3) into the ceiling panel.
			set(12, 6, 3, stairs(Blocks.POLISHED_ANDESITE_STAIRS, Direction.EAST, Half.BOTTOM));
			set(14, 6, 3, stairs(Blocks.POLISHED_ANDESITE_STAIRS, Direction.WEST, Half.BOTTOM));
			for (int y = 6; y <= 8; y++) set(13, y, 3, andesite);
			// Spice rack and a pot on the counter behind the stove (this column may go tall).
			set(12, 2, 3, Blocks.BREWING_STAND);
			pot(14, 2, 3, Direction.SOUTH);
			set(13, 2, 3, Blocks.AIR);
		}

		/** Counter tops x 2..11, z 3..4 at y=2: only flat things (sightline limit y=2.2). */
		void counterTops() {
			BlockState iron = trapdoor(Blocks.IRON_TRAPDOOR, Direction.SOUTH, Half.BOTTOM, false);
			for (int x = 2; x <= 11; x++) {
				for (int z = 3; z <= 4; z++) {
					boolean prep = x == 2 || x == 7 || x >= 8;
					set(x, 2, z, prep ? iron : Blocks.AIR.defaultBlockState());
				}
			}
			// Plated food on flat item frames between the two prep surfaces, right where the chef works.
			ItemStack[] food = {
					new ItemStack(Items.BREAD), new ItemStack(Items.COOKED_BEEF), new ItemStack(Items.BAKED_POTATO), new ItemStack(Items.PUMPKIN_PIE),
					new ItemStack(Items.APPLE), new ItemStack(Items.COOKED_SALMON), new ItemStack(Items.CARROT), new ItemStack(Items.COOKED_CHICKEN),
			};
			int i = 0;
			for (int z = 3; z <= 4; z++) {
				for (int x = 3; x <= 6; x++) frame(x, 2, z, Direction.UP, food[i++]);
			}
		}

		/** West wall column x=1, z 2..9 (the chef path is x=2, z 5..7, so nothing crosses x=1). */
		void westWall() {
			int x = 1;
			// Shelving at z=2: barrel base, two chiseled bookshelves of recipe books, barrel on top.
			barrel(x, 1, 2, Direction.UP);
			bookshelf(x, 2, 2, Direction.EAST, new int[] {0, 1, 3, 5});
			bookshelf(x, 3, 2, Direction.EAST, new int[] {0, 2, 3, 4});
			barrel(x, 4, 2, Direction.EAST);
			set(x, 5, 2, Blocks.AIR);
			// Sink at z=4 between two barrels: water cauldron with a tripwire-hook tap hung on the wall.
			barrel(x, 1, 3, Direction.UP);
			set(x, 1, 4, Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
			barrel(x, 1, 5, Direction.UP);
			set(x, 2, 3, Blocks.AIR);
			set(x, 2, 4, Blocks.TRIPWIRE_HOOK.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
					.setValue(BlockStateProperties.ATTACHED, false).setValue(BlockStateProperties.POWERED, false));
			set(x, 2, 5, Blocks.POTTED_FERN);
			// Over the sink: light strip (sea lantern set into the wall behind white stained glass), a shallow
			// spruce-trapdoor cupboard on top, capped with a slab so the cupboard never shows an open top.
			for (int z = 3; z <= 5; z++) {
				set(0, 3, z, Blocks.SEA_LANTERN);
				set(x, 3, z, Blocks.WHITE_STAINED_GLASS);
				set(x, 4, z, trapdoor(Blocks.SPRUCE_TRAPDOOR, Direction.WEST, Half.BOTTOM, true));
				set(x, 5, z, slab(Blocks.SPRUCE_SLAB, SlabType.BOTTOM));
			}
			// Dry store toward the pass.
			barrel(x, 1, 6, Direction.UP);
			pot(x, 2, 6, Direction.EAST);
			barrel(x, 1, 7, Direction.EAST);
			barrel(x, 2, 7, Direction.EAST);
			pot(x, 1, 9, Direction.EAST);
		}

		/** East pantry x=26, z 2..3 (up to y=3) and back-of-house x 16..25, z 2..4 (y=1 only). */
		void eastPantry() {
			barrel(26, 1, 2, Direction.UP);
			barrel(26, 2, 2, Direction.UP);
			pot(26, 3, 2, Direction.WEST);
			barrel(26, 1, 3, Direction.WEST);
			set(26, 2, 3, Blocks.HAY_BLOCK);
			barrel(20, 1, 2, Direction.UP);
			barrel(21, 1, 2, Direction.UP);
			set(24, 1, 2, Blocks.HAY_BLOCK);
		}

		// ---- dining room ----

		void dining() {
			for (int t = 0; t < TABLES.length; t++) {
				int x0 = TABLES[t][0];
				int z0 = TABLES[t][1];
				int y = tableItemY(x0 + 1, z0);
				if (y < 0) continue;
				// Seats sit at the two end columns, so plates land on x0 and x0+3; the middle columns stay free.
				candle(x0 + 1, y, z0 + 1, Blocks.WHITE_CANDLE, 1);
				set(x0 + 2, y, z0, Blocks.POTTED_FLOWERING_AZALEA);
				if (t == TABLE_B) set(x0 + 2, y, z0 + 1, Blocks.CANDLE_CAKE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
			}
		}

		/** Y of the block a prop should occupy on a table top: the cloth layer if there is one, else the air above. */
		int tableItemY(int x, int z) {
			for (int y = 1; y <= 3; y++) {
				BlockState state = get(x, y, z);
				if (state.is(net.minecraft.tags.BlockTags.WOOL_CARPETS) || isTableProp(state)) return y;
				if (state.isAir() && !get(x, y - 1, z).isAir()) return y;
			}
			return -1;
		}

		static boolean isTableProp(BlockState state) {
			return state.is(Blocks.WHITE_CANDLE) || state.is(Blocks.POTTED_FLOWERING_AZALEA) || state.is(Blocks.CANDLE_CAKE);
		}

		void sideboard() {
			// West wall x=1, z 18..20, y 1..2 (between the pillars at z=17 and z=21; the banner above is at y=6).
			// Every top face is at y=2.0 (full blocks and a top slab), so the props on y=2 sit flush.
			int x = 1;
			barrel(x, 1, 18, Direction.UP);
			set(x, 1, 19, Blocks.BOOKSHELF);
			set(x, 1, 20, slab(Blocks.SPRUCE_SLAB, SlabType.TOP));
			pot(x, 2, 18, Direction.EAST);
			candle(x, 2, 19, Blocks.WHITE_CANDLE, 3);
			pot(x, 2, 20, Direction.EAST);
		}

		// ---- helpers ----

		void set(int x, int y, int z, BlockState state) {
			level.setBlock(origin.offset(x, y, z), state, FLAGS);
		}

		void set(int x, int y, int z, Block block) {
			set(x, y, z, block.defaultBlockState());
		}

		BlockState get(int x, int y, int z) {
			return level.getBlockState(origin.offset(x, y, z));
		}

		void frame(int x, int y, int z, Direction facing, ItemStack item) {
			ItemFrame frame = new ItemFrame(level, origin.offset(x, y, z), facing);
			frame.setItem(item, false);
			frame.addTag(TAG);
			frame.setInvulnerable(true);
			level.addFreshEntity(frame);
		}

		void barrel(int x, int y, int z, Direction facing) {
			set(x, y, z, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, facing)
					.setValue(BlockStateProperties.OPEN, false));
		}

		void pot(int x, int y, int z, Direction facing) {
			set(x, y, z, Blocks.DECORATED_POT.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
					.setValue(BlockStateProperties.WATERLOGGED, false));
		}

		void candle(int x, int y, int z, Block candle, int count) {
			set(x, y, z, candle.defaultBlockState().setValue(BlockStateProperties.CANDLES, count)
					.setValue(BlockStateProperties.LIT, true).setValue(BlockStateProperties.WATERLOGGED, false));
		}

		void bookshelf(int x, int y, int z, Direction facing, int[] slots) {
			BlockState state = Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
			for (int slot : slots) state = state.setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(slot), true);
			set(x, y, z, state);
			BlockEntity be = level.getBlockEntity(origin.offset(x, y, z));
			if (be instanceof Container shelf) {
				shelf.clearContent();
				ItemStack[] books = {new ItemStack(Items.BOOK), new ItemStack(Items.WRITABLE_BOOK), new ItemStack(Items.ENCHANTED_BOOK)};
				int i = 0;
				for (int slot : slots) shelf.setItem(slot, books[i++ % books.length]);
			}
			// setItem re-syncs the state from the inventory; write it once more so the slots always match.
			set(x, y, z, state);
		}

		static BlockState stairs(Block block, Direction facing, Half half) {
			return block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
					.setValue(BlockStateProperties.HALF, half).setValue(BlockStateProperties.WATERLOGGED, false);
		}

		static BlockState trapdoor(Block block, Direction facing, Half half, boolean open) {
			return block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
					.setValue(BlockStateProperties.HALF, half).setValue(BlockStateProperties.OPEN, open)
					.setValue(BlockStateProperties.POWERED, false).setValue(BlockStateProperties.WATERLOGGED, false);
		}

		static BlockState slab(Block block, SlabType type) {
			return block.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, type)
					.setValue(BlockStateProperties.WATERLOGGED, false);
		}
	}
}
