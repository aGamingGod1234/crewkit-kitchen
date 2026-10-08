package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Things a player recognises at a glance in its current view: dark open spaces (caves) and ore veins from what the sight
 * rays and the local scan see, plus the far-sight rows ({@link FarSight}: structures, points of interest, biomes,
 * possibly player-built clusters, lava and notable blocks). Nothing outside the agent's line of sight is ever reported.
 */
public final class SightedFeatures {
	static final int MAX_STRUCTURES = 4;
	static final int MAX_CAVES = 3;
	static final int MAX_VEINS = 4;
	/**
	 * Roofed air connected to a seen open cell inside a 5x5x5 box around it; a dug 1x2 tunnel holds about 10 cells, a
	 * cave far more. Air behind a wall is not connected, so it never counts.
	 */
	static final int CAVE_BOX_RADIUS = 2;
	static final int CAVE_MIN_AIR = 30;
	/** Share of the walls and roof around that air that must be natural terrain, so house interiors are not caves. */
	static final double CAVE_MIN_NATURAL_SHARE = 0.6D;
	/**
	 * Air filling this share of its own bounding box, with walls inside the box, is a dug shape (a 3x3 tunnel, a
	 * rectangular room): players dig straight faces, caves are irregular. A space wider than the box in every
	 * direction cannot be judged by shape and stays a cave.
	 */
	static final double CAVE_MAX_REGULAR_FILL = 0.9D;
	static final int CAVE_SEPARATION = 8;
	/** Sky light 11 or less: about four blocks under cover, so a shallow ledge on a lit hillside does not count. */
	static final int CAVE_MAX_SKY_LIGHT = 11;
	static final int MAX_CAVE_PROBES = 24;
	static final int MAX_VEIN_SEEDS = 16;
	static final int MAX_VEIN_NODES = 32;
	static final int MAX_VEIN_VISIBILITY_CHECKS = 48;
	/** A structure seen again within a minute is not new; one seen after that is announced again. */
	static final long NEW_STRUCTURE_TICKS = 1_200L;
	/**
	 * Passive updates carry a far-sight row only for 30 s after it is announced: long enough to survive a model turn that
	 * coalesces several updates, short enough that the same village is not re-told on every update.
	 */
	static final long RECENT_TICKS = 600L;
	static final int MAX_REMEMBERED_STRUCTURES = 128;
	/** Passive rows per far-sight section; a survey can ask for up to {@link FarSight#MAX_ROWS}. */
	static final Map<FarSight.Section, Integer> PASSIVE_ROWS = Map.of(FarSight.Section.STRUCTURES, MAX_STRUCTURES,
			FarSight.Section.POI, 4, FarSight.Section.BIOMES, 4, FarSight.Section.BUILT, 3, FarSight.Section.BLOCKS, 4);
	private static final Set<net.minecraft.world.level.block.Block> NATURAL_BLOCKS = Set.of(
			Blocks.STONE, Blocks.DEEPSLATE, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.NETHERRACK,
			Blocks.BASALT, Blocks.BLACKSTONE, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.SAND, Blocks.RED_SAND,
			Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.TERRACOTTA, Blocks.SNOW_BLOCK,
			Blocks.POWDER_SNOW, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.OBSIDIAN, Blocks.MAGMA_BLOCK, Blocks.SCULK,
			Blocks.SCULK_VEIN, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.END_STONE,
			Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.GLOWSTONE, Blocks.MYCELIUM, Blocks.PODZOL, Blocks.ROOTED_DIRT,
			Blocks.MOSS_CARPET, Blocks.VINE, Blocks.GLOW_LICHEN, Blocks.HANGING_ROOTS);
	/** Block id fragments that only come from building (a player's or a generated structure's), never from terrain. */
	private static final List<String> BUILT_FRAGMENTS = List.of(
			"planks", "_stairs", "_slab", "_wall", "_fence", "_door", "_trapdoor", "bricks", "_tiles", "polished_", "cut_",
			"chiseled_", "smooth_sandstone", "smooth_red_sandstone", "smooth_stone", "smooth_quartz", "glass", "_carpet",
			"_wool", "_bed", "rail", "lantern", "torch", "chest", "barrel", "crafting_table", "furnace", "smoker",
			"bookshelf", "lectern", "composter", "bell", "hay_block", "_glazed_terracotta", "copper", "prismarine",
			"sea_lantern", "purpur", "end_rod", "gilded_blackstone", "crying_obsidian", "gold_block", "iron_bars", "chain",
			"cobblestone", "cobbled_", "spawner", "dispenser", "lever", "tripwire", "ladder", "_sign", "_banner",
			"flower_pot", "candle", "campfire", "cauldron", "anvil", "_button", "_pressure_plate", "quartz_block",
			"quartz_pillar", "packed_mud", "dirt_path", "decorated_pot", "vault", "trial_spawner", "loom", "stonecutter",
			"grindstone", "cartography_table", "fletching_table", "smithing_table", "brewing_stand", "jack_o_lantern",
			"target", "beehive", "redstone_lamp", "sticky_piston");
	/** Generated structures a player would name at a glance, by registry path; buried treasure is never visible. */
	private static final Map<String, String> STRUCTURE_LABELS = Map.ofEntries(
			Map.entry("shipwreck", "shipwreck"), Map.entry("shipwreck_beached", "shipwreck"),
			Map.entry("desert_pyramid", "desert temple"), Map.entry("jungle_pyramid", "jungle temple"),
			Map.entry("swamp_hut", "witch hut"), Map.entry("igloo", "igloo"), Map.entry("mineshaft", "mineshaft"),
			Map.entry("mineshaft_mesa", "mineshaft"), Map.entry("stronghold", "stronghold"), Map.entry("monument", "ocean monument"),
			Map.entry("mansion", "woodland mansion"), Map.entry("pillager_outpost", "pillager outpost"),
			Map.entry("trail_ruins", "trail ruins"), Map.entry("trial_chambers", "trial chambers"),
			Map.entry("ancient_city", "ancient city"), Map.entry("fortress", "nether fortress"),
			Map.entry("bastion_remnant", "bastion"), Map.entry("end_city", "end city"),
			Map.entry("ocean_ruin_cold", "ocean ruins"), Map.entry("ocean_ruin_warm", "ocean ruins"),
			Map.entry("nether_fossil", "nether fossil"));

	private SightedFeatures() {
	}

	public record Cell(int x, int y, int z) {
		Cell offset(int dx, int dy, int dz) {
			return new Cell(x + dx, y + dy, z + dz);
		}

		static Cell of(BlockPos position) {
			return new Cell(position.getX(), position.getY(), position.getZ());
		}

		BlockPos position() {
			return new BlockPos(x, y, z);
		}
	}

	/** One reported feature: its identity key, label (structure or block id), nearest seen cell and a count. */
	public record Sighting(String key, String label, Cell cell, double distance, int count) {
		public Sighting {
			Objects.requireNonNull(key, "key must not be null");
			Objects.requireNonNull(cell, "cell must not be null");
		}
	}

	/** Rows computed once per cached view; bearings are added when the rows are rendered. */
	public record Sample(List<FarSight.Row> far, List<Sighting> caves, List<Sighting> veins) {
		public static final Sample EMPTY = new Sample(List.of(), List.of(), List.of());

		public Sample {
			far = List.copyOf(far);
			caves = List.copyOf(caves);
			veins = List.copyOf(veins);
		}

		boolean isEmpty() {
			return far.isEmpty() && caves.isEmpty() && veins.isEmpty();
		}
	}

	/** Deepslate and stone variants of one ore are one vein; non-ores are null. */
	static String oreFamily(String blockId) {
		if (blockId == null) return null;
		if (blockId.equals("minecraft:ancient_debris")) return blockId;
		if (!blockId.endsWith("_ore")) return null;
		return blockId.replace(":deepslate_", ":");
	}

	/**
	 * Groups seen ore into connected veins (26-neighbour, same ore family) and counts the members the agent
	 * can see. Hidden members only connect seen ones; they are never counted.
	 */
	static List<Sighting> veins(
			List<Cell> seenOre,
			Function<Cell, String> blockIdAt,
			Predicate<Cell> visible,
			ToDoubleFunction<Cell> distance
	) {
		List<Cell> seeds = seenOre.stream().sorted(Comparator.comparingDouble(distance)).limit(MAX_VEIN_SEEDS).toList();
		Set<Cell> seedSet = new HashSet<>(seeds);
		Set<Cell> claimed = new HashSet<>();
		List<Sighting> veins = new ArrayList<>();
		int[] visibilityChecks = {0};
		for (Cell seed : seeds) {
			if (veins.size() == MAX_VEINS) break;
			if (claimed.contains(seed)) continue;
			String family = oreFamily(blockIdAt.apply(seed));
			if (family == null) continue;
			ArrayDeque<Cell> queue = new ArrayDeque<>();
			queue.add(seed);
			claimed.add(seed);
			int nodes = 0;
			int seen = 0;
			Cell nearest = seed;
			while (!queue.isEmpty() && nodes < MAX_VEIN_NODES) {
				Cell cell = queue.poll();
				nodes++;
				boolean isSeen = seedSet.contains(cell);
				if (!isSeen && visibilityChecks[0] < MAX_VEIN_VISIBILITY_CHECKS) {
					visibilityChecks[0]++;
					isSeen = visible.test(cell);
				}
				if (isSeen) {
					seen++;
					if (distance.applyAsDouble(cell) < distance.applyAsDouble(nearest)) nearest = cell;
				}
				for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) continue;
					Cell next = cell.offset(dx, dy, dz);
					if (claimed.contains(next) || !family.equals(oreFamily(blockIdAt.apply(next)))) continue;
					claimed.add(next);
					queue.add(next);
				}
			}
			veins.add(new Sighting(family + "@" + seed.x() + "," + seed.y() + "," + seed.z(), blockIdAt.apply(nearest), nearest,
					distance.applyAsDouble(nearest), Math.max(1, seen)));
		}
		return List.copyOf(veins);
	}

	/** Whether a seen open cell is in a cave: roofed, roomy and walled (walls and roof) mostly by natural terrain. */
	static boolean caveSpace(boolean roofed, int air, int solid, int natural) {
		return roofed && air >= CAVE_MIN_AIR && solid > 0 && natural >= CAVE_MIN_NATURAL_SHARE * solid;
	}

	/** One row per cave (rows at least 8 blocks apart), nearest first, from the open cells the sight rays crossed. */
	static List<Sighting> caves(
			List<Cell> openings,
			Predicate<Cell> roofed,
			Predicate<Cell> shaded,
			Predicate<Cell> air,
			Predicate<Cell> natural,
			ToDoubleFunction<Cell> distance
	) {
		List<Cell> ordered = openings.stream().sorted(Comparator.comparingDouble(distance)).toList();
		List<Cell> probed = new ArrayList<>();
		List<Sighting> caves = new ArrayList<>();
		for (Cell cell : ordered) {
			if (caves.size() == MAX_CAVES || probed.size() == MAX_CAVE_PROBES) break;
			if (caves.stream().anyMatch(cave -> chebyshev(cave.cell(), cell) <= CAVE_SEPARATION)
					|| probed.stream().anyMatch(previous -> chebyshev(previous, cell) <= CAVE_BOX_RADIUS)) continue;
			probed.add(cell);
			if (!air.test(cell) || !roofed.test(cell) || !shaded.test(cell)) continue;
			// The open space in front of the seen face: air connected to it inside the box. Air behind a wall is not
			// part of what the agent looks into, so it never counts.
			Set<Cell> space = new HashSet<>();
			ArrayDeque<Cell> queue = new ArrayDeque<>();
			space.add(cell);
			queue.add(cell);
			Set<Cell> walls = new HashSet<>();
			int roofedAir = 0;
			int minX = cell.x(), maxX = cell.x(), minY = cell.y(), maxY = cell.y(), minZ = cell.z(), maxZ = cell.z();
			while (!queue.isEmpty()) {
				Cell open = queue.poll();
				// Open air under the sky (beside a hillside ledge) is not cave space.
				if (roofed.test(open)) roofedAir++;
				minX = Math.min(minX, open.x()); maxX = Math.max(maxX, open.x());
				minY = Math.min(minY, open.y()); maxY = Math.max(maxY, open.y());
				minZ = Math.min(minZ, open.z()); maxZ = Math.max(maxZ, open.z());
				for (int[] step : NEIGHBOURS) {
					Cell next = open.offset(step[0], step[1], step[2]);
					if (chebyshev(next, cell) > CAVE_BOX_RADIUS || space.contains(next) || walls.contains(next)) continue;
					if (air.test(next)) {
						space.add(next);
						queue.add(next);
					} else {
						walls.add(next);
					}
				}
			}
			int naturalWalls = 0;
			for (Cell wall : walls) if (natural.test(wall)) naturalWalls++;
			if (!caveSpace(true, roofedAir, walls.size(), naturalWalls)) continue;
			int box = CAVE_BOX_RADIUS * 2 + 1;
			boolean spansBox = maxX - minX + 1 == box && maxY - minY + 1 == box && maxZ - minZ + 1 == box;
			double fill = (double) space.size() / ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1));
			if (!spansBox && fill >= CAVE_MAX_REGULAR_FILL) continue;
			caves.add(new Sighting("cave@" + cell.x() + "," + cell.y() + "," + cell.z(), null, cell, distance.applyAsDouble(cell), roofedAir));
		}
		return List.copyOf(caves);
	}

	private static final int[][] NEIGHBOURS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	static int chebyshev(Cell left, Cell right) {
		return Math.max(Math.abs(left.x() - right.x()), Math.max(Math.abs(left.y() - right.y()), Math.abs(left.z() - right.z())));
	}

	/**
	 * When each far-sight key was announced and last seen, per agent. A key is announced when first seen, or seen again
	 * after a minute out of sight; it stays in passive updates for {@link #RECENT_TICKS} after that.
	 */
	static final class Announcements {
		private final Map<String, long[]> keys = new LinkedHashMap<>();

		/** Records a sighting and returns the tick it was announced. */
		synchronized long see(String key, long gameTime) {
			long[] times = keys.get(key);
			if (times == null || gameTime - times[1] > NEW_STRUCTURE_TICKS || gameTime < times[1]) {
				times = new long[] {gameTime, gameTime};
				keys.remove(key);
				keys.put(key, times);
			} else {
				times[1] = gameTime;
			}
			evict(key);
			return times[0];
		}

		/**
		 * A survey showed the model this key: it counts as known (not announced again, no wake) without starting the
		 * passive window, so surveyed rows do not reappear in the next 30 s of updates. Returns whether it was unknown.
		 */
		synchronized boolean acknowledge(String key, long gameTime) {
			long[] times = keys.get(key);
			boolean unknown = times == null || gameTime - times[1] > NEW_STRUCTURE_TICKS || gameTime < times[1];
			if (unknown) {
				keys.remove(key);
				keys.put(key, new long[] {gameTime - RECENT_TICKS - 1, gameTime});
				evict(key);
			} else {
				times[1] = gameTime;
			}
			return unknown;
		}

		private void evict(String keep) {
			if (keys.size() <= MAX_REMEMBERED_STRUCTURES) return;
			String oldest = keys.entrySet().stream().min(Comparator.comparingLong(entry -> entry.getValue()[1])).map(Map.Entry::getKey).orElse(null);
			if (oldest != null && !oldest.equals(keep)) keys.remove(oldest);
		}

		synchronized int size() {
			return keys.size();
		}
	}

	/** Natural terrain: what cave walls are made of. */
	static boolean natural(BlockState state) {
		// Explicit common blocks too, so the rule holds before datapack tags are bound.
		return NATURAL_BLOCKS.contains(state.getBlock()) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT)
				|| state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.is(Blocks.CALCITE)
				|| state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.SMOOTH_BASALT)
				|| state.is(Blocks.BEDROCK) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(BlockTags.TERRACOTTA)
				|| state.is(Blocks.SNOW) || state.is(Blocks.ICE)
				|| !state.getFluidState().isEmpty()
				|| oreFamily(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()) != null;
	}

	/**
	 * The name a player would give a structure (village, desert temple, ocean monument...), or null for one that is
	 * never seen as such (buried treasure lies under sand). Unknown datapack structures read as their id path.
	 */
	static String structureLabel(String structureId) {
		if (structureId == null) return null;
		String path = structureId.substring(structureId.indexOf(':') + 1);
		if (path.equals("buried_treasure")) return null;
		if (path.startsWith("village_")) return "village";
		if (path.startsWith("ruined_portal")) return "ruined portal";
		String label = STRUCTURE_LABELS.get(path);
		return label != null ? label : path.replace('_', ' ');
	}

	/**
	 * Whether a seen block shows building: only built blocks identify a structure, so natural terrain inside a piece's
	 * box (a desert temple's sandstone seen from a cave) never does. Ruined portals are known by their obsidian frame
	 * and igloos by their snow dome, both small boxes with nothing else inside.
	 */
	static boolean built(BlockState state, String structureLabel) {
		if (state.isAir() || !state.getFluidState().isEmpty() && !state.hasProperty(
				net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED)) return false;
		if ("ruined portal".equals(structureLabel) && state.is(Blocks.OBSIDIAN)) return true;
		if ("igloo".equals(structureLabel) && state.is(Blocks.SNOW_BLOCK)) return true;
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (path.endsWith("_ore") || path.equals("smooth_basalt") || path.startsWith("raw_")) return false;
		for (String fragment : BUILT_FRAGMENTS) {
			if (path.contains(fragment)) return true;
		}
		return false;
	}

	/**
	 * Computes the rows for one view. {@code seenHits} are blocks the sight rays hit; {@code openings} are the open
	 * cells in front of those hits; {@code seenOre} are ore blocks (local cube and far sight) that passed visibility;
	 * {@code far} are the far-sight rows of the same view.
	 */
	static Sample sample(
			ServerLevel level,
			Vec3 eye,
			List<BlockPos> seenHits,
			List<BlockPos> openings,
			List<BlockPos> seenOre,
			Predicate<BlockPos> canSee,
			List<FarSight.Row> far
	) {
		ToDoubleFunction<Cell> distance = cell -> Math.sqrt(eye.distanceToSqr(cell.x() + 0.5D, cell.y() + 0.5D, cell.z() + 0.5D));
		Map<Long, Boolean> chunks = new HashMap<>();
		Predicate<Cell> loaded = cell -> chunks.computeIfAbsent(
				(((long) (cell.x() >> 4)) << 32) ^ ((long) (cell.z() >> 4) & 0xffffffffL),
				key -> level.hasChunkAt(cell.position()));
		Function<Cell, BlockState> stateAt = cell -> loaded.test(cell) ? level.getBlockState(cell.position()) : null;

		List<Cell> openCells = openings.stream().map(Cell::of).distinct().toList();
		Map<Long, Integer> surfaces = new HashMap<>();
		// Height and light are read only in loaded chunks; Level.getHeight would load an unloaded one.
		Predicate<Cell> roofed = cell -> loaded.test(cell) && surfaces.computeIfAbsent((((long) cell.x()) << 32) ^ (cell.z() & 0xffffffffL),
				key -> level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cell.x(), cell.z())) > cell.y() + 2;
		List<Sighting> caves = caves(openCells, roofed,
				cell -> loaded.test(cell) && level.getBrightness(LightLayer.SKY, cell.position()) <= CAVE_MAX_SKY_LIGHT,
				cell -> {
					BlockState state = stateAt.apply(cell);
					return state != null && state.isAir();
				},
				cell -> {
					BlockState state = stateAt.apply(cell);
					return state != null && natural(state);
				},
				distance);

		ArrayList<Cell> ore = new ArrayList<>();
		for (BlockPos position : seenOre) ore.add(Cell.of(position));
		for (BlockPos hit : seenHits) {
			Cell cell = Cell.of(hit);
			BlockState state = stateAt.apply(cell);
			if (state != null && oreFamily(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()) != null) ore.add(cell);
		}
		List<Sighting> veins = veins(ore.stream().distinct().toList(),
				cell -> {
					BlockState state = stateAt.apply(cell);
					return state == null ? "minecraft:air" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
				},
				cell -> canSee.test(cell.position()), distance);
		return new Sample(far, caves, veins);
	}

	/**
	 * Renders rows with bearings for the current view. Far-sight rows appear only while recently announced (see
	 * {@link Announcements}), and carry {@code new} on the update that announces them; caves and veins describe the current
	 * view on every update.
	 */
	static JsonObject toJson(Sample sample, Vec3 eye, float yaw, Announcements announcements, long gameTime) {
		JsonObject sighted = render(sample, eye, yaw, announcements, gameTime, true, PASSIVE_ROWS, EnumSet.allOf(FarSight.Section.class));
		return sighted.size() == 0 ? null : sighted;
	}

	/**
	 * Rows by section. Passive rendering keeps far-sight rows only while recently announced and marks the announcing
	 * update {@code new}. A survey shows every row of the requested sections with an {@code id}, marks rows the model did
	 * not know {@code new}, and records them as known without starting the passive window.
	 */
	static JsonObject render(Sample sample, Vec3 eye, float yaw, Announcements announcements, long gameTime, boolean passive,
			Map<FarSight.Section, Integer> limits, Set<FarSight.Section> sections) {
		JsonObject sighted = new JsonObject();
		if (passive) {
			Map<String, Long> announced = new HashMap<>();
			for (FarSight.Row row : sample.far()) announced.put(row.key(), announcements.see(row.key(), gameTime));
			FarSight.render(sighted, sample.far(), eye, yaw,
					row -> sections.contains(row.section()) && gameTime - announced.get(row.key()) <= RECENT_TICKS,
					row -> announced.get(row.key()) == gameTime, limits, false);
		} else {
			Set<String> unknown = new HashSet<>();
			for (FarSight.Row row : sample.far()) {
				if (sections.contains(row.section()) && announcements.acknowledge(row.key(), gameTime)) unknown.add(row.key());
			}
			FarSight.render(sighted, sample.far(), eye, yaw, row -> sections.contains(row.section()), row -> unknown.contains(row.key()), limits, true);
		}
		if (sections.contains(FarSight.Section.CAVES) && !sample.caves().isEmpty()) {
			JsonArray rows = new JsonArray();
			for (Sighting sighting : sample.caves()) {
				JsonObject row = row(sighting, eye, yaw);
				row.addProperty("air", sighting.count());
				rows.add(row);
			}
			sighted.add("caves", rows);
		}
		if (sections.contains(FarSight.Section.VEINS) && !sample.veins().isEmpty()) {
			JsonArray rows = new JsonArray();
			for (Sighting sighting : sample.veins()) {
				JsonObject row = row(sighting, eye, yaw);
				row.addProperty("blockId", sighting.label());
				row.addProperty("visible", sighting.count());
				rows.add(row);
			}
			sighted.add("veins", rows);
		}
		return sighted;
	}

	private static JsonObject row(Sighting sighting, Vec3 eye, float yaw) {
		JsonObject row = new JsonObject();
		row.addProperty("x", sighting.cell().x());
		row.addProperty("y", sighting.cell().y());
		row.addProperty("z", sighting.cell().z());
		row.addProperty("distance", Math.round(sighting.distance()));
		row.addProperty("bearing", bearing(eye, yaw, sighting.cell()));
		return row;
	}

	/** Degrees right (positive) or left of the current facing, like landmark bearings. */
	static long bearing(Vec3 eye, float yaw, Cell cell) {
		double dx = cell.x() + 0.5D - eye.x;
		double dz = cell.z() + 0.5D - eye.z;
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		return Math.round(net.minecraft.util.Mth.wrapDegrees(targetYaw - yaw));
	}

	/** Drops remembered structure keys of agents that left the roster. */
	static <K> void retain(Map<K, ?> remembered, Set<K> tracked) {
		for (Iterator<K> iterator = remembered.keySet().iterator(); iterator.hasNext(); ) {
			if (!tracked.contains(iterator.next())) iterator.remove();
		}
	}
}
