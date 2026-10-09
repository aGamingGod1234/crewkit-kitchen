package dev.agaminggod.arenaagents.crewkit.fun.pigeons;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDispatcher;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplay;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplays;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSchedule;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSounds;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitText;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Reap pigeons: every Reap call (activity search/quote/probe/details) launches a paper courier from the
 * pass that flies out the east door or up into a ceiling coffer. The matching result flies a courier back
 * onto the pass with a green burst (ok), red smoke (sold_out/over/error) or a grey droop (busy).
 * At most 3 couriers are visible; the rest show as a pulsing "xN in flight" counter over the door.
 */
public final class ReapPigeonFeature implements CrewkitFeature {
	private static final String TAG = "ck_pigeons";
	private static final Set<String> KINDS = Set.of("search", "quote", "probe", "details");
	private static final int MAX_VISIBLE = 3;
	private static final int FLIGHT_TICKS = 32;
	private static final int LAND_TICKS = 24;
	private static final int STEP = 2;
	private static final int STALE_TICKS = 20 * 90;

	private enum Phase { OUT, AWAY, IN, LAND, DONE }

	private static final class Call {
		final String kind;
		final boolean viaCoffer;
		String result;
		Phase phase = Phase.AWAY;
		CrewkitDisplay display;
		int phaseStart;
		int awaySince;
		Vec3 a;
		Vec3 c;
		Vec3 b;

		Call(String kind, boolean viaCoffer) {
			this.kind = kind;
			this.viaCoffer = viaCoffer;
		}
	}

	private final List<Call> calls = new ArrayList<>();
	private CrewkitDisplay counter;
	private int counterShown = -1;
	private int launches;

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		try {
			if (!"activity".equals(event) || data == null) return;
			String kind = str(data, "kind");
			String result = str(data, "result");
			if (kind == null || result == null || !KINDS.contains(kind)) return;
			int now = server.getTickCount();
			if ("pending".equals(result)) {
				launch(server, kind, now);
			} else {
				for (Call call : calls) {
					if (call.kind.equals(kind) && call.result == null) {
						call.result = result;
						return;
					}
				}
			}
		} catch (Exception e) {
			CrewkitDispatcher.LOGGER.debug("CrewKit pigeons onEvent failed", e);
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		try {
			int now = server.getTickCount();
			if (now % STEP != 0) return;
			for (Iterator<Call> it = calls.iterator(); it.hasNext();) {
				Call call = it.next();
				step(server, call, now);
				if (call.phase == Phase.DONE) it.remove();
			}
			updateCounter(server);
		} catch (Exception e) {
			CrewkitDispatcher.LOGGER.debug("CrewKit pigeons tick failed", e);
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		calls.clear();
		counter = null;
		counterShown = -1;
		launches = 0;
		try {
			CrewkitDisplays.killTag(server, TAG);
		} catch (Exception e) {
			CrewkitDispatcher.LOGGER.debug("CrewKit pigeons reset failed", e);
		}
	}

	// --- flight ---

	private void launch(MinecraftServer server, String kind, int now) {
		Call call = new Call(kind, (launches++ % 3) == 2);
		call.awaySince = now;
		boolean show = visible() < MAX_VISIBLE;
		calls.add(call);
		if (show) {
			Vec3 from = pass();
			Vec3 to = call.viaCoffer ? coffer() : door();
			Vec3 ctrl = call.viaCoffer ? rel(11.5, 7.2, 8.5) : rel(18.0, 6.0, 10.5);
			startFlight(server, call, Phase.OUT, from, ctrl, to, now);
			CrewkitSounds.play(server, from, "minecraft:entity.phantom.flap", 0.7f, 1.6f);
			CrewkitSounds.play(server, from, "minecraft:item.elytra.flying", 0.25f, 1.8f);
		}
		pulseCounter();
	}

	private void startFlight(MinecraftServer server, Call call, Phase phase, Vec3 a, Vec3 c, Vec3 b, int now) {
		call.phase = phase;
		call.phaseStart = now;
		call.a = a;
		call.c = c;
		call.b = b;
		if (call.display == null || !call.display.alive()) {
			call.display = CrewkitDisplays.item(server, a, TAG, "minecraft:paper", 0.6f, "fixed", "");
		}
	}

	private void step(MinecraftServer server, Call call, int now) {
		switch (call.phase) {
			case OUT, IN -> fly(server, call, now);
			case LAND -> land(call, now);
			case AWAY -> {
				if (call.result != null) {
					if (visible() < MAX_VISIBLE) {
						Vec3 from = call.viaCoffer ? coffer() : door();
						Vec3 ctrl = call.viaCoffer ? rel(13.0, 7.0, 9.5) : rel(19.0, 5.5, 2.5);
						startFlight(server, call, Phase.IN, from, ctrl, landing(), now);
						CrewkitSounds.play(server, from, "minecraft:entity.phantom.flap", 0.6f, 1.3f);
					} else {
						// Too many in the air: resolve straight onto the pass.
						burst(server, call.result, landing());
						call.phase = Phase.DONE;
					}
				} else if (now - call.awaySince > STALE_TICKS) {
					call.phase = Phase.DONE;
				}
			}
			default -> {}
		}
	}

	private void fly(MinecraftServer server, Call call, int now) {
		double t = Math.min(1.0, (now - call.phaseStart) / (double) FLIGHT_TICKS);
		double e = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
		Vec3 p = bezier(call.a, call.c, call.b, e);
		// Lateral S-wobble (fading at the ends) so it banks like a paper plane.
		double fade = Math.sin(e * Math.PI);
		p = p.add(0, Math.sin(e * Math.PI * 3) * 0.15 * fade, Math.sin(e * Math.PI * 2) * 0.5 * fade);
		Vec3 d = bezierTangent(call.a, call.c, call.b, e);
		double bank = Math.cos(e * Math.PI * 2) * 0.6 * fade + Math.atan2(d.y, Math.max(0.01, Math.hypot(d.x, d.z))) * 0.8;
		pose(server, call.display, p, d, (float) bank, STEP);
		if (now % 4 == 0) particle(server, "minecraft:end_rod", p, 0.02, 1, 0.0);
		if (t < 1.0) return;
		if (call.phase == Phase.OUT) {
			particle(server, "minecraft:cloud", p, 0.15, 6, 0.02);
			CrewkitSounds.play(server, p, "minecraft:entity.breeze.wind_burst", 0.3f, 1.8f);
			if (call.display != null) call.display.kill();
			call.display = null;
			call.phase = Phase.AWAY;
			call.awaySince = now;
		} else {
			call.phase = Phase.LAND;
			call.phaseStart = now;
			burst(server, call.result, call.b);
			if (call.display != null) {
				if ("busy".equals(call.result)) {
					call.display.transform(0.55f, 0.45f, 0.55f, 0f, -0.15f, 0f, 1.1f, 8);
				} else {
					call.display.transform(0.9f, 0.9f, 0.9f, 0f, 0.1f, 0f, 0f, 4);
				}
			}
		}
	}

	private void land(Call call, int now) {
		int age = now - call.phaseStart;
		if (call.display != null) {
			if ("busy".equals(call.result)) {
				if (age > 8) {
					float wob = (float) (Math.sin(age * 0.6) * 0.25 * (1 - age / (double) LAND_TICKS));
					call.display.transform(0.55f, 0.45f, 0.55f, 0f, -0.2f, 0f, 1.1f + wob, STEP);
				}
			} else if (age == 6) {
				call.display.transform(0.01f, 0.01f, 0.01f, 0f, 0.3f, 0f, 0f, LAND_TICKS - 6);
			}
		}
		if (age >= LAND_TICKS) {
			if (call.display != null) call.display.kill();
			call.display = null;
			call.phase = Phase.DONE;
		}
	}

	private void burst(MinecraftServer server, String result, Vec3 at) {
		switch (result == null ? "" : result) {
			case "ok" -> {
				particle(server, "minecraft:happy_villager", at, 0.35, 14, 0.0);
				particle(server, "minecraft:electric_spark", at, 0.25, 10, 0.3);
				CrewkitSounds.play(server, at, "minecraft:entity.experience_orb.pickup", 0.8f, 1.2f);
			}
			case "busy" -> {
				particle(server, "minecraft:smoke", at, 0.2, 10, 0.0);
				particle(server, "minecraft:ash", at, 0.3, 12, 0.0);
				CrewkitSounds.play(server, at, "minecraft:block.note_block.bass", 0.7f, 0.6f);
			}
			default -> {
				particle(server, "minecraft:large_smoke", at, 0.2, 8, 0.02);
				particle(server, "minecraft:dust{color:[1.0,0.25,0.2],scale:1.4}", at, 0.3, 12, 0.0);
				CrewkitSounds.play(server, at, "minecraft:entity.villager.no", 0.8f, 1.0f);
			}
		}
	}

	// --- counter ---

	private int inFlight() {
		int n = 0;
		for (Call call : calls) {
			if (call.phase == Phase.OUT || (call.phase == Phase.AWAY && call.result == null)) n++;
		}
		return n;
	}

	private void updateCounter(MinecraftServer server) {
		int n = inFlight();
		if (n == counterShown) return;
		counterShown = n;
		if (n <= 1) {
			if (counter != null) counter.kill();
			counter = null;
			return;
		}
		String label = CrewkitText.of("x" + n + " in flight", n > MAX_VISIBLE ? CrewkitText.AMBER : CrewkitText.WHITE, true);
		if (counter == null || !counter.alive()) {
			counter = CrewkitDisplays.text(server, rel(25.5, 4.2, 5.6), TAG, label, 1.2f, "");
		} else {
			counter.text(label);
		}
	}

	private void pulseCounter() {
		CrewkitDisplay c = counter;
		if (c == null) return;
		c.transform(1.7f, 0f, 0f, 0f, 3);
		CrewkitSchedule.after(4, () -> {
			try {
				if (c.alive()) c.transform(1.2f, 0f, 0f, 0f, 6);
			} catch (Exception ignored) {
				// counter already gone
			}
		});
	}

	// --- helpers ---

	private int visible() {
		int n = 0;
		for (Call call : calls) {
			if (call.display != null) n++;
		}
		return n;
	}

	private static void pose(MinecraftServer server, CrewkitDisplay display, Vec3 p, Vec3 dir, float roll, int ticks) {
		if (display == null) return;
		// Sprite plane contains the travel direction; flip so its face points toward the camera (south).
		float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)) + 90f;
		if (Math.cos(Math.toRadians(yaw)) < 0) yaw += 180f;
		display.merge("{teleport_duration:" + ticks + "}");
		display.transform(0.6f, 0.6f, 0.6f, 0f, 0f, 0f, roll, ticks);
		CrewkitDisplays.run(server, String.format(Locale.ROOT, "tp %s %.4f %.4f %.4f %.2f 0", display.uuid(), p.x, p.y, p.z, yaw));
	}

	private static void particle(MinecraftServer server, String particle, Vec3 p, double spread, int count, double speed) {
		CrewkitDisplays.run(server, String.format(Locale.ROOT, "particle %s %.3f %.3f %.3f %.2f %.2f %.2f %.3f %d force",
				particle, p.x, p.y, p.z, spread, spread, spread, speed, count));
	}

	private static Vec3 bezier(Vec3 a, Vec3 c, Vec3 b, double t) {
		double u = 1 - t;
		return a.scale(u * u).add(c.scale(2 * u * t)).add(b.scale(t * t));
	}

	private static Vec3 bezierTangent(Vec3 a, Vec3 c, Vec3 b, double t) {
		return c.subtract(a).scale(2 * (1 - t)).add(b.subtract(c).scale(2 * t));
	}

	private static Vec3 rel(double x, double y, double z) {
		net.minecraft.core.BlockPos o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + x, o.getY() + y, o.getZ() + z);
	}

	/** Launch point at the pass, just east of and in front of the chef. */
	private static Vec3 pass() {
		int[] a = CrewkitAnchors.AGENT;
		return rel(a[0] + 1.3, a[1] + 1.4, a[2] + 1.0);
	}

	private static Vec3 landing() {
		int[] a = CrewkitAnchors.AGENT;
		return rel(a[0] + 1.5, a[1] + 1.1, a[2] + 1.2);
	}

	/** Just outside the east delivery door (x=27, z 5-6). */
	private static Vec3 door() {
		return rel(28.6, 2.4, 6.0);
	}

	/** Up into a ceiling coffer above the beams. */
	private static Vec3 coffer() {
		return rel(14.5, 8.6, 4.5);
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}
