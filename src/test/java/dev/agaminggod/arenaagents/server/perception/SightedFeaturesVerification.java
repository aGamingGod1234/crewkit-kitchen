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

		// A 5x4x5 natural room 40 blocks away is a cave when roofed, and not when open to the sky.
		Map<Cell, String> room = new HashMap<>();
		for (int x = 38; x <= 42; x++) for (int y = 0; y <= 3; y++) for (int z = -2; z <= 2; z++) room.put(new Cell(x, y, z), "air");
		Cell inside = new Cell(40, 1, 0);
		List<Sighting> cave = SightedFeatures.caves(List.of(inside, new Cell(41, 1, 1)), cell -> true, cell -> true,
				cell -> "air".equals(room.get(cell)), cell -> true, FROM_ORIGIN);
		check(cave.size() == 1, "two openings of one cave make one row");
		check(cave.getFirst().count() >= SightedFeatures.CAVE_MIN_AIR, "the row carries its open-air count");
		check(SightedFeatures.caves(List.of(inside), cell -> false, cell -> true, cell -> "air".equals(room.get(cell)), cell -> true, FROM_ORIGIN).isEmpty(),
				"an unroofed hollow (valley, ravine floor) is not reported as a cave");
		check(SightedFeatures.caves(List.of(inside), cell -> cell.x() == 40, cell -> true, cell -> "air".equals(room.get(cell)), cell -> true, FROM_ORIGIN).isEmpty(),
				"a ledge over open air (a hillside overhang) is not a cave");
		check(SightedFeatures.caves(List.of(inside), cell -> true, cell -> true, cell -> "air".equals(room.get(cell)), cell -> false, FROM_ORIGIN).isEmpty(),
				"a roofed room with built walls is a building, not a cave");
		check(SightedFeatures.caves(List.of(inside, new Cell(38, 1, 2), new Cell(42, 2, -2)), cell -> true, cell -> true, cell -> "air".equals(room.get(cell)), cell -> true, FROM_ORIGIN).size() == 1,
				"openings across one room stay one row");
		check(SightedFeatures.caves(List.of(inside), cell -> true, cell -> false, cell -> "air".equals(room.get(cell)), cell -> true, FROM_ORIGIN).isEmpty(),
				"a sky-lit opening is not a cave");
		check(!SightedFeatures.caveSpace(true, SightedFeatures.CAVE_MIN_AIR - 1, 100, 100), "too little air is not a cave");

		List<Cell> many = new ArrayList<>();
		Map<Cell, String> big = new HashMap<>();
		for (int x = 0; x < 64; x++) for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) big.put(new Cell(x, y, z), "air");
		for (int x = 2; x < 64; x += 12) many.add(new Cell(x, 1, 1));
		check(SightedFeatures.caves(many, cell -> true, cell -> true, cell -> "air".equals(big.get(cell)), cell -> true, FROM_ORIGIN).size()
				== SightedFeatures.MAX_CAVES, "cave rows are capped");
	}

	private static void structuresKeepNearestPerStart() {
		List<Sighting> rows = SightedFeatures.nearestPerKey(List.of(
				new Sighting("minecraft:shipwreck@1,2", "minecraft:shipwreck", new Cell(30, 60, 0), 30, 0),
				new Sighting("minecraft:village_plains@5,5", "minecraft:village_plains", new Cell(80, 70, 0), 80, 0),
				new Sighting("minecraft:shipwreck@1,2", "minecraft:shipwreck", new Cell(25, 60, 0), 25, 0)), SightedFeatures.MAX_STRUCTURES);
		check(rows.size() == 2, "one row per structure start");
		check(rows.getFirst().cell().equals(new Cell(25, 60, 0)), "the nearest seen block represents the structure");
		check("minecraft:village_plains".equals(rows.get(1).label()), "structures are ordered nearest first");
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
				List.of(new Sighting("minecraft:shipwreck@1,2", "minecraft:shipwreck", new Cell(0, 60, 40), 40.4, 0)),
				List.of(new Sighting("cave@0,0,1", null, new Cell(10, 0, 0), 10.2, 44)),
				List.of(new Sighting("minecraft:iron_ore@1,0,0", "minecraft:deepslate_iron_ore", new Cell(1, 0, 0), 1.2, 5)));
		JsonObject json = SightedFeatures.toJson(sample, new Vec3(0.5D, 0.0D, 0.5D), 0.0F, key -> key.startsWith("minecraft:shipwreck"));
		JsonObject structure = json.getAsJsonArray("structures").get(0).getAsJsonObject();
		check("minecraft:shipwreck".equals(structure.get("structure").getAsString()) && structure.get("new").getAsBoolean(), "structure row names the structure and marks it new");
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
		row.addProperty("structure", "minecraft:village_plains");
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
		check(!SightedFeatures.natural(Blocks.OAK_PLANKS.defaultBlockState()) && !SightedFeatures.natural(Blocks.OBSIDIAN.defaultBlockState())
				&& !SightedFeatures.natural(Blocks.COBBLESTONE.defaultBlockState()), "planks, obsidian and cobblestone identify built structures");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}
}
