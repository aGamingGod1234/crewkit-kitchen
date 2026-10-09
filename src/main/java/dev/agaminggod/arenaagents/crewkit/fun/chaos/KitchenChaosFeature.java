package dev.agaminggod.arenaagents.crewkit.fun.chaos;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplay;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplays;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSchedule;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Busy-kitchen ambience on the stove (x 12..14, z=4, cooking clearance at y 2..3): two pots and a pan
 * that rattle and bubble with each Reap call, steam on quotes, a pass bell, flying ingredients on
 * "think", a pan clatter on sold_out. Heat builds with activity and decays; "completed" calms it all.
 * Particles and display entities only; no world blocks are touched.
 */
public final class KitchenChaosFeature implements CrewkitFeature {
	private static final String TAG = "ck_chaos";
	private static final String[] PROP_ITEMS = {"minecraft:cauldron", "minecraft:heavy_weighted_pressure_plate", "minecraft:cauldron"};
	private static final String[] INGREDIENTS = {"minecraft:carrot", "minecraft:potato", "minecraft:egg"};
	private static final float PROP_SCALE = 2.0f;

	private final Random random = new Random();
	private final List<CrewkitDisplay> props = new ArrayList<>();
	private boolean calm;
	private double heat;
	private int lastDingTick = -100;

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		try {
			handle(server, event, data == null ? new JsonObject() : data);
		} catch (RuntimeException e) {
			// Ambience must never break the run.
		}
	}

	private void handle(MinecraftServer server, String event, JsonObject data) {
		switch (event) {
			case "reset" -> reset(server);
			case "brief" -> {
				calm = false;
				ensureProps(server);
			}
			case "quote" -> quoteBack(server);
			case "completed" -> finale(server);
			case "activity" -> activity(server, data);
			default -> {}
		}
	}

	private void activity(MinecraftServer server, JsonObject data) {
		if (calm) return;
		ensureProps(server);
		String kind = str(data, "kind");
		String result = str(data, "result");
		heat = Math.min(10, heat + 1);
		sizzle(server);
		if ("think".equals(kind)) {
			flyIngredient(server);
			return;
		}
		if ("pending".equals(result)) {
			rattle(server);
			bubble(server, 4 + (int) heat);
		} else if ("sold_out".equals(result)) {
			clatter(server);
		} else if ("quote".equals(kind) && ("ok".equals(result) || "over".equals(result))) {
			quoteBack(server);
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		if (calm || props.isEmpty() || heat <= 0.05) return;
		heat = Math.max(0, heat - 0.01);
		// Idle simmer: rarer and smaller as the heat fades.
		int every = Math.max(6, 40 - (int) (heat * 3.5));
		if (server.getTickCount() % every != 0) return;
		try {
			Vec3 pot = potPos(random.nextBoolean() ? 0 : 2);
			particle(server, "minecraft:bubble_pop", pot.add(0, 0.35, 0), 0.12, 0.03, 0.12, 0.01, 1 + (int) (heat / 3));
			if (heat > 5) particle(server, "minecraft:cloud", pot.add(0, 0.5, 0), 0.05, 0.05, 0.05, 0.01, 1);
		} catch (RuntimeException ignored) {
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		try {
			CrewkitDisplays.killTag(server, TAG);
		} catch (RuntimeException ignored) {
		}
		props.clear();
		calm = false;
		heat = 0;
		lastDingTick = -100;
	}

	// --- props ---------------------------------------------------------------------------------

	private void ensureProps(MinecraftServer server) {
		if (!props.isEmpty() && props.get(0).alive()) return;
		props.clear();
		CrewkitDisplays.killTag(server, TAG);
		for (int i = 0; i < PROP_ITEMS.length; i++) {
			props.add(CrewkitDisplays.item(server, potPos(i), TAG, PROP_ITEMS[i], PROP_SCALE, "ground", ""));
		}
	}

	/** Prop i sits on stove cell x=12+i, in the cleared cooking space at y=2 over the stove top. */
	private static Vec3 potPos(int i) {
		var o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + 12.5 + i, o.getY() + 2.0, o.getZ() + 4.55);
	}

	private static Vec3 rel(double x, double y, double z) {
		var o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + x, o.getY() + y, o.getZ() + z);
	}

	// --- reactions -----------------------------------------------------------------------------

	private void rattle(MinecraftServer server) {
		float amp = (float) (0.08 + heat * 0.015);
		for (CrewkitDisplay prop : props) {
			float a = (random.nextFloat() * 2 - 1) * amp;
			prop.transform(PROP_SCALE, PROP_SCALE, PROP_SCALE, 0, 0.03f, 0, a, 2);
			CrewkitSchedule.after(3, () -> prop.transform(PROP_SCALE, PROP_SCALE, PROP_SCALE, 0, 0.01f, 0, -a * 0.7f, 2));
			CrewkitSchedule.after(6, () -> prop.transform(PROP_SCALE, PROP_SCALE, PROP_SCALE, 0, 0, 0, 0, 3));
		}
		CrewkitSounds.play(server, potPos(1), "minecraft:block.chain.hit", 0.25f, 1.6f + random.nextFloat() * 0.3f);
	}

	private void bubble(MinecraftServer server, int count) {
		particle(server, "minecraft:bubble_pop", potPos(0).add(0, 0.35, 0), 0.12, 0.04, 0.12, 0.02, count);
		particle(server, "minecraft:bubble_pop", potPos(2).add(0, 0.35, 0), 0.12, 0.04, 0.12, 0.02, count);
		particle(server, "minecraft:splash", potPos(1).add(0, 0.25, 0), 0.15, 0.02, 0.1, 0.05, Math.max(2, count / 2));
		CrewkitSounds.play(server, potPos(0), "minecraft:block.bubble_column.bubble_pop", 0.4f, 1.0f + random.nextFloat() * 0.4f);
	}

	private void sizzle(MinecraftServer server) {
		CrewkitSounds.play(server, potPos(1), "minecraft:block.fire.extinguish", 0.12f, 1.4f + random.nextFloat() * 0.4f);
	}

	private void steam(MinecraftServer server, int count, double speed) {
		for (int i = 0; i < PROP_ITEMS.length; i += 2) {
			// count 0 makes dx/dy/dz a direction: a column of cloud rising out of each pot.
			Vec3 p = potPos(i).add(0, 0.45, 0);
			for (int n = 0; n < count; n++) {
				double jx = (random.nextDouble() - 0.5) * 0.25;
				double jz = (random.nextDouble() - 0.5) * 0.25;
				particle(server, "minecraft:cloud", p.add(jx, 0, jz), jx * 0.3, 1.0, jz * 0.3, speed, 0);
			}
		}
	}

	private void quoteBack(MinecraftServer server) {
		int now = server.getTickCount();
		if (now - lastDingTick < 10) return;
		lastDingTick = now;
		if (!calm) {
			ensureProps(server);
			steam(server, 5, 0.12);
			CrewkitSounds.play(server, potPos(1), "minecraft:block.fire.extinguish", 0.3f, 1.2f);
		}
		Vec3 pass = rel(13.5, 2.3, 8.5);
		CrewkitSounds.play(server, pass, "minecraft:block.note_block.bell", 0.8f, 1.5f);
		particle(server, "minecraft:note", pass.add(0, 0.4, 0), 0, 0, 0, 0, 1);
	}

	private void flyIngredient(MinecraftServer server) {
		String item = INGREDIENTS[random.nextInt(INGREDIENTS.length)];
		double x = 4.5 + random.nextInt(6);
		Vec3 start = rel(x, 2.05, 4.6);
		Vec3 apex = rel(x + 0.6, 3.6 + random.nextDouble() * 0.5, 4.75);
		Vec3 land = rel(x + 1.2, 2.05, 4.6);
		CrewkitDisplay d = CrewkitDisplays.item(server, start, TAG, item, 1.4f, "ground", "");
		float dir = random.nextBoolean() ? 1f : -1f;
		CrewkitSounds.play(server, start, "minecraft:entity.egg.throw", 0.35f, 1.3f);
		CrewkitSchedule.after(2, () -> {
			d.moveTo(apex, 7);
			d.transform(1.4f, 1.4f, 1.4f, 0, 0, 0, dir * (float) Math.PI, 7);
		});
		CrewkitSchedule.after(9, () -> {
			d.moveTo(land, 7);
			d.transform(1.4f, 1.4f, 1.4f, 0, 0, 0, dir * (float) (Math.PI * 2 - 0.001), 7);
		});
		CrewkitSchedule.after(16, () -> CrewkitSounds.play(server, land, "minecraft:block.wool.place", 0.4f, 1.4f));
		CrewkitSchedule.after(40, d::kill);
	}

	private void clatter(MinecraftServer server) {
		Vec3 start = potPos(1).add(0, 0.05, 0);
		Vec3 edge = start.add(0.25, 0.35, 0.6);
		Vec3 floor = start.add(0.6, -0.95, 1.3);
		CrewkitDisplay pan = CrewkitDisplays.item(server, start, TAG, "minecraft:heavy_weighted_pressure_plate", PROP_SCALE, "ground", "");
		CrewkitSounds.play(server, start, "minecraft:block.chain.hit", 0.5f, 0.9f);
		CrewkitSchedule.after(2, () -> {
			pan.moveTo(edge, 4);
			pan.transform(PROP_SCALE, PROP_SCALE, PROP_SCALE, 0, 0, 0, 0.9f, 4);
		});
		CrewkitSchedule.after(6, () -> {
			pan.moveTo(floor, 5);
			pan.transform(PROP_SCALE, PROP_SCALE, PROP_SCALE, 0, 0, 0, 2.6f, 5);
		});
		CrewkitSchedule.after(11, () -> {
			CrewkitSounds.play(server, floor, "minecraft:block.anvil.land", 0.25f, 2.0f);
			particle(server, "minecraft:crit", floor.add(0, 0.1, 0), 0.2, 0.05, 0.2, 0.1, 6);
			pan.transform(PROP_SCALE, PROP_SCALE, PROP_SCALE, 0, 0, 0, 3.14f, 3);
		});
		CrewkitSchedule.after(15, () -> CrewkitSounds.play(server, floor, "minecraft:block.anvil.land", 0.12f, 2.0f));
		CrewkitSchedule.after(60, pan::kill);
	}

	private void finale(MinecraftServer server) {
		if (props.isEmpty()) return;
		steam(server, 14, 0.2);
		particle(server, "minecraft:cloud", potPos(1).add(0, 0.6, 0), 0.6, 0.3, 0.2, 0.04, 30);
		CrewkitSounds.play(server, potPos(1), "minecraft:block.lava.extinguish", 0.6f, 0.7f);
		for (CrewkitDisplay prop : props) prop.transform(PROP_SCALE, PROP_SCALE, PROP_SCALE, 0, 0, 0, 0, 6);
		calm = true;
		heat = 0;
	}

	// --- helpers -------------------------------------------------------------------------------

	private static void particle(MinecraftServer server, String id, Vec3 p, double dx, double dy, double dz, double speed, int count) {
		CrewkitDisplays.run(server, String.format(Locale.ROOT, "particle %s %.3f %.3f %.3f %.3f %.3f %.3f %.3f %d",
				id, p.x, p.y, p.z, dx, dy, dz, speed, count));
	}

	private static String str(JsonObject data, String key) {
		JsonElement e = data.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}
}
