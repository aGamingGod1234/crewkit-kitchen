package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;

/** Hearing: client sampling math, bearing, dedupe, expiry, ambient lava emulation and the lava attention edge. */
public final class HearingPerceptionVerification {
	private static int checks;

	private HearingPerceptionVerification() { }

	public static int verify() {
		checks = 0;
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		sampling();
		bearing();
		dedupeAndExpiry();
		salience();
		ambientClassification();
		ambientLava();
		attention();
		mood();
		return checks;
	}

	private static void sampling() {
		double origin = HearingPerception.sampleRate(0, 0, 0);
		check(Math.abs(origin - 667.0D * (Math.pow(1.0D / 16, 3) + Math.pow(1.0D / 32, 3))) < 1e-12, "origin rate is 667 triangular samples at r16 and r32");
		check(HearingPerception.sampleRate(3, -2, 1) == HearingPerception.sampleRate(-3, 2, -1), "sampling is symmetric");
		check(HearingPerception.sampleRate(16, 0, 0) > 0 && HearingPerception.sampleRate(16, 0, 0) < HearingPerception.sampleRate(15, 0, 0), "beyond 15 only the 32-radius pass reaches");
		check(HearingPerception.sampleRate(32, 0, 0) == 0.0D, "nothing is sampled 32 blocks away");
		check(Math.abs(HearingPerception.loudness(0.3F, 8, 16) - 0.15D) < 1e-6 && HearingPerception.loudness(2.0F, 16, 16) == 0.0D, "linear attenuation clamps volume and range");
		check(HearingPerception.range(0.3F) == 16.0D && HearingPerception.range(2.0F) == 32.0D, "range grows only above volume one");
	}

	private static void bearing() {
		check(HearingPerception.direction(0, 5, 0).equals("front"), "yaw 0 faces +z");
		check(HearingPerception.direction(5, 0, 0).equals("left"), "east is left when facing south");
		check(HearingPerception.direction(0, -5, 0).equals("back"), "behind");
		check(HearingPerception.direction(-4, -4, 0).equals("back_right"), "diagonal sector");
		check(HearingPerception.direction(0, 5, 90).equals("left"), "bearing follows the current yaw");
		check(HearingPerception.direction(0.3, 0.2, 0).equals("here"), "directly above or below has no horizontal direction");
		check(HearingPerception.elevation(-1.62).equals("level") && HearingPerception.elevation(-2.62).equals("below")
				&& HearingPerception.elevation(0.38).equals("level") && HearingPerception.elevation(1.38).equals("above"),
				"elevation bands: own floor is level, one block lower is below, a head-height ledge is above");
		check(HearingPerception.shortSound("minecraft:block.lava.pop").equals("block.lava.pop")
				&& HearingPerception.shortSound("mod:x").equals("mod:x"), "only the vanilla namespace is shortened");
	}

	private static void dedupeAndExpiry() {
		HearingPerception.Listener listener = new HearingPerception.Listener(1).at("minecraft:overworld");
		for (int tick = 0; tick < 3; tick++) {
			listener.record("zombie#7", "minecraft:entity.zombie.ambient", "minecraft:zombie", -6, 64, -6, 0.4, 3, tick * 10, 1);
		}
		listener.record("door@1", "minecraft:block.wooden_door.open", null, 3, 64, 0, 0.5, 1, 20, 1);
		JsonArray top = listener.top(0, 65.62, 0, 0, 20, 6);
		check(top.size() == 2, "repeats from one source merge into one entry");
		JsonObject zombie = top.get(0).getAsJsonObject();
		check(zombie.get("sound").getAsString().equals("entity.zombie.ambient") && zombie.get("count").getAsInt() == 3, "merged entry counts repeats");
		check(!zombie.has("source"), "source is omitted when the sound names it");
		check(zombie.get("direction").getAsString().equals("back_right") && zombie.get("distance").getAsInt() == 9, "zombie heard behind at ~9 blocks");
		check(!zombie.has("x") && !zombie.has("y") && !zombie.has("z"), "no coordinates leak");
		HearingPerception.Listener pocket = new HearingPerception.Listener(1).at("minecraft:overworld");
		pocket.record("pop-a", HearingPerception.LAVA_POP, null, -8, 62, 1, 0.2, 3, 20, 2);
		pocket.record("pop-b", HearingPerception.LAVA_POP, null, -9, 62, 0, 0.2, 3, 20, 1);
		JsonArray lava = new JsonArray();
		for (var value : pocket.top(0, 65.62, 0, 0, 20, 6)) if (value.getAsJsonObject().get("sound").getAsString().contains("lava")) lava.add(value);
		check(lava.size() == 1 && lava.get(0).getAsJsonObject().get("count").getAsInt() == 3 && lava.get(0).getAsJsonObject().get("distance").getAsInt() == 9,
				"one pocket heard from two cells reads as one entry with the nearest distance");
		listener.expire(121);
		check(listener.top(0, 65.62, 0, 0, 121, 6).isEmpty(), "sounds expire after the 5 s window");
		listener.record("lava", HearingPerception.LAVA_POP, null, 1, 64, 0, 0.2, 3, 200, 1);
		listener.expire(700);
		check(listener.top(0, 65.62, 0, 0, 700, 6).size() == 1, "lava is remembered for 30 s");
		check(listener.top(0, 65.62, 0, 0, 700, 6).get(0).getAsJsonObject().get("secondsAgo").getAsInt() == 25, "a remembered sound reports its age");
		listener.expire(801);
		check(listener.top(0, 65.62, 0, 0, 801, 6).isEmpty(), "lava memory expires too");
		listener.record("lava", HearingPerception.LAVA_POP, null, 1, 64, 0, 0.2, 3, 900, 1);
		listener.at("minecraft:the_nether");
		check(listener.top(0, 65.62, 0, 0, 900, 6).isEmpty(), "changing dimension clears hearing");
	}

	private static void salience() {
		HearingPerception.Listener listener = new HearingPerception.Listener(1).at("minecraft:overworld");
		for (int index = 0; index < 10; index++) {
			listener.record("rain" + index, "minecraft:weather.rain_" + index, null, index, 70, 0, 0.9, HearingPerception.weight("minecraft:weather.rain", "weather"), 5, 1);
		}
		listener.record("skeleton", "minecraft:entity.skeleton.ambient", null, -8, 64, 0, 0.3, HearingPerception.weight("minecraft:entity.skeleton.ambient", "hostile"), 5, 1);
		JsonArray top = listener.top(0, 65.62, 0, 0, 5, HearingPerception.MAX_REPORTED);
		check(top.size() == HearingPerception.MAX_REPORTED, "at most six sounds are reported");
		check(top.get(0).getAsJsonObject().get("sound").getAsString().equals("entity.skeleton.ambient"), "a quiet hostile outranks loud weather");
		check(HearingPerception.weight("minecraft:entity.generic.explode", "block") == 3.0D
				&& HearingPerception.weight("minecraft:entity.tnt.primed", "block") == 3.0D, "fuses and explosions are salient");
	}

	private static Map<Long, BlockState> world() { return new HashMap<>(); }

	private static BlockState at(Map<Long, BlockState> world, int x, int y, int z) {
		return world.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
	}

	private static List<HearingPerception.AmbientSource> classify(Map<Long, BlockState> world) {
		ArrayList<HearingPerception.AmbientSource> out = new ArrayList<>();
		for (var entry : world.entrySet()) {
			BlockPos pos = BlockPos.of(entry.getKey());
			if (HearingPerception.mayEmit(entry.getValue())) {
				HearingPerception.classify(entry.getValue(), pos.getX(), pos.getY(), pos.getZ(), (x, y, z) -> at(world, x, y, z), false, false, out);
			}
		}
		return out;
	}

	private static void ambientClassification() {
		Map<Long, BlockState> world = world();
		world.put(BlockPos.asLong(0, 63, 0), Blocks.LAVA.defaultBlockState());
		world.put(BlockPos.asLong(5, 63, 0), Blocks.LAVA.defaultBlockState());
		world.put(BlockPos.asLong(5, 64, 0), Blocks.STONE.defaultBlockState());
		List<HearingPerception.AmbientSource> lava = classify(world);
		check(lava.size() == 2 && lava.stream().allMatch(source -> source.x() == 0), "only lava open to air pops and rumbles");
		check(lava.stream().anyMatch(source -> source.sound().equals(HearingPerception.LAVA_POP) && source.chance() == 0.01D && source.soundY() == 64.0D),
				"lava pop: 1/100 per sample, at the surface");
		Map<Long, BlockState> water = world();
		water.put(BlockPos.asLong(0, 64, 0), Blocks.WATER.defaultBlockState());
		water.put(BlockPos.asLong(1, 64, 0), Fluids.FLOWING_WATER.getFlowing(5, false).createLegacyBlock());
		water.put(BlockPos.asLong(2, 64, 0), Fluids.FLOWING_WATER.getFlowing(7, true).createLegacyBlock());
		List<HearingPerception.AmbientSource> flowing = classify(water);
		check(flowing.size() == 1 && flowing.get(0).x() == 1 && flowing.get(0).sound().equals(HearingPerception.WATER_AMBIENT),
				"only flowing, non-falling water murmurs");
		check(Fluids.FLOWING_WATER.getFlowing(7, true).getValue(FlowingFluid.FALLING), "falling water fixture is falling");
		Map<Long, BlockState> drip = world();
		BlockState tip = Blocks.POINTED_DRIPSTONE.defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN)
				.setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP);
		BlockState base = tip.setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.BASE);
		drip.put(BlockPos.asLong(0, 70, 0), tip);
		drip.put(BlockPos.asLong(0, 71, 0), base);
		drip.put(BlockPos.asLong(0, 72, 0), Blocks.STONE.defaultBlockState());
		drip.put(BlockPos.asLong(0, 73, 0), Blocks.LAVA.defaultBlockState());
		drip.put(BlockPos.asLong(0, 73 + 1, 0), Blocks.STONE.defaultBlockState());
		drip.put(BlockPos.asLong(0, 64, 0), Blocks.STONE.defaultBlockState());
		// Root is the dripstone at 71; the block above it is stone, so this tip only drips dry (2%) water.
		List<HearingPerception.AmbientSource> dry = classify(drip);
		check(dry.size() == 1 && dry.get(0).sound().equals(HearingPerception.DRIP_WATER) && dry.get(0).chance() == 0.02D
				&& dry.get(0).soundY() == 65.0D, "dry dripstone drips rarely and sounds where the drop lands");
		drip.put(BlockPos.asLong(0, 72, 0), Blocks.LAVA.defaultBlockState());
		List<HearingPerception.AmbientSource> wet = classify(drip).stream().filter(source -> source.sound().contains("drip")).toList();
		check(wet.size() == 1 && wet.get(0).sound().equals(HearingPerception.DRIP_LAVA) && wet.get(0).chance() == 0.12D,
				"lava above the root drips lava 12% of samples");
		Map<Long, BlockState> misc = world();
		misc.put(BlockPos.asLong(0, 64, 0), Blocks.FIRE.defaultBlockState());
		misc.put(BlockPos.asLong(1, 64, 0), Blocks.CAMPFIRE.defaultBlockState());
		misc.put(BlockPos.asLong(2, 64, 0), Blocks.NETHER_PORTAL.defaultBlockState());
		misc.put(BlockPos.asLong(3, 64, 0), Blocks.STONE.defaultBlockState());
		check(classify(misc).size() == 3 && !HearingPerception.mayEmit(Blocks.STONE.defaultBlockState()), "fire, lit campfire and portal emit; stone does not");
	}

	private static void ambientLava() {
		// Lucas's case: strip mining with feet at y=64 and two small lava pools in pockets just below and beside the tunnel.
		Map<Long, BlockState> world = world();
		int[][] pools = { { 2, 62, 0 }, { 2, 62, 1 }, { 3, 62, 0 }, { 3, 62, 1 }, { 2, 62, -1 }, { 3, 62, -1 },
				{ -2, 62, 3 }, { -3, 62, 3 }, { -2, 62, 4 }, { -3, 62, 4 }, { -2, 62, 5 }, { -3, 62, 5 } };
		for (int[] lava : pools) world.put(BlockPos.asLong(lava[0], lava[1], lava[2]), Blocks.LAVA.defaultBlockState());
		HearingPerception.Listener listener = new HearingPerception.Listener(1).at("minecraft:overworld");
		listener.scanned(0, 64, 0, 0, 0, classify(world));
		double expected = 0;
		for (HearingPerception.AmbientSource source : classify(world)) expected += source.chance() * HearingPerception.sampleRate(source.x(), source.y() - 64, source.z());
		listener.accumulate(0, 0.5, 65.62, 0.5);
		for (int tick = 10; tick <= 100; tick += 10) listener.accumulate(tick, 0.5, 65.62, 0.5);
		JsonArray heard = listener.top(0.5, 65.62, 0.5, 0, 100, 6);
		int total = 0;
		for (var value : heard) total += value.getAsJsonObject().has("count") ? value.getAsJsonObject().get("count").getAsInt() : 1;
		check(expected * 100 >= 2.0D, "two small pools beside the tunnel are audible within 5 s");
		check(!heard.isEmpty() && heard.get(0).getAsJsonObject().get("sound").getAsString().startsWith("block.lava"), "the agent hears lava");
		check(Math.abs(total - Math.floor(expected * 100)) <= 1, "heard count matches the client's expected rate: " + total + " vs " + expected * 100);
		JsonObject first = heard.get(0).getAsJsonObject();
		check(first.get("distance").getAsInt() <= 4 && first.get("elevation").getAsString().equals("below"), "lava is reported close and below the eyes");
		check(listener.lavaHeardNear(1.5, 63.5, 0.5, 2.5, 100), "a floor block over the pool is near heard lava");
		check(!listener.lavaHeardNear(10.5, 63.5, 10.5, 2.5, 100), "a distant mining target is not");

		Map<Long, BlockState> far = world();
		far.put(BlockPos.asLong(14, 60, 0), Blocks.LAVA.defaultBlockState());
		HearingPerception.Listener distant = new HearingPerception.Listener(1).at("minecraft:overworld");
		distant.scanned(0, 64, 0, 0, 0, classify(far));
		distant.accumulate(0, 0.5, 65.62, 0.5);
		distant.accumulate(100, 0.5, 65.62, 0.5);
		check(distant.top(0.5, 65.62, 0.5, 0, 100, 6).isEmpty(), "one lava block 14 blocks away stays rarely audible");
		distant.accumulate(10_000, 0.5, 65.62, 0.5);
		check(distant.top(0.5, 65.62, 0.5, 0, 10_000, 6).isEmpty(), "a long gap credits at most one window of sounds");

		check(listener.needsScan(BlockPos.asLong(0, 64, 0), 0, 10) == false, "a still listener reuses its scan");
		check(listener.needsScan(BlockPos.asLong(1, 64, 0), 0, 10) == false, "moving rescans at most once per second");
		check(listener.needsScan(BlockPos.asLong(1, 64, 0), 0, 20), "moving rescans after a second");
		check(listener.needsScan(BlockPos.asLong(0, 64, 0), 0, 100), "a still listener rescans every 5 s");
	}

	private static void attention() {
		JsonObject before = new JsonObject();
		JsonObject after = new JsonObject();
		JsonArray heard = new JsonArray();
		JsonObject lava = new JsonObject();
		lava.addProperty("sound", "block.lava.pop");
		heard.add(lava);
		after.add("heard", heard);
		check(AttentionSignalPolicy.changedFacts(before, after).contains("heard"), "first heard lava requests attention");
		check(!AttentionSignalPolicy.changedFacts(after, after).contains("heard"), "lava still heard is not a new edge");
		JsonObject zombie = new JsonObject();
		zombie.addProperty("sound", "entity.zombie.ambient");
		JsonArray other = new JsonArray();
		other.add(zombie);
		JsonObject mob = new JsonObject();
		mob.add("heard", other);
		check(!AttentionSignalPolicy.changedFacts(before, mob).contains("heard"), "ordinary sounds do not add a hearing edge");
	}

	private static void mood() {
		HearingPerception.Listener listener = new HearingPerception.Listener(1);
		int played = 0;
		for (int tick = 0; tick < 5_980; tick += 20) if (listener.moodStep(0, 0, 6_000, 20)) played++;
		check(played == 0, "cave mood needs about 6000 dark ticks");
		for (int step = 0; step < 2; step++) if (listener.moodStep(0, 0, 6_000, 20)) played++;
		check(played == 1, "then the cave sound plays once and resets");
		check(!listener.moodStep(15, 0, 6_000, 20), "daylight drains moodiness");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		checks++;
	}
}
