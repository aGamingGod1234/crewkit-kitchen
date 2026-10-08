package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.perception.SightedFeatures.Cell;
import dev.agaminggod.arenaagents.server.perception.SightedFeatures.Sample;
import dev.agaminggod.arenaagents.server.perception.SightedFeatures.Sighting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Structure, cave and ore-vein sightings: only seen blocks count, and dug tunnels or houses are not caves. */
public final class SightedFeaturesVerification {
	private static int checks;
	private static final ToDoubleFunction<Cell> FROM_ORIGIN = cell -> Math.sqrt((double) cell.x() * cell.x() + (double) cell.y() * cell.y() + (double) cell.z() * cell.z());

	private SightedFeaturesVerification() { }

	public static int verify() {
		checks = 0;
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		oreFamilies();
		veinsCountOnlySeenOre();
		cavesNeedRoomRoofAndNaturalWalls();
		structureLabels();
		onlyBuiltBlocksIdentifyStructures();
		sightedRecomputeIsThrottled();
		announcements();
		rendering();
		attention();
		naturalTerrain();
		farSightKinds();
		farSightClusters();
		farSightGeometry();
		surveyRequests();
		seenPartsOnly();
		stableClusterKeys();
		surveysAcknowledge();
		fogRange();
		perTickBudgets();
		return checks;
	}

	private static void oreFamilies() {
		check("minecraft:iron_ore".equals(SightedFeatures.oreFamily("minecraft:deepslate_iron_ore")), "deepslate iron joins the iron family");
		check("minecraft:nether_gold_ore".equals(SightedFeatures.oreFamily("minecraft:nether_gold_ore")), "nether gold keeps its id");
		check("minecraft:ancient_debris".equals(SightedFeatures.oreFamily("minecraft:ancient_debris")), "ancient debris is an ore");
		check(SightedFeatures.oreFamily("minecraft:stone") == null && SightedFeatures.oreFamily("minecraft:deepslate") == null, "stone is not ore");
	}

	private static void veinsCountOnlySeenOre() {
		Map<Cell, String> world = new HashMap<>();
		// One iron vein of six blocks (one diagonal, one deepslate) and a separate coal vein.
		List<Cell> iron = List.of(new Cell(3, 0, 0), new Cell(4, 0, 0), new Cell(5, 1, 0), new Cell(4, 0, 1), new Cell(5, 0, 1), new Cell(6, 0, 1));
		iron.forEach(cell -> world.put(cell, "minecraft:iron_ore"));
		world.put(new Cell(6, 0, 1), "minecraft:deepslate_iron_ore");
		world.put(new Cell(0, 0, 8), "minecraft:coal_ore");
		world.put(new Cell(0, 1, 8), "minecraft:coal_ore");
		Set<Cell> visible = new HashSet<>(List.of(new Cell(3, 0, 0), new Cell(4, 0, 0), new Cell(5, 1, 0), new Cell(0, 0, 8)));
		List<Sighting> veins = SightedFeatures.veins(List.of(new Cell(4, 0, 0), new Cell(0, 0, 8)),
				cell -> world.getOrDefault(cell, "minecraft:stone"), visible::contains, FROM_ORIGIN);
		check(veins.size() == 2, "two veins from two seeds");
		Sighting ironVein = veins.getFirst();
		check(ironVein.count() == 3, "only the three seen iron blocks count, not the three hidden ones: " + ironVein.count());
		check(ironVein.cell().equals(new Cell(3, 0, 0)) && "minecraft:iron_ore".equals(ironVein.label()), "the vein is reported at its nearest seen block");
		check(veins.get(1).count() == 1, "the hidden coal neighbour is not counted");

		List<Cell> seeds = new ArrayList<>();
		for (int index = 0; index < 8; index++) {
			Cell cell = new Cell(index * 4, 0, 0);
			world.put(cell, "minecraft:copper_ore");
			seeds.add(cell);
		}
		check(SightedFeatures.veins(seeds, cell -> world.getOrDefault(cell, "minecraft:stone"), cell -> true, FROM_ORIGIN).size()
				== SightedFeatures.MAX_VEINS, "vein rows are capped");
	}

	/** An irregular natural cave: a rough blob around (cx, cy, cz), as carvers leave it. */
	private static boolean blob(Cell cell, int cx, int cy, int cz) {
		int dx = cell.x() - cx, dy = cell.y() - cy, dz = cell.z() - cz;
		return dx * dx + dy * dy * 2 + dz * dz <= 7 + Math.floorMod(cell.x() * 7 + cell.z() * 13 + cell.y() * 5, 4);
	}

	private static void cavesNeedRoomRoofAndNaturalWalls() {
		Map<Cell, String> world = new HashMap<>();
		// A dug 1x2 tunnel along +x: the open cell in front of its end wall is not a cave.
		for (int x = 0; x < 12; x++) {
			world.put(new Cell(x, 0, 0), "air");
			world.put(new Cell(x, 1, 0), "air");
		}
		List<Sighting> tunnel = SightedFeatures.caves(List.of(new Cell(11, 0, 0)), cell -> true, cell -> true,
				cell -> "air".equals(world.get(cell)), cell -> !"minecraft:oak_planks".equals(world.get(cell)), FROM_ORIGIN);
		check(tunnel.isEmpty(), "a dug tunnel is not a cave");

		// Review finding: a dug 3x3 tunnel held 45 roofed air cells in the box and passed as a cave.
		Set<Cell> wide = new HashSet<>();
		for (int x = 20; x <= 60; x++) for (int y = 0; y <= 2; y++) for (int z = -1; z <= 1; z++) wide.add(new Cell(x, y, z));
		check(SightedFeatures.caves(List.of(new Cell(40, 1, 0)), cell -> true, cell -> true, wide::contains, cell -> true, FROM_ORIGIN).isEmpty(),
				"a dug 3x3 tunnel (straight faces) is not a cave");
		Set<Cell> dugRoom = new HashSet<>();
		for (int x = 38; x <= 42; x++) for (int y = 0; y <= 3; y++) for (int z = -2; z <= 2; z++) dugRoom.add(new Cell(x, y, z));
		check(SightedFeatures.caves(List.of(new Cell(40, 1, 0)), cell -> true, cell -> true, dugRoom::contains, cell -> true, FROM_ORIGIN).isEmpty(),
				"a dug rectangular room is not a cave");
		// Review finding: air behind a wall counted. A sealed pocket beside a small dug nook adds nothing.
		Set<Cell> nook = new HashSet<>(List.of(new Cell(40, 0, 0), new Cell(40, 1, 0)));
		Set<Cell> withPocket = new HashSet<>(nook);
		// Sealed pockets on both sides, behind one-block walls: 50 air cells the old count included.
		for (int x : new int[] {38, 42}) for (int y = -1; y <= 3; y++) for (int z = -2; z <= 2; z++) withPocket.add(new Cell(x, y, z));
		check(SightedFeatures.caves(List.of(new Cell(40, 1, 0)), cell -> true, cell -> true, withPocket::contains, cell -> true, FROM_ORIGIN).isEmpty(),
				"air sealed behind a wall is not counted toward a cave");

		// A natural cave 40 blocks away is a cave when roofed, and not when open to the sky.
		Cell inside = new Cell(40, 1, 0);
		java.util.function.Predicate<Cell> cave = cell -> blob(cell, 40, 1, 0);
		List<Sighting> rows = SightedFeatures.caves(List.of(inside, new Cell(41, 1, 1)), cell -> true, cell -> true, cave, cell -> true, FROM_ORIGIN);
		check(rows.size() == 1, "two openings of one cave make one row");
		check(rows.getFirst().count() >= SightedFeatures.CAVE_MIN_AIR, "the row carries its open-air count: " + rows.getFirst().count());
		check(SightedFeatures.caves(List.of(inside), cell -> false, cell -> true, cave, cell -> true, FROM_ORIGIN).isEmpty(),
				"an unroofed hollow (valley, ravine floor) is not reported as a cave");
		check(SightedFeatures.caves(List.of(inside), cell -> cell.x() == 40, cell -> true, cave, cell -> true, FROM_ORIGIN).isEmpty(),
				"a ledge over open air (a hillside overhang) is not a cave");
		check(SightedFeatures.caves(List.of(inside), cell -> true, cell -> true, cave, cell -> false, FROM_ORIGIN).isEmpty(),
				"a roofed room with built walls is a building, not a cave");
		check(SightedFeatures.caves(List.of(inside, new Cell(39, 1, 1), new Cell(41, 2, -1)), cell -> true, cell -> true, cave, cell -> true, FROM_ORIGIN).size() == 1,
				"openings across one cave stay one row");
		check(SightedFeatures.caves(List.of(inside), cell -> true, cell -> false, cave, cell -> true, FROM_ORIGIN).isEmpty(),
				"a sky-lit opening is not a cave");
		check(!SightedFeatures.caveSpace(true, SightedFeatures.CAVE_MIN_AIR - 1, 100, 100), "too little air is not a cave");

		List<Cell> many = new ArrayList<>();
		for (int x = 2; x < 64; x += 12) many.add(new Cell(x, 1, 1));
		// A long winding natural passage of rough cross-section.
		java.util.function.Predicate<Cell> passage = cell -> cell.x() >= 0 && cell.x() < 64 && blob(cell, cell.x(), 1, 1);
		check(SightedFeatures.caves(many, cell -> true, cell -> true, passage, cell -> true, FROM_ORIGIN).size()
				== SightedFeatures.MAX_CAVES, "cave rows are capped");
	}

	/** Review decision: structures read as a player names them, and buried treasure (never visible) is never reported. */
	private static void structureLabels() {
		String[][] expected = {
				{"minecraft:village_plains", "village"}, {"minecraft:village_snowy", "village"}, {"minecraft:shipwreck_beached", "shipwreck"},
				{"minecraft:desert_pyramid", "desert temple"}, {"minecraft:jungle_pyramid", "jungle temple"}, {"minecraft:swamp_hut", "witch hut"},
				{"minecraft:igloo", "igloo"}, {"minecraft:ruined_portal_nether", "ruined portal"}, {"minecraft:mineshaft_mesa", "mineshaft"},
				{"minecraft:stronghold", "stronghold"}, {"minecraft:monument", "ocean monument"}, {"minecraft:mansion", "woodland mansion"},
				{"minecraft:pillager_outpost", "pillager outpost"}, {"minecraft:trail_ruins", "trail ruins"},
				{"minecraft:trial_chambers", "trial chambers"}, {"minecraft:ancient_city", "ancient city"},
				{"minecraft:fortress", "nether fortress"}, {"minecraft:bastion_remnant", "bastion"}, {"minecraft:end_city", "end city"},
				{"minecraft:ocean_ruin_warm", "ocean ruins"}};
		for (String[] pair : expected) check(pair[1].equals(SightedFeatures.structureLabel(pair[0])), pair[0] + " reads as " + pair[1]);
		check(SightedFeatures.structureLabel("minecraft:buried_treasure") == null, "buried treasure is never reported");
		check("sky tower".equals(SightedFeatures.structureLabel("mymod:sky_tower")), "a datapack structure reads as its name");
	}

	/**
	 * Review finding: natural blocks inside a structure piece's box (a desert temple's sandstone seen from a cave below)
	 * reported the structure though nothing built was visible. Only built blocks identify one now.
	 */
	private static void onlyBuiltBlocksIdentifyStructures() {
		for (var block : List.of(Blocks.SANDSTONE, Blocks.RED_SANDSTONE, Blocks.TERRACOTTA, Blocks.ORANGE_TERRACOTTA, Blocks.OAK_LOG,
				Blocks.OAK_LEAVES, Blocks.VINE, Blocks.SHORT_GRASS, Blocks.SNOW_BLOCK, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.OBSIDIAN,
				Blocks.MAGMA_BLOCK, Blocks.SCULK, Blocks.CRIMSON_NYLIUM, Blocks.STONE, Blocks.DEEPSLATE, Blocks.WATER, Blocks.IRON_ORE,
				Blocks.SMOOTH_BASALT, Blocks.RAW_IRON_BLOCK, Blocks.COBWEB, Blocks.GRAVEL, Blocks.SAND)) {
			check(!SightedFeatures.built(block.defaultBlockState(), "desert temple"), block + " is terrain, not building");
		}
		for (var block : List.of(Blocks.CUT_SANDSTONE, Blocks.CHISELED_SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.OAK_PLANKS,
				Blocks.SPRUCE_FENCE, Blocks.STONE_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE,
				Blocks.NETHER_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE, Blocks.PURPUR_BLOCK,
				Blocks.END_STONE_BRICKS, Blocks.DEEPSLATE_TILES, Blocks.TUFF_BRICKS, Blocks.WAXED_CUT_COPPER, Blocks.MUD_BRICKS,
				Blocks.CHEST, Blocks.RAIL, Blocks.GLASS_PANE, Blocks.WHITE_WOOL, Blocks.CRYING_OBSIDIAN, Blocks.HAY_BLOCK)) {
			check(SightedFeatures.built(block.defaultBlockState(), "village"), block + " shows building");
		}
		check(SightedFeatures.built(Blocks.OBSIDIAN.defaultBlockState(), "ruined portal"), "a ruined portal is known by its obsidian frame");
		check(SightedFeatures.built(Blocks.SNOW_BLOCK.defaultBlockState(), "igloo"), "an igloo is known by its snow dome");
	}

	/** Review finding: the sight cache keyed on the exact view rarely hit, so every observation recomputed the rows. */
	private static void sightedRecomputeIsThrottled() {
		ServerObservationCollector.SightedMemo memo = new ServerObservationCollector.SightedMemo("minecraft:overworld", 100L, Sample.EMPTY);
		check(ServerObservationCollector.sightedDue(null, "minecraft:overworld", 100L), "the first sample is computed");
		check(!ServerObservationCollector.sightedDue(memo, "minecraft:overworld", 109L), "within 10 ticks the rows are reused");
		check(ServerObservationCollector.sightedDue(memo, "minecraft:overworld", 110L), "after 10 ticks they are recomputed");
		check(ServerObservationCollector.sightedDue(memo, "minecraft:the_nether", 101L), "a dimension change recomputes at once");
		int recomputes = 0;
		ServerObservationCollector.SightedMemo current = null;
		for (long tick = 0; tick < 200; tick++) {
			if (ServerObservationCollector.sightedDue(current, "minecraft:overworld", tick)) {
				recomputes++;
				current = new ServerObservationCollector.SightedMemo("minecraft:overworld", tick, Sample.EMPTY);
			}
		}
		check(recomputes == 20, "an observation every tick for 10 s recomputes 20 times, not 200: " + recomputes);
		check(ServerObservationCollector.quantize(64.62D, ServerObservationCollector.LANDMARK_EYE_QUANTUM) == 64.5D
				&& ServerObservationCollector.quantize(64.70D, ServerObservationCollector.LANDMARK_EYE_QUANTUM) == 64.5D,
				"eye positions within a quarter block share a landmark key");
		check(ServerObservationCollector.quantize(91.0D, ServerObservationCollector.LANDMARK_VIEW_QUANTUM_DEGREES)
				== ServerObservationCollector.quantize(93.5D, ServerObservationCollector.LANDMARK_VIEW_QUANTUM_DEGREES),
				"a 2.5 degree head movement reuses the sight rays");
	}

	/** A far-sight row is announced once, rides passive updates for 30 s, and is announced again after a minute away. */
	private static void announcements() {
		SightedFeatures.Announcements seen = new SightedFeatures.Announcements();
		check(seen.see("village", 100) == 100, "first sighting is announced now");
		check(seen.see("village", 400) == 100, "a second glance keeps the first announcement");
		check(seen.see("village", 1_500) == 100, "a steady sighting (glances under a minute apart) is never re-announced");
		long back = 1_500 + SightedFeatures.NEW_STRUCTURE_TICKS + 1;
		check(seen.see("village", back) == back, "seen again after a minute out of sight is announced again");
		for (int index = 0; index < SightedFeatures.MAX_REMEMBERED_STRUCTURES + 5; index++) seen.see("s" + index, 5_000 + index);
		check(seen.size() <= SightedFeatures.MAX_REMEMBERED_STRUCTURES, "the latch is bounded");
	}

	private static FarSight.Row farRow(FarSight.Section section, String key, String label, List<String> blocks, int count, int size, Cell cell) {
		return new FarSight.Row(section, key, label, blocks, count, size, cell, Math.sqrt((double) cell.x() * cell.x() + (double) cell.z() * cell.z()));
	}

	private static void rendering() {
		SightedFeatures.Announcements seen = new SightedFeatures.Announcements();
		check(SightedFeatures.toJson(Sample.EMPTY, Vec3.ZERO, 0.0F, seen, 0L) == null, "nothing seen: no field");
		Sample sample = new Sample(
				List.of(farRow(FarSight.Section.STRUCTURES, "minecraft:shipwreck@1,2", "shipwreck", List.of(), 0, 20, new Cell(0, 60, 40)),
						farRow(FarSight.Section.STRUCTURES, "minecraft:village_plains@9,9", null, List.of("minecraft:oak_planks", "minecraft:cobblestone"), 0, 48, new Cell(0, 70, 150)),
						farRow(FarSight.Section.BUILT, "built@1,2,3", null, List.of("minecraft:oak_planks", "minecraft:glass", "minecraft:torch"), 0, 20, new Cell(-100, 64, 100)),
						farRow(FarSight.Section.BIOMES, "biome:minecraft:desert", "minecraft:desert", List.of(), 0, 0, new Cell(64, 64, 64)),
						farRow(FarSight.Section.POI, "poi:minecraft:nether_portal@1", "minecraft:nether_portal", List.of(), 0, 0, new Cell(10, 64, 120)),
						farRow(FarSight.Section.BLOCKS, "lava@1", "minecraft:lava", List.of(), 12, 0, new Cell(5, 60, 20))),
				List.of(new Sighting("cave@0,0,1", null, new Cell(10, 0, 0), 10.2, 44)),
				List.of(new Sighting("minecraft:iron_ore@1,0,0", "minecraft:deepslate_iron_ore", new Cell(1, 0, 0), 1.2, 5)));
		JsonObject json = SightedFeatures.toJson(sample, new Vec3(0.5D, 0.0D, 0.5D), 0.0F, seen, 1_000L);
		JsonObject near = json.getAsJsonArray("structures").get(0).getAsJsonObject();
		check("shipwreck".equals(near.get("structure").getAsString()) && near.get("new").getAsBoolean(), "a near structure is named and marked new");
		check(near.get("distance").getAsLong() == 40 && near.get("bearing").getAsLong() == 0, "straight ahead at 40 blocks");
		JsonObject far = json.getAsJsonArray("structures").get(1).getAsJsonObject();
		check(!far.has("structure") && far.getAsJsonArray("blocks").size() == 2 && far.get("size").getAsInt() == 48,
				"a far structure gives clues (built blocks seen, size), not a name");
		JsonObject built = json.getAsJsonArray("built").get(0).getAsJsonObject();
		check(built.getAsJsonArray("blocks").size() == 3 && built.get("size").getAsInt() == 20 && built.get("bearing").getAsLong() == 45,
				"a built cluster lists its seen blocks, its size and a bearing (45 degrees to the right)");
		check("minecraft:desert".equals(json.getAsJsonArray("biomes").get(0).getAsJsonObject().get("biome").getAsString()), "biome row");
		check("minecraft:nether_portal".equals(json.getAsJsonArray("poi").get(0).getAsJsonObject().get("blockId").getAsString()), "poi row");
		check(json.getAsJsonArray("blocks").get(0).getAsJsonObject().get("count").getAsInt() == 12, "a lava pool row carries its exposed size");
		JsonObject cave = json.getAsJsonArray("caves").get(0).getAsJsonObject();
		check(cave.get("air").getAsInt() == 44 && cave.get("bearing").getAsLong() == -90, "a cave to the east is 90 degrees off a south-facing view, signed like landmarks");
		JsonObject vein = json.getAsJsonArray("veins").get(0).getAsJsonObject();
		check(vein.get("visible").getAsInt() == 5 && "minecraft:deepslate_iron_ore".equals(vein.get("blockId").getAsString()), "vein row keeps the seen block id and count");

		JsonObject later = SightedFeatures.toJson(sample, Vec3.ZERO, 0.0F, seen, 1_000L + SightedFeatures.RECENT_TICKS);
		check(later.getAsJsonArray("structures").size() == 2 && !later.getAsJsonArray("structures").get(0).getAsJsonObject().has("new"),
				"within 30 s the rows stay in passive updates without the new flag");
		JsonObject steady = SightedFeatures.toJson(sample, Vec3.ZERO, 0.0F, seen, 1_000L + SightedFeatures.RECENT_TICKS + 1);
		check(!steady.has("structures") && !steady.has("built") && !steady.has("biomes") && !steady.has("poi") && !steady.has("blocks"),
				"after 30 s a structure still in view is not re-told on every passive update");
		check(steady.has("caves") && steady.has("veins"), "caves and veins still describe the current view");
		JsonObject survey = SightedFeatures.render(sample, Vec3.ZERO, 0.0F, seen, 1_000L + SightedFeatures.RECENT_TICKS + 2, false,
				Map.of(FarSight.Section.STRUCTURES, 1), java.util.EnumSet.of(FarSight.Section.STRUCTURES, FarSight.Section.BUILT));
		check(survey.getAsJsonArray("structures").size() == 1 && survey.has("built") && !survey.has("caves") && !survey.has("biomes"),
				"a survey shows known rows again, only the asked sections, and honours the per-section limit");
		check(json.toString().length() < 1_100, "every section filled stays compact: " + json.toString().length() + " characters");
	}

	private static void farSightKinds() {
		BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
		check(FarSight.looksBuilt(planks, 0) && FarSight.looksBuilt(Blocks.GLASS.defaultBlockState(), 0) && FarSight.looksBuilt(Blocks.TORCH.defaultBlockState(), 0),
				"planks, glass and torches look built");
		check(FarSight.looksBuilt(Blocks.NETHERRACK.defaultBlockState(), 0) && !FarSight.looksBuilt(Blocks.NETHERRACK.defaultBlockState(), 1),
				"netherrack is out of place in the overworld, not in the Nether");
		check(FarSight.looksBuilt(Blocks.GRASS_BLOCK.defaultBlockState(), 1) && FarSight.looksBuilt(Blocks.OAK_LOG.defaultBlockState(), 1)
				&& !FarSight.looksBuilt(Blocks.CRIMSON_STEM.defaultBlockState(), 1), "overworld ground and logs are out of place in the Nether; its own stems are not");
		for (Block natural : List.of(Blocks.STONE, Blocks.GRASS_BLOCK, Blocks.SAND, Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.OBSIDIAN, Blocks.MAGMA_BLOCK,
				Blocks.MOSSY_COBBLESTONE, Blocks.MOSS_CARPET, Blocks.PALE_MOSS_CARPET, Blocks.IRON_ORE, Blocks.WATER, Blocks.SMOOTH_BASALT)) {
			check(!FarSight.looksBuilt(natural.defaultBlockState(), 0), natural + " is terrain (boulders and moss carpets included)");
		}
		check(FarSight.kinds(Blocks.LAVA.defaultBlockState(), 0) == FarSight.LAVA, "lava");
		check(FarSight.kinds(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), 0) == FarSight.ORE, "ore");
		check(FarSight.kinds(Blocks.SPAWNER.defaultBlockState(), 0) == (FarSight.NOTABLE | FarSight.BUILT), "a spawner is notable and built");
		check((FarSight.kinds(Blocks.BELL.defaultBlockState(), 0) & FarSight.POI) != 0 && (FarSight.kinds(Blocks.NETHER_PORTAL.defaultBlockState(), 0) & FarSight.POI) != 0,
				"bells and portals are points of interest");
		check(FarSight.kinds(Blocks.AIR.defaultBlockState(), 0) == 0 && FarSight.kinds(Blocks.STONE.defaultBlockState(), 0) == 0, "air and stone are nothing");
		BlockState air = Blocks.AIR.defaultBlockState(), stone = Blocks.STONE.defaultBlockState(), lava = Blocks.LAVA.defaultBlockState();
		check(FarSight.faceOpen(stone, air) && FarSight.faceOpen(stone, Blocks.GLASS.defaultBlockState()) && FarSight.faceOpen(stone, Blocks.WATER.defaultBlockState()),
				"a face next to air, glass or water can be seen");
		check(!FarSight.faceOpen(stone, stone) && !FarSight.faceOpen(planks, Blocks.DIRT.defaultBlockState()), "a face against a solid block cannot");
		check(FarSight.faceOpen(lava, air) && !FarSight.faceOpen(lava, lava) && !FarSight.faceOpen(lava, stone), "lava shows only where it meets air");
		check(FarSight.noticeableDistance(FarSight.LARGE_CLUSTER) == FarSight.RANGE && FarSight.noticeableDistance(FarSight.LARGE_CLUSTER - 1) == FarSight.SMALL_SIGHT,
				"big clusters are noticed to full range, small ones only up close");
		check(FarSight.SMALL_SIGHT == 24 && FarSight.NAMED_STRUCTURE_DISTANCE == 64 && FarSight.RANGE == 256, "documented sight distances");
	}

	private static FarSight.Found found(int x, int y, int z) {
		return new FarSight.Found(net.minecraft.core.BlockPos.asLong(x, y, z), Blocks.OAK_PLANKS.defaultBlockState(), FarSight.BUILT, 1);
	}

	private static double horizontal(FarSight.Found entry) {
		double x = net.minecraft.core.BlockPos.getX(entry.position()), z = net.minecraft.core.BlockPos.getZ(entry.position());
		return Math.sqrt(x * x + z * z);
	}

	private static void farSightClusters() {
		List<FarSight.Found> blocks = new ArrayList<>();
		for (int x = 100; x < 105; x++) for (int y = 64; y < 68; y++) blocks.add(found(x, y, 50));
		blocks.add(found(10, 64, 10));
		blocks.add(found(300, 64, 50));
		var clusters = FarSight.cluster(blocks, SightedFeaturesVerification::horizontal);
		check(clusters.size() == 3, "a house and two far-apart torches are three clusters: " + clusters.size());
		check(clusters.get(0).members().size() == 1 && clusters.get(1).members().size() == 20, "nearest cluster first; the house keeps all 20 blocks");
		check(net.minecraft.core.BlockPos.getX(clusters.get(1).members().getFirst().position()) == 100, "members are nearest first");
	}

	private static void farSightGeometry() {
		var box = new net.minecraft.world.level.levelgen.structure.BoundingBox(10, 60, 10, 19, 69, 19);
		check(FarSight.boxDistance(new Vec3(0.0D, 65.0D, 15.0D), box) == 10.0D, "distance to a box is to its nearest face");
		check(FarSight.boxDistance(new Vec3(15.0D, 65.0D, 15.0D), box) == 0.0D, "inside a box is distance 0");
		List<Vec3> points = FarSight.aimPoints(new Vec3(0.0D, 65.0D, 15.0D), box);
		check(points.size() == 3 && points.contains(new Vec3(15.0D, 65.0D, 15.0D)) && points.contains(new Vec3(15.0D, 69.5D, 15.0D))
				&& points.contains(new Vec3(10.5D, 65.0D, 15.0D)), "aim at the centre, the top and the side facing the eye: " + points);
		check(FarSight.aimPoints(new Vec3(30.0D, 65.0D, 30.0D), box).size() == 4, "from a corner both facing sides are aimed at");
	}

	/** Review fix: sizes and noticeability come only from seen blocks; one visible torch in front of a hidden house is small. */
	private static void seenPartsOnly() {
		List<FarSight.Found> house = new ArrayList<>();
		for (int x = 200; x < 205; x++) for (int y = 64; y < 68; y++) for (int z = 0; z < 5; z++) house.add(found(x, y, z));
		var cluster = FarSight.cluster(house, SightedFeaturesVerification::horizontal).getFirst();
		check(cluster.members().size() >= FarSight.LARGE_CLUSTER, "the fixture house has many exposed blocks");
		long torch = cluster.members().getFirst().position();
		FarSight.SeenPart one = FarSight.seenPart(cluster, member -> member.position() == torch, SightedFeaturesVerification::horizontal, 16);
		check(one.count() == 0, "one visible torch at 200 blocks is not reported because of hidden blocks behind it");
		FarSight.SeenPart front = FarSight.seenPart(cluster, member -> net.minecraft.core.BlockPos.getX(member.position()) == 200,
				SightedFeaturesVerification::horizontal, 32);
		check(front.count() >= FarSight.LARGE_CLUSTER && front.horizontalSize() >= 2 && front.horizontalSize() <= 5, "a visible front wall is reported at the size of its seen part: "
				+ front.count() + " seen, size " + front.horizontalSize());
		var near = FarSight.cluster(List.of(found(10, 64, 0), found(10, 65, 0)), SightedFeaturesVerification::horizontal).getFirst();
		check(FarSight.seenPart(near, member -> true, SightedFeaturesVerification::horizontal, 16).count() == 2, "up close one seen block is enough");
		check(FarSight.seenPart(near, member -> false, SightedFeaturesVerification::horizontal, 16).count() == 0, "nothing seen, nothing reported");
	}

	/** Review fix: a cluster keeps one key wherever it is seen from, so walking along a wall does not re-announce it. */
	private static void stableClusterKeys() {
		List<FarSight.Found> wall = new ArrayList<>();
		for (int x = 0; x < 64; x++) wall.add(found(x, 64, 100));
		long fromWest = FarSight.cluster(wall, entry -> Math.abs(net.minecraft.core.BlockPos.getX(entry.position()) - 0)).getFirst().seed();
		long fromEast = FarSight.cluster(wall, entry -> Math.abs(net.minecraft.core.BlockPos.getX(entry.position()) - 63)).getFirst().seed();
		check(fromWest == fromEast, "the seed of a 64-block wall is the same from either end");
		check(!FarSight.rowId("built@" + fromWest).equals(FarSight.rowId("built@" + (fromWest + 1))) && FarSight.rowId("x").length() <= 7,
				"row ids are short and tell clusters apart");
	}

	/** Review fix: a survey marks rows known without starting the 30 s passive window, and only unknown rows are new. */
	private static void surveysAcknowledge() {
		SightedFeatures.Announcements seen = new SightedFeatures.Announcements();
		Sample sample = new Sample(List.of(farRow(FarSight.Section.STRUCTURES, "minecraft:village_plains@1,1", "village", List.of(), 0, 30, new Cell(0, 64, 50))),
				List.of(), List.of());
		JsonObject survey = SightedFeatures.render(sample, Vec3.ZERO, 0.0F, seen, 100L, false, Map.of(), java.util.EnumSet.allOf(FarSight.Section.class));
		JsonObject row = survey.getAsJsonArray("structures").get(0).getAsJsonObject();
		check(row.get("new").getAsBoolean() && row.has("id"), "a survey shows an unknown village as new, with an id");
		check(SightedFeatures.toJson(sample, Vec3.ZERO, 0.0F, seen, 110L) == null, "the next passive update does not repeat a surveyed village");
		JsonObject again = SightedFeatures.render(sample, Vec3.ZERO, 0.0F, seen, 120L, false, Map.of(), java.util.EnumSet.allOf(FarSight.Section.class));
		check(!again.getAsJsonArray("structures").get(0).getAsJsonObject().has("new"), "a second survey does not call it new");
		check(SightedFeatures.toJson(sample, Vec3.ZERO, 0.0F, seen, 120L + SightedFeatures.NEW_STRUCTURE_TICKS + 1).getAsJsonArray("structures")
				.get(0).getAsJsonObject().get("new").getAsBoolean(), "out of sight for a minute, it is announced passively again");
		check(!SightedFeatures.toJson(sample, Vec3.ZERO, 0.0F, new SightedFeatures.Announcements(), 0L).getAsJsonArray("structures").get(0).getAsJsonObject().has("id"),
				"passive rows carry no id");
	}

	/** Review fix: fog limits sight with the eye in water or lava, as the 26.1 client draws it. */
	private static void fogRange() {
		check(FarSight.sightRange(false, false, false, 0.0F) == FarSight.RANGE, "in air the full range");
		check(FarSight.sightRange(true, false, false, 0.0F) == 1.0D && FarSight.sightRange(true, true, false, 0.0F) == 5.0D,
				"in lava 1 block, 5 with fire resistance");
		check(FarSight.sightRange(false, false, true, FarSight.waterVision(0)) == 24.0D, "just under water 24 blocks");
		check(Math.abs(FarSight.sightRange(false, false, true, FarSight.waterVision(100)) - 57.6D) < 0.01D, "after 5 s under water about 58 blocks");
		check(FarSight.sightRange(false, false, true, FarSight.waterVision(600)) == 96.0D, "after 30 s under water 96 blocks");
		FarSight.SightCache cache = new FarSight.SightCache();
		cache.waterVision(true, 0L);
		check(Math.abs(cache.waterVision(true, 100L) - 0.6F) < 0.001F, "the server follows the client's water vision timer");
		check(cache.waterVision(false, 105L) < 0.6F, "leaving the water fades it");
	}

	/** Review fix: passes, surveys and section reads are capped per level per tick, shared by all agents and searches. */
	private static void perTickBudgets() {
		FarSight.LevelIndex index = new FarSight.LevelIndex();
		int passes = 0, surveys = 0;
		for (int agent = 0; agent < 8; agent++) {
			if (index.tryPass(500L, false)) passes++;
			if (index.tryPass(500L, true)) surveys++;
		}
		check(passes == FarSight.PASSES_PER_TICK && surveys == FarSight.SURVEYS_PER_TICK, "8 agents on one tick: 2 passes and 1 survey run");
		check(index.tryPass(501L, false), "the next tick has budget again");
		check(!FarSight.searchable(Blocks.STONE) && !FarSight.searchable(Blocks.WATER) && FarSight.searchable(Blocks.DIAMOND_ORE),
				"terrain is not searchable; ores are");
		try {
			FarSight.surveyRequest(List.of("blocks:minecraft:stone"), List.of(), 4);
			throw new AssertionError("a stone search was accepted");
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException expected) {
			check("INVALID_INSPECTION".equals(expected.code()), "a survey refuses to search stone");
		}
		check(FarSight.Request.passive().cachedOnly().clips() == 0, "a survey over budget makes no new sight lines");
	}

	private static void surveyRequests() {
		FarSight.Request all = FarSight.surveyRequest(List.of(), List.of(), 4);
		check(all.sections().size() == FarSight.Section.values().length && all.searched().isEmpty() && all.clips() == FarSight.SURVEY_CLIPS,
				"no include means every section");
		FarSight.Request some = FarSight.surveyRequest(List.of("structures", "blocks:minecraft:diamond_ore"), List.of(), 2);
		check(some.sections().equals(java.util.EnumSet.of(FarSight.Section.STRUCTURES, FarSight.Section.BLOCKS))
				&& some.searched().equals(List.of(Blocks.DIAMOND_ORE)), "include picks sections and blocks:<id> searches");
		FarSight.Request less = FarSight.surveyRequest(List.of(), List.of("biomes", "caves"), 4);
		check(!less.wants(FarSight.Section.BIOMES) && !less.wants(FarSight.Section.CAVES) && less.wants(FarSight.Section.BUILT), "exclude drops sections");
		for (var bad : List.of(List.of("mineshafts"), List.of("blocks:minecraft:not_a_block"))) {
			try {
				FarSight.surveyRequest(bad, List.of(), 4);
				throw new AssertionError("survey accepted " + bad);
			} catch (dev.agaminggod.arenaagents.agent.AgentDomainException expected) {
				check("INVALID_INSPECTION".equals(expected.code()), "survey rejects " + bad);
			}
		}
		check(FarSight.Request.passive().clips() == FarSight.PASSIVE_CLIPS && FarSight.PASSIVE_CLIPS < FarSight.SURVEY_CLIPS,
				"passive passes use the smaller line-of-sight budget");
	}

	private static void attention() {
		JsonObject previous = new JsonObject();
		JsonObject current = new JsonObject();
		JsonObject sighted = new JsonObject();
		JsonArray structures = new JsonArray();
		JsonObject row = new JsonObject();
		row.addProperty("structure", "village");
		structures.add(row);
		sighted.add("structures", structures);
		current.add("sighted", sighted);
		check(!AttentionSignalPolicy.newStructureSighted(current), "a known structure does not wake the model");
		check(!AttentionSignalPolicy.changedFacts(previous, current).contains("sighted"), "no attention without a new structure");
		row.addProperty("new", true);
		check(AttentionSignalPolicy.newStructureSighted(current), "a new structure is detected");
		check(AttentionSignalPolicy.changedFacts(previous, current).contains("sighted"), "a new structure requests attention");
		for (String section : List.of("built", "poi", "biomes", "blocks")) {
			JsonObject quiet = new JsonObject();
			JsonObject rows = new JsonObject();
			JsonArray array = new JsonArray();
			JsonObject fresh = new JsonObject();
			fresh.addProperty("new", true);
			array.add(fresh);
			rows.add(section, array);
			quiet.add("sighted", rows);
			boolean wakes = section.equals("built") || section.equals("poi");
			check(AttentionSignalPolicy.newStructureSighted(quiet) == wakes,
					"a new " + section + " row " + (wakes ? "wakes the model" : "rides along without waking it"));
		}
	}

	private static void naturalTerrain() {
		check(SightedFeatures.natural(Blocks.STONE.defaultBlockState()) && SightedFeatures.natural(Blocks.DEEPSLATE.defaultBlockState()), "stone is natural");
		check(SightedFeatures.natural(Blocks.WATER.defaultBlockState()) && SightedFeatures.natural(Blocks.SAND.defaultBlockState()), "water and sand are natural");
		check(SightedFeatures.natural(Blocks.IRON_ORE.defaultBlockState()), "ore is part of a cave wall");
		check(!SightedFeatures.natural(Blocks.OAK_PLANKS.defaultBlockState()) && !SightedFeatures.natural(Blocks.COBBLESTONE.defaultBlockState()),
				"planks and cobblestone are not cave walls");
		check(SightedFeatures.natural(Blocks.SANDSTONE.defaultBlockState()) && SightedFeatures.natural(Blocks.OBSIDIAN.defaultBlockState())
				&& SightedFeatures.natural(Blocks.SNOW_BLOCK.defaultBlockState()) && SightedFeatures.natural(Blocks.MAGMA_BLOCK.defaultBlockState()),
				"sandstone, obsidian, snow and magma line natural caves");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}
}
