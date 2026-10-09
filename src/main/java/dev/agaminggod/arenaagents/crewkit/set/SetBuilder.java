package dev.agaminggod.arenaagents.crewkit.set;

import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * Places the CrewKit kitchen set procedurally at {@link CrewkitAnchors#origin}, from docs/crewkit/kitchen-layout.html.
 * Relative coords: x east 0..27, z south 0..21, floor at y=0, ceiling at y=7. The south face is open for the camera.
 */
public final class SetBuilder {
	public static final int WIDTH = 28;
	public static final int DEPTH = 22;
	public static final int CEILING = 7;
	/** Foundation layer under the floor; part of the snapshot volume. */
	static final int MIN_Y = -1;
	/** Top surface of the table cloths (carpet on a top slab), relative to origin y. Plates sit here. */
	public static final double TABLE_TOP_Y = 2.0625;
	/** Entity tag on every marker this track spawns. Deliberately not "crewkit" so /crewkit reset keeps the anchors. */
	public static final String SET_TAG = "ck_set";

	// No neighbour/shape updates: we set every state explicitly (doors, lanterns, chains), and no drops or container spills.
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

	private static final Object[][] MARKERS = {
			{"ck_budget", CrewkitAnchors.BUDGET},
			{"ck_ledger", CrewkitAnchors.LEDGER},
			{"ck_agent", CrewkitAnchors.AGENT},
			{"ck_screen", CrewkitAnchors.SCREEN},
			{"ck_crate", CrewkitAnchors.CRATE},
			{"ck_player", CrewkitAnchors.PLAYER},
	};

	private SetBuilder() {
	}

	/** Builds (or rebuilds) the kitchen at {@code origin} in {@code level}, moving it if it was built elsewhere. */
	public static int build(ServerLevel level, BlockPos origin) {
		MinecraftServer server = level.getServer();
		SetSavedData data = SetSavedData.get(server);
		String dimension = level.dimension().identifier().toString();
		boolean sameSpot = origin.equals(data.origin) && dimension.equals(data.dimension);
		if (data.built && !sameSpot) teardown(server);

		List<BlockState> palette = data.palette;
		int[] snapshot = data.snapshot;
		if (!sameSpot || !data.hasSnapshot()) {
			Map<BlockState, Integer> index = new HashMap<>();
			List<BlockState> states = new ArrayList<>();
			int[] packed = new int[WIDTH * DEPTH * (CEILING - MIN_Y + 1)];
			forVolume((x, y, z, i) -> packed[i] = index.computeIfAbsent(level.getBlockState(origin.offset(x, y, z)), state -> {
				states.add(state);
				return states.size() - 1;
			}));
			palette = states;
			snapshot = packed;
		}

		CrewkitAnchors.origin = origin.immutable();
		data.update(origin.immutable(), dimension, true, palette, snapshot);
		int placed = new Placer(level, origin).placeAll();
		spawnMarkers(level);
		return placed;
	}

	/** Removes the set: restores the terrain snapshot (or air if none) and kills the anchor markers. */
	public static boolean teardown(MinecraftServer server) {
		SetSavedData data = SetSavedData.get(server);
		if (data.origin == null) return false;
		ServerLevel level = levelOf(server, data.dimension);
		BlockPos origin = data.origin;
		killMarkers(level);
		boolean restore = data.hasSnapshot();
		List<BlockState> palette = data.palette;
		int[] snapshot = data.snapshot;
		// clear top-down first so hanging/attached blocks never pop
		for (int y = CEILING; y >= MIN_Y; y--) {
			for (int z = 0; z < DEPTH; z++) {
				for (int x = 0; x < WIDTH; x++) {
					BlockState state = restore ? palette.get(snapshot[index(x, y, z)]) : (y < 0 ? null : Blocks.AIR.defaultBlockState());
					if (state != null) level.setBlock(origin.offset(x, y, z), state, FLAGS);
				}
			}
		}
		data.update(origin, data.dimension, false, List.of(), new int[0]);
		return true;
	}

	/** Hook for the core /crewkit reset. Static blocks stay; re-close the delivery door and make sure anchors exist. */
	public static void resetDynamic(MinecraftServer server) {
		SetSavedData data = SetSavedData.get(server);
		if (!data.built || data.origin == null) return;
		ServerLevel level = levelOf(server, data.dimension);
		CrewkitAnchors.origin = data.origin;
		new Placer(level, data.origin).deliveryDoor(false);
		if (countMarkers(level) < MARKERS.length) spawnMarkers(level);
	}

	public static boolean isBuilt(MinecraftServer server) {
		return SetSavedData.get(server).built;
	}

	/** Called on server start: publish the persisted origin to CrewkitAnchors. */
	public static void loadOrigin(MinecraftServer server) {
		SetSavedData.get(server).origin().ifPresent(pos -> CrewkitAnchors.origin = pos);
	}

	static ServerLevel levelOf(MinecraftServer server, String dimension) {
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
		return level != null ? level : server.overworld();
	}

	// ---- markers ----

	private static void spawnMarkers(ServerLevel level) {
		killMarkers(level);
		for (Object[] row : MARKERS) {
			String tag = (String) row[0];
			Vec3 pos = CrewkitAnchors.at((int[]) row[1]);
			Marker marker = EntityType.MARKER.create(level, EntitySpawnReason.COMMAND);
			if (marker == null) continue;
			// ck_player is the demo camera: face north, 25 degrees down. Others face south (into the room).
			boolean camera = tag.equals("ck_player");
			marker.snapTo(pos.x, pos.y, pos.z, camera ? 180.0F : 0.0F, camera ? 25.0F : 0.0F);
			marker.addTag(SET_TAG);
			marker.addTag(tag);
			level.addFreshEntity(marker);
		}
	}

	private static void killMarkers(ServerLevel level) {
		List<Entity> doomed = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity.entityTags().contains(SET_TAG)) doomed.add(entity);
		}
		doomed.forEach(Entity::discard);
	}

	private static int countMarkers(ServerLevel level) {
		int count = 0;
		for (Entity entity : level.getAllEntities()) {
			if (entity.entityTags().contains(SET_TAG)) count++;
		}
		return count;
	}

	// ---- volume helpers ----

	private interface VolumeVisitor {
		void visit(int x, int y, int z, int index);
	}

	private static void forVolume(VolumeVisitor visitor) {
		for (int y = MIN_Y; y <= CEILING; y++) {
			for (int z = 0; z < DEPTH; z++) {
				for (int x = 0; x < WIDTH; x++) {
					visitor.visit(x, y, z, index(x, y, z));
				}
			}
		}
	}

	private static int index(int x, int y, int z) {
		return ((y - MIN_Y) * DEPTH + z) * WIDTH + x;
	}

	// ---- the set itself ----

	private static final class Placer {
		private final Level level;
		private final BlockPos origin;
		private int placed;

		Placer(Level level, BlockPos origin) {
			this.level = level;
			this.origin = origin;
		}

		void set(int x, int y, int z, BlockState state) {
			level.setBlock(origin.offset(x, y, z), state, FLAGS);
			placed++;
		}

		void set(int x, int y, int z, Block block) {
			set(x, y, z, block.defaultBlockState());
		}

		void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
			for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) set(x, y, z, state);
		}

		void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
			fill(x0, y0, z0, x1, y1, z1, block.defaultBlockState());
		}

		boolean isAir(int x, int y, int z) {
			return level.getBlockState(origin.offset(x, y, z)).isAir();
		}

		int placeAll() {
			shell();
			boards();
			counterLine();
			pass();
			deliveryDoor(false);
			tables();
			lanterns();
			decor();
			hiddenLights();
			return placed;
		}

		private void shell() {
			fill(0, 1, 0, WIDTH - 1, CEILING - 1, DEPTH - 1, Blocks.AIR);
			fill(0, MIN_Y, 0, WIDTH - 1, MIN_Y, DEPTH - 1, Blocks.SMOOTH_STONE);
			// Floor: white border, checkered tiles in the kitchen, polished deepslate in the dining room.
			for (int z = 0; z < DEPTH; z++) {
				for (int x = 0; x < WIDTH; x++) {
					Block floor;
					if (x <= 1 || x >= WIDTH - 2 || z <= 1 || z >= DEPTH - 2) floor = Blocks.WHITE_CONCRETE;
					else if (z <= 9) floor = ((x + z) & 1) == 0 ? Blocks.DEEPSLATE_TILES : Blocks.POLISHED_DEEPSLATE;
					else floor = Blocks.POLISHED_DEEPSLATE;
					set(x, 0, z, floor);
				}
			}
			// Line between kitchen and dining room.
			fill(2, 0, 10, WIDTH - 3, 0, 10, Blocks.SMOOTH_QUARTZ);
			// Walls (back + sides), ceiling. South is open.
			fill(0, 1, 0, WIDTH - 1, CEILING - 1, 0, Blocks.WHITE_CONCRETE);
			fill(0, 1, 0, 0, CEILING - 1, DEPTH - 1, Blocks.WHITE_CONCRETE);
			fill(WIDTH - 1, 1, 0, WIDTH - 1, CEILING - 1, DEPTH - 1, Blocks.WHITE_CONCRETE);
			fill(0, CEILING, 0, WIDTH - 1, CEILING, DEPTH - 1, Blocks.WHITE_CONCRETE);
			// Quartz pillars standing proud of the walls; back ones frame the two boards.
			int[][] pillars = {{1, 1}, {11, 1}, {15, 1}, {26, 1}, {1, 11}, {26, 11}, {1, 21}, {26, 21}};
			for (int[] p : pillars) fill(p[0], 1, p[1], p[0], CEILING - 1, p[1], Blocks.QUARTZ_PILLAR);
			// Crown moulding: upside-down quartz stairs along the top of the walls.
			for (int x = 2; x <= WIDTH - 3; x++) {
				if (x == 11 || x == 15) continue;
				set(x, CEILING - 1, 1, stairs(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.NORTH, Half.TOP));
			}
			for (int z = 2; z <= DEPTH - 1; z++) {
				if (z == 11 || z == 21) continue;
				set(1, CEILING - 1, z, stairs(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.WEST, Half.TOP));
				set(WIDTH - 2, CEILING - 1, z, stairs(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.EAST, Half.TOP));
			}
		}

		private void boards() {
			// Budget board (ck_budget 6,4,0): blue panel over a lapis ledge, x 2..10.
			fill(2, 2, 0, 10, 2, 0, Blocks.LAPIS_BLOCK);
			fill(2, 3, 0, 10, 5, 0, Blocks.BLUE_CONCRETE);
			// Bill board (ck_ledger 21,4,0): black panel x 16..25, 5 rows tall.
			fill(16, 1, 0, 25, 5, 0, Blocks.BLACK_CONCRETE);
		}

		private void counterLine() {
			// Back row z=3: smooth stone, barrel and crafting table at the ends.
			fill(2, 1, 3, 13, 1, 3, Blocks.SMOOTH_STONE);
			set(2, 1, 3, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
			set(13, 1, 3, Blocks.CRAFTING_TABLE);
			// Front row z=4: polished andesite worktop with the stove (furnace) between two smokers, lit, facing the room.
			fill(2, 1, 4, 13, 1, 4, Blocks.POLISHED_ANDESITE);
			set(5, 1, 4, lit(Blocks.SMOKER));
			set(6, 1, 4, lit(Blocks.FURNACE));
			set(7, 1, 4, lit(Blocks.SMOKER));
			// Props on the back row.
			set(3, 2, 3, Blocks.BREWING_STAND);
			set(6, 2, 3, Blocks.CAULDRON);
			set(9, 2, 3, Blocks.POTTED_FERN);
			set(12, 2, 3, Blocks.DECORATED_POT);
			set(13, 2, 4, Blocks.POTTED_RED_TULIP);
		}

		private void pass() {
			// The pass (ck_screen 9,2,8): birch counter with a ticket rail (chain between two posts).
			fill(2, 1, 8, 15, 1, 8, Blocks.BIRCH_PLANKS);
			fill(2, 2, 8, 2, 3, 8, Blocks.SPRUCE_FENCE);
			fill(15, 2, 8, 15, 3, 8, Blocks.SPRUCE_FENCE);
			for (int x = 3; x <= 14; x++) {
				set(x, 3, 8, Blocks.IRON_CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			}
		}

		void deliveryDoor(boolean open) {
			// East wall, z 4..7: orange frame, spruce double door at z=5,6 (2 tall).
			fill(WIDTH - 1, 1, 4, WIDTH - 1, 3, 4, Blocks.ORANGE_TERRACOTTA);
			fill(WIDTH - 1, 1, 7, WIDTH - 1, 3, 7, Blocks.ORANGE_TERRACOTTA);
			fill(WIDTH - 1, 3, 5, WIDTH - 1, 3, 6, Blocks.ORANGE_TERRACOTTA);
			door(WIDTH - 1, 5, DoorHingeSide.LEFT, open);
			door(WIDTH - 1, 6, DoorHingeSide.RIGHT, open);
		}

		private void door(int x, int z, DoorHingeSide hinge, boolean open) {
			BlockState base = Blocks.SPRUCE_DOOR.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
					.setValue(BlockStateProperties.DOOR_HINGE, hinge)
					.setValue(BlockStateProperties.OPEN, open);
			set(x, 1, z, base.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
			set(x, 2, z, base.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
		}

		private void tables() {
			// Tables A, B, C (4x2) and D, E spares: dark oak top slab with a white cloth.
			int[][] tables = {{4, 12}, {12, 12}, {20, 12}, {8, 16}, {16, 16}};
			for (int[] t : tables) {
				fill(t[0], 1, t[1], t[0] + 3, 1, t[1] + 1,
						Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
				fill(t[0], 2, t[1], t[0] + 3, 2, t[1] + 1, Blocks.WHITE_CARPET);
			}
			// Seats from CrewkitAnchors.SEATS. facing 0 = sitter faces south, so the stair back points north.
			for (double[] seat : CrewkitAnchors.SEATS) {
				int x = (int) Math.floor(seat[0]);
				int z = (int) Math.floor(seat[1]);
				Direction back = seat[2] == 0 ? Direction.NORTH : Direction.SOUTH;
				set(x, 1, z, stairs(Blocks.SPRUCE_STAIRS, back, Half.BOTTOM));
			}
		}

		private void lanterns() {
			// Kept out of the camera's sight lines to the two boards: along the side walls and over B, D, E.
			int[][] chained = {{2, 6}, {2, 12}, {2, 18}, {25, 9}, {25, 13}, {25, 18}, {10, 17}, {18, 17}};
			for (int[] l : chained) {
				set(l[0], CEILING - 1, l[1], Blocks.IRON_CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
				set(l[0], CEILING - 2, l[1], hangingLantern());
			}
			set(13, CEILING - 1, 13, hangingLantern());
			set(14, CEILING - 1, 13, hangingLantern());
		}

		private void decor() {
			// Delivery corner: barrels stacked by the door, bag drop at ck_crate (25,1,6) stays clear.
			BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
			set(26, 1, 8, barrel);
			set(26, 2, 8, barrel);
			set(26, 1, 9, barrel);
			set(26, 1, 3, Blocks.HAY_BLOCK);
			set(26, 1, 2, barrel);
			// Plants at the corners of the dining room.
			set(2, 1, 10, Blocks.FLOWERING_AZALEA);
			set(25, 1, 11, Blocks.FLOWERING_AZALEA);
			set(2, 1, 20, Blocks.AZALEA);
			set(25, 1, 20, Blocks.AZALEA);
			// Pantry corner behind the counter.
			set(14, 1, 2, barrel);
			set(14, 1, 3, Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
			set(1, 1, 2, Blocks.COMPOSTER);
		}

		private void hiddenLights() {
			// Invisible light blocks: a ceiling grid plus a lower grid so faces get even light from every side.
			BlockState light = Blocks.LIGHT.defaultBlockState().setValue(BlockStateProperties.LEVEL, 15);
			for (int z = 2; z <= DEPTH - 1; z += 3) {
				for (int x = 2; x <= WIDTH - 3; x += 3) {
					if (isAir(x, CEILING - 1, z)) set(x, CEILING - 1, z, light);
					if (z >= 5 && isAir(x, 3, z)) set(x, 3, z, light);
				}
			}
		}

		private static BlockState stairs(Block block, Direction facing, Half half) {
			return block.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
					.setValue(BlockStateProperties.HALF, half);
		}

		private static BlockState lit(Block block) {
			return block.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
					.setValue(BlockStateProperties.LIT, true);
		}

		private static BlockState hangingLantern() {
			return Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true);
		}
	}
}
