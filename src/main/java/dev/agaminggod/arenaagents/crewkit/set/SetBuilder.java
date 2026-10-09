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
 * Places the CrewKit kitchen shell procedurally at {@link CrewkitAnchors#origin}; zones in docs/crewkit/SET-ZONES.md.
 * Relative coords: x east 0..27, z south 0..21, floor at y=0, beams at y=7, ceiling panels at y=8. Closed on all four
 * sides; the south wall (z=21) is the front facade with the entrance, and the camera sits inside at z=19.
 * After the shell, {@link KitchenDecor} and {@link Exterior} dress it.
 */
public final class SetBuilder {
	public static final int WIDTH = 28;
	public static final int DEPTH = 22;
	/** Beam level; the coffered ceiling panels sit one above at {@link #ROOF}. */
	public static final int CEILING = 7;
	public static final int ROOF = 8;
	/** Foundation layer under the floor. */
	static final int MIN_Y = -1;
	/** Teardown snapshot box (relative): the footprint plus 6 blocks east, west and north (exterior) and 4 south (entrance path). */
	static final int[] VOLUME = {-6, MIN_Y, -6, WIDTH - 1 + 6, 14, DEPTH - 1 + 4};
	/** Top surface of the table cloths (carpet on a top slab), relative to origin y. Plates sit here. */
	public static final double TABLE_TOP_Y = 2.0625;
	/** Entity tag on every marker this track spawns. Deliberately not "crewkit" so /crewkit reset keeps the anchors. */
	public static final String SET_TAG = "ck_set";

	// No neighbour/shape updates: we set every state explicitly (doors, panes, trapdoors), and no drops or container spills.
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
			| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;

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
		int[] bounds = data.bounds;
		// Re-snapshot on a new spot, or when an older save used a smaller box (rebuilding in place would otherwise lose the margin).
		if (!sameSpot || !data.hasSnapshot() || !java.util.Arrays.equals(bounds, VOLUME)) {
			if (sameSpot && data.built && data.hasSnapshot()) restore(level, origin, data);
			Map<BlockState, Integer> index = new HashMap<>();
			List<BlockState> states = new ArrayList<>();
			int[] packed = new int[volumeSize(VOLUME)];
			forVolume(VOLUME, (x, y, z, i) -> packed[i] = index.computeIfAbsent(level.getBlockState(origin.offset(x, y, z)), state -> {
				states.add(state);
				return states.size() - 1;
			}));
			palette = states;
			snapshot = packed;
			bounds = VOLUME;
		}

		CrewkitAnchors.origin = origin.immutable();
		builtInThisWorld = true;
		data.update(origin.immutable(), dimension, true, palette, snapshot, bounds);
		int placed = new Placer(level, origin).placeAll();
		KitchenDecor.place(level, origin.immutable());
		Exterior.place(level, origin.immutable());
		// KitchenDecor puts sea lanterns in the west wall at (0,3,3..5); Exterior clears x=-1, so seal their backs.
		for (int z = 3; z <= 5; z++) level.setBlock(origin.offset(-1, 3, z), Blocks.WHITE_CONCRETE.defaultBlockState(), FLAGS);
		data.nextGeneration();
		// Anchors are computed from CrewkitAnchors; no marker entities (they showed as "Unnamed Marker" gizmos
		// in editor mods). Clear any left by older builds.
		killMarkers(level);
		return placed;
	}

	/** Removes the set: restores the terrain snapshot (or air over the footprint if none) and kills the anchor markers. */
	public static boolean teardown(MinecraftServer server) {
		SetSavedData data = SetSavedData.get(server);
		if (data.origin == null) return false;
		ServerLevel level = levelOf(server, data.dimension);
		BlockPos origin = data.origin;
		killMarkers(level);
		KitchenDecor.removeEntities(level, origin); // item frames would otherwise drop as items
		if (data.hasSnapshot()) {
			restore(level, origin, data);
		} else {
			for (int y = ROOF; y >= 0; y--) {
				for (int z = 0; z < DEPTH; z++) {
					for (int x = 0; x < WIDTH; x++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), FLAGS);
				}
			}
		}
		data.update(origin, data.dimension, false, List.of(), new int[0], VOLUME);
		data.nextGeneration(); // markers left in unloaded chunks become stale
		return true;
	}

	private static void restore(ServerLevel level, BlockPos origin, SetSavedData data) {
		int[] b = data.bounds;
		if (data.snapshot.length != volumeSize(b)) return;
		List<BlockState> palette = data.palette;
		int[] snapshot = data.snapshot;
		// top-down so hanging/attached blocks never pop
		for (int y = b[4]; y >= b[1]; y--) {
			for (int z = b[2]; z <= b[5]; z++) {
				for (int x = b[0]; x <= b[3]; x++) {
					level.setBlock(origin.offset(x, y, z), palette.get(snapshot[index(b, x, y, z)]), FLAGS);
				}
			}
		}
	}

	/** Hook for the core /crewkit reset. Static blocks stay; re-close the delivery door and make sure anchors exist. */
	public static void resetDynamic(MinecraftServer server) {
		SetSavedData data = SetSavedData.get(server);
		if (!data.built || data.origin == null) return;
		ServerLevel level = levelOf(server, data.dimension);
		CrewkitAnchors.origin = data.origin;
		new Placer(level, data.origin).doors(false);
		killMarkers(level);
	}

	public static boolean isBuilt(MinecraftServer server) {
		return SetSavedData.get(server).built;
	}

	/** Called on server start: publish this world's persisted origin, forgetting any from a previously opened world. */
	public static void loadOrigin(MinecraftServer server) {
		java.util.Optional<BlockPos> saved = SetSavedData.get(server).origin();
		builtInThisWorld = saved.isPresent();
		CrewkitAnchors.origin = saved.orElse(new BlockPos(0, 100, 0));
	}

	/** True once this world has a kitchen, so the chef is never placed at a stale origin. */
	public static volatile boolean builtInThisWorld;

	static ServerLevel levelOf(MinecraftServer server, String dimension) {
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
		return level != null ? level : server.overworld();
	}

	// ---- markers ----

	/** Entity load hook: drop anchor markers saved by older builds. The set no longer spawns any. */
	static void discardIfStale(Entity entity, ServerLevel level) {
		if (entity.entityTags().contains(SET_TAG)) level.getServer().execute(entity::discard);
	}

	private static void killMarkers(ServerLevel level) {
		List<Entity> doomed = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			if (entity.entityTags().contains(SET_TAG)) doomed.add(entity);
		}
		doomed.forEach(Entity::discard);
	}

	// ---- volume helpers ----

	private interface VolumeVisitor {
		void visit(int x, int y, int z, int index);
	}

	private static void forVolume(int[] b, VolumeVisitor visitor) {
		for (int y = b[1]; y <= b[4]; y++) {
			for (int z = b[2]; z <= b[5]; z++) {
				for (int x = b[0]; x <= b[3]; x++) {
					visitor.visit(x, y, z, index(b, x, y, z));
				}
			}
		}
	}

	private static int volumeSize(int[] b) {
		return (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
	}

	private static int index(int[] b, int x, int y, int z) {
		int w = b[3] - b[0] + 1;
		int d = b[5] - b[2] + 1;
		return ((y - b[1]) * d + (z - b[2])) * w + (x - b[0]);
	}

	// ---- the shell ----

	private static final class Placer {
		// Back-wall pillars frame the boards; side pillars bracket the window bays.
		private static final int[][] PILLARS = {{1, 1}, {11, 1}, {15, 1}, {1, 11}, {1, 17}, {26, 11}, {26, 17}, {11, 20}, {16, 20}};
		private static final int[][] FRONT_WINDOWS = {{4, 8}, {19, 23}};
		private static final int[] BEAMS_Z = {1, 5, 9, 13, 17, 21};
		private static final int[] BEAMS_X = {1, 5, 9, 13, 14, 18, 22, 26};
		private static final int[] COFFER_X = {3, 7, 11, 16, 20, 24};
		private static final int[] COFFER_Z = {3, 7, 11, 15, 19};
		private static final int[][] TABLES = {{4, 12}, {12, 12}, {20, 12}, {8, 16}, {16, 16}};

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

		int placeAll() {
			shell();
			ceiling();
			pillars();
			boards();
			windowsAndWainscot();
			frontWall();
			counterBase();
			pass();
			deliveryDoor();
			tables();
			fillLights();
			return placed;
		}

		private void shell() {
			fill(0, 1, 0, WIDTH - 1, ROOF, DEPTH - 1, Blocks.AIR);
			fill(0, MIN_Y, 0, WIDTH - 1, MIN_Y, DEPTH - 1, Blocks.SMOOTH_STONE);
			// Floor: quartz border; light polished tuff in the kitchen, glossy polished blackstone in the dining room.
			for (int z = 0; z < DEPTH; z++) {
				for (int x = 0; x < WIDTH; x++) {
					Block floor;
					if (x <= 1 || x >= WIDTH - 2 || z <= 1 || z >= DEPTH - 2 || z == 10) floor = Blocks.SMOOTH_QUARTZ;
					else if (z <= 9) floor = Blocks.POLISHED_TUFF;
					else floor = Blocks.POLISHED_BLACKSTONE;
					set(x, 0, z, floor);
				}
			}
			// Blue runner down the centre aisle toward the camera.
			fill(13, 1, 15, 14, 1, DEPTH - 2, Blocks.BLUE_CARPET);
			// Walls (back + sides) up to the roof so the coffers are sealed; south is open.
			fill(0, 1, 0, WIDTH - 1, ROOF, 0, Blocks.WHITE_CONCRETE);
			fill(0, 1, 0, 0, ROOF, DEPTH - 1, Blocks.WHITE_CONCRETE);
			fill(WIDTH - 1, 1, 0, WIDTH - 1, ROOF, DEPTH - 1, Blocks.WHITE_CONCRETE);
			// Front corner plants (plain azalea, no magenta), just inside the front wall.
			set(2, 1, DEPTH - 2, Blocks.AZALEA);
			set(WIDTH - 3, 1, DEPTH - 2, Blocks.AZALEA);
		}

		private void ceiling() {
			// Coffered ceiling: white terracotta panels at y=8, stripped spruce beams at y=7, a lit copper bulb per coffer.
			fill(1, ROOF, 1, WIDTH - 2, ROOF, DEPTH - 1, Blocks.WHITE_TERRACOTTA);
			BlockState alongX = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
			BlockState alongZ = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
			for (int z : BEAMS_Z) fill(1, CEILING, z, WIDTH - 2, CEILING, z, alongX);
			for (int x : BEAMS_X) {
				for (int z = 1; z < DEPTH; z++) {
					boolean crossing = false;
					for (int bz : BEAMS_Z) crossing |= bz == z;
					if (!crossing) set(x, CEILING, z, alongZ);
				}
			}
			BlockState bulb = Blocks.WAXED_COPPER_BULB.defaultBlockState().setValue(BlockStateProperties.LIT, true);
			for (int x : COFFER_X) for (int z : COFFER_Z) set(x, ROOF, z, bulb);
		}

		private void pillars() {
			for (int[] p : PILLARS) {
				set(p[0], 1, p[1], Blocks.CHISELED_QUARTZ_BLOCK);
				fill(p[0], 2, p[1], p[0], 5, p[1], Blocks.QUARTZ_PILLAR);
				set(p[0], 6, p[1], Blocks.CHISELED_QUARTZ_BLOCK);
			}
			// Crown moulding on the side walls (none on the back wall: it would cover the board displays).
			for (int z = 2; z <= DEPTH - 2; z++) {
				if (z == 11 || z == 17 || z == 19) continue; // pillars and banners
				set(1, 6, z, stairs(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.WEST, Half.TOP));
				set(WIDTH - 2, 6, z, stairs(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.EAST, Half.TOP));
			}
			// Brand-blue banners above the sideboard and the east wainscot.
			set(1, 6, 19, Blocks.BLUE_WALL_BANNER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
			set(WIDTH - 2, 6, 19, Blocks.BLUE_WALL_BANNER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
		}

		private void boards() {
			// BoardsFeature draws display panels at z=1.05 over budget x 2..11 / y 1.8..6.7 and bill x 16.5..26.5 / y 1.6..6.7.
			// Backings sit in the wall; frames stay outside those rectangles (logs in the wall plane, thin lips at z=1).
			BlockState logX = Blocks.DARK_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
			fill(2, 2, 0, 10, 6, 0, Blocks.BLUE_CONCRETE);
			fill(2, 1, 0, 10, 1, 0, logX);
			fill(2, 7, 0, 10, 7, 0, logX);
			fill(16, 2, 0, 25, 6, 0, Blocks.BLACK_CONCRETE);
			fill(16, 1, 0, 25, 1, 0, logX);
			fill(16, 7, 0, 25, 7, 0, logX);
			fill(26, 1, 0, 26, 7, 0, Blocks.DARK_OAK_LOG);
			BlockState sill = Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
			BlockState lip = Blocks.DARK_OAK_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF, Half.TOP);
			fill(2, 1, 1, 10, 1, 1, sill);
			fill(16, 1, 1, 25, 1, 1, sill);
			fill(2, 6, 1, 10, 6, 1, lip);
			fill(16, 6, 1, 25, 6, 1, lip);
		}

		private void windowsAndWainscot() {
			BlockState pane = Blocks.GLASS_PANE.defaultBlockState()
					.setValue(BlockStateProperties.NORTH, true)
					.setValue(BlockStateProperties.SOUTH, true);
			BlockState header = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
			for (int x : new int[] {0, WIDTH - 1}) {
				for (int z = 12; z <= 16; z++) {
					fill(x, 3, z, x, 5, z, z == 14 ? Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState() : pane);
					set(x, 6, z, header);
				}
			}
			BlockState cap = Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF, Half.BOTTOM);
			int[][] runs = {{1, 12, 16}, {WIDTH - 2, 12, 16}, {WIDTH - 2, 18, 19}};
			for (int[] r : runs) {
				fill(r[0], 1, r[1], r[0], 2, r[2], Blocks.STRIPPED_SPRUCE_WOOD);
				fill(r[0], 3, r[1], r[0], 3, r[2], cap);
			}
		}

		/** South wall z=21: entrance double door at x 13..14, two window bays, wainscot, moulding; brick facade and path outside. */
		private void frontWall() {
			int z = DEPTH - 1;
			fill(0, 1, z, WIDTH - 1, ROOF, z, Blocks.WHITE_CONCRETE);
			fill(0, 0, z, WIDTH - 1, 0, z, Blocks.SMOOTH_QUARTZ);
			BlockState pane = Blocks.GLASS_PANE.defaultBlockState()
					.setValue(BlockStateProperties.EAST, true)
					.setValue(BlockStateProperties.WEST, true);
			BlockState header = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
			for (int[] w : FRONT_WINDOWS) {
				int mullion = (w[0] + w[1]) / 2;
				for (int x = w[0]; x <= w[1]; x++) {
					fill(x, 3, z, x, 5, z, x == mullion ? Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState() : pane);
					set(x, 6, z, header);
				}
			}
			// Entrance: stripped dark oak frame and lintel, spruce double door, blue runner leads to it.
			fill(12, 1, z, 12, 3, z, Blocks.STRIPPED_DARK_OAK_LOG);
			fill(15, 1, z, 15, 3, z, Blocks.STRIPPED_DARK_OAK_LOG);
			fill(13, 3, z, 14, 3, z, Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X));
			fill(13, 0, z, 14, 0, z, Blocks.STONE_BRICKS);
			frontDoor(13, DoorHingeSide.LEFT);
			frontDoor(14, DoorHingeSide.RIGHT);
			// Interior face (z=20): wainscot either side of the entrance, crown moulding along the top.
			BlockState cap = Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(BlockStateProperties.HALF, Half.BOTTOM);
			for (int[] run : new int[][] {{3, 10}, {17, 24}}) {
				fill(run[0], 1, z - 1, run[1], 2, z - 1, Blocks.STRIPPED_SPRUCE_WOOD);
				fill(run[0], 3, z - 1, run[1], 3, z - 1, cap);
			}
			for (int x = 2; x <= WIDTH - 3; x++) {
				if (x == 11 || x == 16) continue;
				set(x, 6, z - 1, stairs(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.SOUTH, Half.TOP));
			}
			// Outside (z=22): brick skin matching the exterior's other three faces, with reveals for the openings.
			int o = z + 1;
			fill(-1, 1, o, WIDTH, 7, o, Blocks.BRICKS);
			fill(-1, 0, o, WIDTH, 0, o, Blocks.STONE_BRICKS);
			for (int x : new int[] {-1, 0, 11, 16, WIDTH - 1, WIDTH}) fill(x, 1, o, x, 7, o, Blocks.STONE_BRICKS);
			for (int[] w : FRONT_WINDOWS) {
				fill(w[0], 3, o, w[1], 5, o, Blocks.AIR);
				fill(w[0], 2, o, w[1], 2, o, Blocks.SMOOTH_STONE);
				fill(w[0], 6, o, w[1], 6, o, Blocks.SMOOTH_STONE);
			}
			fill(13, 1, o, 14, 2, o, Blocks.AIR);
			fill(13, 0, o, 14, 0, o, Blocks.STONE_BRICKS);
			fill(-1, 8, o, WIDTH, 8, o, Blocks.DEEPSLATE_TILES);
			BlockState eave = Blocks.DEEPSLATE_TILE_STAIRS.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
					.setValue(BlockStateProperties.HALF, Half.BOTTOM);
			for (int x = -1; x <= WIDTH; x++) {
				set(x, 9, o, Blocks.DEEPSLATE_TILE_SLAB.defaultBlockState());
				set(x, 8, o + 1, eave);
			}
			// Short entrance path with two lanterns under the eave and two on posts at the end.
			fill(13, 0, o + 1, 14, 0, o + 3, Blocks.STONE_BRICKS);
			fill(12, 0, o + 1, 12, 0, o + 3, Blocks.POLISHED_ANDESITE);
			fill(15, 0, o + 1, 15, 0, o + 3, Blocks.POLISHED_ANDESITE);
			fill(12, 1, o + 1, 15, 7, o + 3, Blocks.AIR);
			BlockState hanging = Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true);
			set(12, 7, o + 1, hanging);
			set(15, 7, o + 1, hanging);
			set(12, 1, o + 3, Blocks.SPRUCE_FENCE);
			set(15, 1, o + 3, Blocks.SPRUCE_FENCE);
			set(12, 2, o + 3, Blocks.LANTERN.defaultBlockState());
			set(15, 2, o + 3, Blocks.LANTERN.defaultBlockState());
		}

		private void frontDoor(int x, DoorHingeSide hinge) {
			BlockState base = Blocks.SPRUCE_DOOR.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
					.setValue(BlockStateProperties.DOOR_HINGE, hinge)
					.setValue(BlockStateProperties.OPEN, false);
			set(x, 1, DEPTH - 1, base.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
			set(x, 2, DEPTH - 1, base.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
		}

		private void counterBase() {
			// Two-deep counter x 2..14; the stove group (smoker, furnace, smoker) sits under the hood bay x 12..14.
			fill(2, 1, 3, 14, 1, 3, Blocks.SMOOTH_STONE);
			fill(2, 1, 4, 14, 1, 4, Blocks.POLISHED_ANDESITE);
			set(12, 1, 4, lit(Blocks.SMOKER));
			set(13, 1, 4, lit(Blocks.FURNACE));
			set(14, 1, 4, lit(Blocks.SMOKER));
		}

		private void pass() {
			// The pass (ck_screen 9,2,8): smooth quartz top at y=2.0, spruce frontage panels, two recessed barrels.
			fill(2, 1, 8, 15, 1, 8, Blocks.SMOOTH_QUARTZ);
			BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH);
			set(4, 1, 8, barrel);
			set(13, 1, 8, barrel);
			// Open trapdoor facing south = panel on the north edge of its cell, flush against the pass.
			BlockState front = Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
					.setValue(BlockStateProperties.OPEN, true)
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
			for (int x = 2; x <= 15; x++) {
				if (x != 4 && x != 13) set(x, 1, 9, front);
			}
			// No ticket-rail chain: it hid the chef from the camera. Clear the cells in case an older build left it.
			fill(2, 2, 8, 2, 3, 8, Blocks.AIR);
			fill(15, 2, 8, 15, 3, 8, Blocks.AIR);
			for (int x = 3; x <= 14; x++) set(x, 3, 8, Blocks.AIR.defaultBlockState());
		}

		private void deliveryDoor() {
			// East wall, z 4..7: stripped dark oak frame, stone brick threshold, spruce double door at z=5,6.
			fill(WIDTH - 1, 1, 4, WIDTH - 1, 3, 4, Blocks.STRIPPED_DARK_OAK_LOG);
			fill(WIDTH - 1, 1, 7, WIDTH - 1, 3, 7, Blocks.STRIPPED_DARK_OAK_LOG);
			fill(WIDTH - 1, 3, 5, WIDTH - 1, 3, 6,
					Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
			fill(WIDTH - 2, 0, 5, WIDTH - 1, 0, 6, Blocks.STONE_BRICKS);
			doors(false);
			// Lantern over the door on a short chain from the ceiling beam (outside every board sightline).
			fill(WIDTH - 2, 5, 5, WIDTH - 2, 6, 5, Blocks.IRON_CHAIN.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
			set(WIDTH - 2, 4, 5, Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true));
			// Compact delivery stack south of the bag drop.
			BlockState barrel = Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP);
			set(WIDTH - 2, 1, 8, barrel);
			set(WIDTH - 2, 2, 8, barrel);
			set(WIDTH - 2, 1, 9, barrel);
		}

		void doors(boolean open) {
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
			// Tables A, B, C (4x2) and D, E spares: dark oak top slab with a white cloth (surface = TABLE_TOP_Y).
			for (int[] t : TABLES) {
				fill(t[0], 1, t[1], t[0] + 3, 1, t[1] + 1,
						Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));
				fill(t[0], 2, t[1], t[0] + 3, 2, t[1] + 1, Blocks.WHITE_CARPET);
			}
			// Light grey rug ring around tables A-C on the free cells (seats and tables keep their own blocks).
			for (int i = 0; i < 3; i++) {
				int[] t = TABLES[i];
				for (int x = t[0] - 1; x <= t[0] + 4; x++) {
					for (int z = t[1] - 1; z <= t[1] + 2; z++) {
						boolean edgeColumn = x == t[0] - 1 || x == t[0] + 4;
						boolean middleOfLongSide = (z == t[1] - 1 || z == t[1] + 2) && (x == t[0] + 1 || x == t[0] + 2);
						if (edgeColumn || middleOfLongSide) set(x, 1, z, Blocks.LIGHT_GRAY_CARPET);
					}
				}
			}
			// Seats from CrewkitAnchors.SEATS: spruce stair plus a trapdoor backrest behind the sitter.
			// facing 0 = sitter faces south (stair back north); an open trapdoor facing F has its panel on the side opposite F.
			for (double[] seat : CrewkitAnchors.SEATS) {
				int x = (int) Math.floor(seat[0]);
				int z = (int) Math.floor(seat[1]);
				Direction faces = seat[2] == 0 ? Direction.SOUTH : Direction.NORTH;
				set(x, 1, z, stairs(Blocks.SPRUCE_STAIRS, faces.getOpposite(), Half.BOTTOM));
				set(x, 2, z, Blocks.SPRUCE_TRAPDOOR.defaultBlockState()
						.setValue(BlockStateProperties.OPEN, true)
						.setValue(BlockStateProperties.HORIZONTAL_FACING, faces));
			}
		}

		private void fillLights() {
			// The bulbs do the real lighting (shaders cast proper shadows); a soft front fill keeps faces toward the camera readable.
			BlockState fill = Blocks.LIGHT.defaultBlockState().setValue(BlockStateProperties.LEVEL, 9);
			for (int x : new int[] {4, 10, 17, 23}) set(x, 4, 19, fill);
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
	}
}
