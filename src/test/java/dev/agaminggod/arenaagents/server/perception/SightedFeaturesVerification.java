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
import net.minecraft.world.level.block.Blocks;
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
		structuresKeepNearestPerStart();
		structureLabels();
		onlyBuiltBlocksIdentifyStructures();
		sightedRecomputeIsThrottled();
		structuresAreNewOncePerMinute();
		rendering();
		attention();
		naturalTerrain();
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

	private static void structuresKeepNearestPerStart() {
		List<Sighting> rows = SightedFeatures.nearestPerKey(List.of(
				new Sighting("minecraft:shipwreck@1,2", "shipwreck", new Cell(30, 60, 0), 30, 0),
				new Sighting("minecraft:village_plains@5,5", "village", new Cell(80, 70, 0), 80, 0),
				new Sighting("minecraft:shipwreck@1,2", "shipwreck", new Cell(25, 60, 0), 25, 0)), SightedFeatures.MAX_STRUCTURES);
		check(rows.size() == 2, "one row per structure start");
		check(rows.getFirst().cell().equals(new Cell(25, 60, 0)), "the nearest seen block represents the structure");
		check("village".equals(rows.get(1).label()), "structures are ordered nearest first");
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

	private static void structuresAreNewOncePerMinute() {
		Map<String, Long> seen = new HashMap<>();
		check(SightedFeatures.markSeen(seen, "minecraft:shipwreck@1,2", 100), "first sighting is new");
		check(!SightedFeatures.markSeen(seen, "minecraft:shipwreck@1,2", 700), "a second glance is not new");
		check(!SightedFeatures.markSeen(seen, "minecraft:shipwreck@1,2", 1_800), "steady sighting stays not new");
		check(SightedFeatures.markSeen(seen, "minecraft:shipwreck@1,2", 1_800 + SightedFeatures.NEW_STRUCTURE_TICKS + 1), "seen again after a minute away is new");
		for (int index = 0; index < SightedFeatures.MAX_REMEMBERED_STRUCTURES + 5; index++) SightedFeatures.markSeen(seen, "s" + index, 5_000 + index);
		check(seen.size() <= SightedFeatures.MAX_REMEMBERED_STRUCTURES, "the latch is bounded");
	}

	private static void rendering() {
		check(SightedFeatures.toJson(Sample.EMPTY, Vec3.ZERO, 0.0F, key -> true) == null, "nothing seen: no field");
		Sample sample = new Sample(
				List.of(new Sighting("minecraft:shipwreck@1,2", "shipwreck", new Cell(0, 60, 40), 40.4, 0)),
				List.of(new Sighting("cave@0,0,1", null, new Cell(10, 0, 0), 10.2, 44)),
				List.of(new Sighting("minecraft:iron_ore@1,0,0", "minecraft:deepslate_iron_ore", new Cell(1, 0, 0), 1.2, 5)));
		JsonObject json = SightedFeatures.toJson(sample, new Vec3(0.5D, 0.0D, 0.5D), 0.0F, key -> key.startsWith("minecraft:shipwreck"));
		JsonObject structure = json.getAsJsonArray("structures").get(0).getAsJsonObject();
		check("shipwreck".equals(structure.get("structure").getAsString()) && structure.get("new").getAsBoolean(), "structure row names the structure and marks it new");
		check(structure.get("distance").getAsLong() == 40 && structure.get("bearing").getAsLong() == 0, "straight ahead at 40 blocks");
		JsonObject cave = json.getAsJsonArray("caves").get(0).getAsJsonObject();
		check(cave.get("air").getAsInt() == 44 && cave.get("bearing").getAsLong() == -90, "a cave to the east is 90 degrees off a south-facing view, signed like landmarks");
		JsonObject vein = json.getAsJsonArray("veins").get(0).getAsJsonObject();
		check(vein.get("visible").getAsInt() == 5 && "minecraft:deepslate_iron_ore".equals(vein.get("blockId").getAsString()), "vein row keeps the seen block id and count");
		JsonObject repeat = SightedFeatures.toJson(sample, Vec3.ZERO, 0.0F, key -> false);
		check(!repeat.getAsJsonArray("structures").get(0).getAsJsonObject().has("new"), "a known structure carries no new flag");
		check(json.toString().length() < 400, "three rows stay compact: " + json.toString().length() + " characters");
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
