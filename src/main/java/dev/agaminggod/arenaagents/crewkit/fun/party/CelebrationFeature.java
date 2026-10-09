package dev.agaminggod.arenaagents.crewkit.fun.party;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDispatcher;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplay;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplays;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSchedule;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSounds;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitText;
import java.util.Locale;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Party layer: confetti and a cheer on gate_passed, indoor fireworks + paper confetti rain + banner on
 * completed, and gold sparkles orbiting the bill board on record. Fireworks are particle bursts at
 * y 5-6 (ceiling beams sit at y=7), never rockets.
 */
public final class CelebrationFeature implements CrewkitFeature {
	private static final String TAG = "ck_party";
	private static final String CAST = "@e[tag=ck_cast,type=mannequin]";

	/** Table centres {x, z} relative to origin: A, B, C, D, E (docs/crewkit/SET-ZONES.md). */
	private static final double[][] TABLES = {{6, 13}, {14, 13}, {22, 13}, {10, 17}, {18, 17}};
	private static final float[][] COLOURS = {
		{1f, 0.30f, 0.24f}, {1f, 0.69f, 0.13f}, {0.24f, 0.86f, 0.52f},
		{0.43f, 0.55f, 1f}, {0.95f, 0.40f, 0.85f}, {1f, 1f, 1f},
	};

	private final Random random = new Random();
	/** Bumped on reset so stale scheduled steps do nothing. */
	private int generation;

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		try {
			switch (event) {
				case "gate_passed" -> gatePassed(server);
				case "completed" -> completed(server);
				case "record" -> record(server);
				case "reset" -> reset(server);
				default -> {}
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit party: {} failed", event, e);
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		generation++;
		try {
			CrewkitDisplays.killTag(server, TAG);
			CrewkitDisplays.run(server, "execute as " + CAST + " run rotate @s ~ 0");
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit party: reset failed", e);
		}
	}

	// ---------------------------------------------------------------- gate_passed

	private void gatePassed(MinecraftServer server) {
		Vec3 pass = rel(9, 3.6, 8);
		for (float[] c : COLOURS) particle(server, dust(c, 1.6f), pass, 2.8, 0.7, 0.4, 0, 14);
		particle(server, "minecraft:totem_of_undying", pass, 1.5, 0.3, 0.3, 0.45, 70);
		CrewkitDisplays.run(server, "execute at " + CAST + " run particle minecraft:happy_villager ~ ~2.1 ~ 0.35 0.25 0.35 0 10 force");
		CrewkitSounds.play(server, pass, "minecraft:entity.villager.celebrate", 0.8f, 1.1f);
		float[] notes = {0.794f, 1.0f, 1.189f, 1.587f};
		int gen = generation;
		for (int i = 0; i < notes.length; i++) {
			float pitch = notes[i];
			later(gen, 1 + i * 3, () -> CrewkitSounds.play(server, pass, "minecraft:block.note_block.pling", 0.7f, pitch));
		}
		later(gen, 6, () -> CrewkitDisplays.run(server, "execute at " + CAST + " run particle minecraft:note ~ ~2.3 ~ 0.3 0.1 0.3 1 2 force"));
	}

	// ---------------------------------------------------------------- completed

	private void completed(MinecraftServer server) {
		int gen = generation;
		Vec3 centre = rel(14, 4, 13);
		CrewkitSounds.play(server, centre, "minecraft:ui.toast.challenge_complete", 0.9f, 1.0f);
		banner(server, gen);
		confettiRain(server, gen);
		headBob(server, gen);
		// Two waves of indoor fireworks, one burst per table, staggered so each reads.
		for (int wave = 0; wave < 2; wave++) {
			for (int t = 0; t < TABLES.length; t++) {
				double[] table = TABLES[t];
				float[] colour = COLOURS[(t + wave * 2) % COLOURS.length];
				int delay = 6 + wave * 30 + t * 5;
				later(gen, delay, () -> firework(server, gen, rel(table[0], 5.3 + random.nextDouble() * 0.6, table[1]), colour));
			}
		}
	}

	private void firework(MinecraftServer server, int gen, Vec3 at, float[] colour) {
		particle(server, "minecraft:firework", at, 0, 0, 0, 0.16, 45);
		particle(server, dust(colour, 1.4f), at, 1.1, 0.7, 1.1, 0, 35);
		particle(server, "minecraft:end_rod", at, 0.2, 0.2, 0.2, 0.08, 6);
		CrewkitSounds.play(server, at, random.nextBoolean() ? "minecraft:entity.firework_rocket.blast" : "minecraft:entity.firework_rocket.large_blast", 0.7f, 0.9f + random.nextFloat() * 0.3f);
		later(gen, 8, () -> CrewkitSounds.play(server, at, "minecraft:entity.firework_rocket.twinkle", 0.5f, 1.0f + random.nextFloat() * 0.3f));
	}

	private void banner(MinecraftServer server, int gen) {
		Vec3 pos = rel(14, 3.4, 12.2);
		String text = CrewkitText.join(CrewkitText.of("ORDER PLACED! ", CrewkitText.GREEN, true), CrewkitText.of("🎉", CrewkitText.AMBER, true));
		CrewkitDisplay banner = CrewkitDisplays.text(server, pos, TAG, text, 0.1f, "background:" + CrewkitText.argb(0x90, 0x101418));
		later(gen, 2, () -> banner.transform(2.6f, 0, 0, 0, 8));
		later(gen, 10, () -> banner.transform(2.2f, 0, 0, 0, 4));
		later(gen, 60, () -> banner.transform(1.6f, 0, 1.8f, 0, 40));
		later(gen, 100, () -> banner.transform(0.05f, 0, 2.6f, 0, 10));
		later(gen, 112, banner::kill);
	}

	/** 30 paper bits fall from just under the beams in two spinning legs, gone after 6 s. */
	private void confettiRain(MinecraftServer server, int gen) {
		for (int i = 0; i < 30; i++) {
			double x = 3 + random.nextDouble() * 22;
			double z = 11 + random.nextDouble() * 8;
			Vec3 top = rel(x, 6.2, z);
			later(gen, 1 + i / 3, () -> {
				CrewkitDisplay bit = CrewkitDisplays.item(server, top, TAG, "minecraft:paper", 0.32f, "fixed", "");
				double drift = (random.nextDouble() - 0.5) * 1.5;
				float spin = (random.nextBoolean() ? 1 : -1) * (2.0f + random.nextFloat());
				later(gen, 2, () -> {
					bit.moveTo(top.add(drift, -2.1, 0), 55);
					bit.transform(0.32f, 0.32f, 0.32f, 0, 0, 0, spin, 55);
				});
				later(gen, 57, () -> {
					bit.moveTo(top.add(-drift * 0.5, -4.0, 0.2), 55);
					bit.transform(0.32f, 0.32f, 0.32f, 0, 0, 0, -spin, 55);
				});
				later(gen, 118, bit::kill);
			});
		}
	}

	/** Guests nod a few times; rotate changes pitch only, so yaw and seating stay as the cast set them. */
	private void headBob(MinecraftServer server, int gen) {
		for (int i = 0; i < 4; i++) {
			int base = 8 + i * 8;
			later(gen, base, () -> CrewkitDisplays.run(server, "execute as " + CAST + " run rotate @s ~ 25"));
			later(gen, base + 4, () -> CrewkitDisplays.run(server, "execute as " + CAST + " run rotate @s ~ -15"));
		}
		later(gen, 42, () -> CrewkitDisplays.run(server, "execute as " + CAST + " run rotate @s ~ 0"));
		later(gen, 10, () -> CrewkitDisplays.run(server, "execute at " + CAST + " run particle minecraft:happy_villager ~ ~2.1 ~ 0.35 0.3 0.35 0 12 force"));
	}

	// ---------------------------------------------------------------- record

	private void record(MinecraftServer server) {
		int gen = generation;
		double cx = 21.5, cy = 4.2, z = 1.7, rx = 5.6, ry = 2.9;
		int coins = 8;
		int steps = 30;
		CrewkitSounds.play(server, rel(cx, cy, z), "minecraft:entity.experience_orb.pickup", 0.7f, 1.4f);
		for (int c = 0; c < coins; c++) {
			double phase = c * Math.PI * 2 / coins;
			CrewkitDisplay coin = CrewkitDisplays.item(server, orbit(cx, cy, z, rx, ry, phase), TAG, "minecraft:gold_nugget", 0.45f, "fixed", "");
			for (int s = 1; s <= steps; s++) {
				double angle = phase + s * 0.21;
				boolean sparkle = s % 5 == 0;
				later(gen, s * 2, () -> {
					Vec3 p = orbit(cx, cy, z, rx, ry, angle);
					coin.moveTo(p, 3);
					if (sparkle) particle(server, "minecraft:wax_off", p, 0.1, 0.1, 0.05, 0, 2);
				});
			}
			later(gen, steps * 2 + 2, () -> coin.transform(0.02f, 0, 0, 0, 6));
			later(gen, steps * 2 + 10, coin::kill);
		}
		later(gen, 20, () -> CrewkitSounds.play(server, rel(cx, cy, z), "minecraft:block.amethyst_block.chime", 0.8f, 1.5f));
		later(gen, 40, () -> CrewkitSounds.play(server, rel(cx, cy, z), "minecraft:block.amethyst_block.chime", 0.8f, 1.8f));
	}

	private static Vec3 orbit(double cx, double cy, double z, double rx, double ry, double angle) {
		return rel(cx + Math.cos(angle) * rx, cy + Math.sin(angle) * ry, z);
	}

	// ---------------------------------------------------------------- helpers

	private void later(int gen, int ticks, Runnable action) {
		CrewkitSchedule.after(ticks, () -> {
			if (gen != generation) return;
			try {
				action.run();
			} catch (RuntimeException e) {
				CrewkitDispatcher.LOGGER.debug("CrewKit party step failed", e);
			}
		});
	}

	private static Vec3 rel(double x, double y, double z) {
		BlockPos o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + x, o.getY() + y, o.getZ() + z);
	}

	private static String dust(float[] c, float scale) {
		return String.format(Locale.ROOT, "minecraft:dust{color:[%.2f,%.2f,%.2f],scale:%.1f}", c[0], c[1], c[2], scale);
	}

	private static void particle(MinecraftServer server, String particle, Vec3 at, double dx, double dy, double dz, double speed, int count) {
		CrewkitDisplays.run(server, String.format(Locale.ROOT, "particle %s %.3f %.3f %.3f %.2f %.2f %.2f %.3f %d force",
				particle, at.x, at.y, at.z, dx, dy, dz, speed, count));
	}
}
