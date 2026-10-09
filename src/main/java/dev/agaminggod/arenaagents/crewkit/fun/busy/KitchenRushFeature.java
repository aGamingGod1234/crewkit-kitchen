package dev.agaminggod.arenaagents.crewkit.fun.busy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplay;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplays;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSchedule;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSounds;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitText;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes waiting on Reap dramatic: a "REAP IS BUSY" sign swings down over the pass with a retry countdown,
 * the chef sweats and the stove flares; a rush meter above the delivery door fills per Reap call and cools
 * down; the hood smokes while a run is active, faster as the kitchen gets busier.
 */
public final class KitchenRushFeature implements CrewkitFeature {
	private static final Logger LOGGER = LoggerFactory.getLogger("crewkit-busy");
	private static final String TAG = "ck_busy";

	// Sign hangs over the pass between the two back-wall boards (x 13.5), bottom edge at y 4.0.
	private static final double SIGN_X = 13.5, SIGN_Y = 4.0, SIGN_Z = 8.5, SIGN_LIFT = 3.4;
	private static final float SIGN_SCALE = 1.7f;
	private static final int DEFAULT_RETRY_SECONDS = 5;

	private static final int METER_CELLS = 12;
	private static final double METER_X = 26.45, METER_Y = 4.3, METER_Z = 6.0;
	private static final double RUSH_PER_CALL = 0.11;
	private static final double RUSH_PER_BUSY = 0.2;
	private static final double RUSH_COOL_PER_TICK = 0.0012; // full to empty in about 40 s
	private static final double RED_AT = 0.8, YELLOW_AT = 0.45;

	private final List<CrewkitDisplay> sign = new ArrayList<>();
	private CrewkitDisplay signText;
	private boolean busy;
	private int retryLeft;
	private int nextSecondTick;
	private int busySince;

	private CrewkitDisplay meter;
	private double rush;
	private String meterShown = "";
	private boolean wasRed;
	private int lastBellTick = -1000;

	private boolean runActive;
	private int nextSmokeTick;

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		try {
			switch (event) {
				case "brief" -> {
					runActive = true;
					ensureMeter(server);
				}
				case "activity" -> onActivity(server, data);
				case "completed", "failed", "expired" -> {
					runActive = false;
					raiseSign(server);
				}
				case "reset" -> reset(server);
				default -> {}
			}
		} catch (RuntimeException e) {
			LOGGER.warn("CrewKit busy: event {} failed", event, e);
		}
	}

	private void onActivity(MinecraftServer server, JsonObject data) {
		if (data == null) return;
		String kind = str(data, "kind");
		String result = str(data, "result");
		ensureMeter(server);
		if ("pending".equals(result) && !"think".equals(kind)) bumpRush(server, RUSH_PER_CALL);
		if ("busy".equals(result) || "backoff".equals(kind)) {
			bumpRush(server, RUSH_PER_BUSY);
			int seconds = retrySeconds(data);
			if (!busy) dropSign(server, seconds);
			else restartCountdown(server, seconds);
			flareStove(server);
		} else if ("retry".equals(kind) && busy) {
			retryLeft = 0;
			updateSignText();
		} else if ("ok".equals(result)) {
			raiseSign(server);
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		try {
			int now = server.getTickCount();
			tickSign(server, now);
			tickMeter(now);
			tickSmoke(server, now);
		} catch (RuntimeException e) {
			LOGGER.warn("CrewKit busy: tick failed", e);
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		try {
			busy = false;
			runActive = false;
			rush = 0;
			wasRed = false;
			meterShown = "";
			sign.clear();
			signText = null;
			meter = null;
			CrewkitDisplays.killTag(server, TAG);
		} catch (RuntimeException e) {
			LOGGER.warn("CrewKit busy: reset failed", e);
		}
	}

	// ---------------------------------------------------------------- busy sign

	private void dropSign(MinecraftServer server, int seconds) {
		clearSign();
		busy = true;
		busySince = server.getTickCount();
		retryLeft = seconds;
		nextSecondTick = busySince + 40; // first tick lands after the drop

		Vec3 up = rel(SIGN_X, SIGN_Y + SIGN_LIFT, SIGN_Z);
		signText = CrewkitDisplays.text(server, up, TAG, signComponent(), SIGN_SCALE,
				"background:" + CrewkitText.argb(240, 0x3A2414) + ",line_width:200");
		sign.add(signText);
		// Two chains up into the ceiling; the block model grows from the min corner, so shift -0.5 to centre it.
		for (double cx : new double[] {SIGN_X - 1.4, SIGN_X + 1.4}) {
			Vec3 at = rel(cx - 0.5, SIGN_Y + SIGN_LIFT + 0.9, SIGN_Z - 0.5);
			sign.add(CrewkitDisplays.block(server, at, TAG, "minecraft:iron_chain", 1f, 3.2f, 1f, ""));
		}
		List<CrewkitDisplay> dropping = new ArrayList<>(sign);
		CrewkitSchedule.after(2, () -> {
			for (CrewkitDisplay d : dropping) d.moveTo(d.pos().subtract(0, SIGN_LIFT, 0), 14);
		});
		CrewkitSchedule.after(16, () -> {
			if (!busy) return;
			Vec3 at = rel(SIGN_X, SIGN_Y, SIGN_Z);
			CrewkitSounds.play(server, at, "minecraft:block.anvil.land", 0.35f, 1.5f);
			CrewkitSounds.play(server, at, "minecraft:entity.villager.no", 0.8f, 0.9f);
			swing(0.22f, 0);
		});
		CrewkitSounds.play(server, rel(SIGN_X, SIGN_Y + 2, SIGN_Z), "minecraft:block.chain.fall", 0.8f, 1.0f);
	}

	/** Decaying pendulum wobble on the sign after it lands. */
	private void swing(float amplitude, int step) {
		if (!busy || signText == null || step > 6) return;
		float roll = step == 6 ? 0f : (step % 2 == 0 ? 1 : -1) * amplitude;
		signText.transform(SIGN_SCALE, SIGN_SCALE, SIGN_SCALE, 0, 0, 0, roll, 8);
		float next = amplitude * 0.6f;
		CrewkitSchedule.after(8, () -> swing(next, step + 1));
	}

	private void restartCountdown(MinecraftServer server, int seconds) {
		retryLeft = seconds;
		nextSecondTick = server.getTickCount() + 20;
		updateSignText();
		swing(0.15f, 0);
	}

	private void raiseSign(MinecraftServer server) {
		if (!busy) return;
		busy = false;
		List<CrewkitDisplay> going = new ArrayList<>(sign);
		sign.clear();
		signText = null;
		for (CrewkitDisplay d : going) d.moveTo(d.pos().add(0, SIGN_LIFT, 0), 14);
		Vec3 at = rel(SIGN_X, SIGN_Y, SIGN_Z);
		CrewkitSounds.play(server, at, "minecraft:block.piston.contract", 0.6f, 1.3f);
		CrewkitSounds.play(server, at, "minecraft:entity.villager.yes", 0.7f, 1.2f);
		CrewkitSchedule.after(16, () -> {
			for (CrewkitDisplay d : going) d.kill();
		});
	}

	private void clearSign() {
		for (CrewkitDisplay d : sign) d.kill();
		sign.clear();
		signText = null;
	}

	private void tickSign(MinecraftServer server, int now) {
		if (!busy) return;
		if (retryLeft > 0 && now >= nextSecondTick) {
			retryLeft--;
			nextSecondTick = now + 20;
			updateSignText();
			Vec3 at = rel(SIGN_X, SIGN_Y, SIGN_Z);
			CrewkitSounds.play(server, at, "minecraft:block.note_block.hat", 0.9f, retryLeft % 2 == 0 ? 1.2f : 1.7f);
			if (retryLeft == 0) CrewkitSounds.play(server, at, "minecraft:block.note_block.bell", 0.7f, 1.4f);
		}
		int since = now - busySince;
		if (since % 10 == 0) {
			Vec3 head = rel(CrewkitAnchors.AGENT[0] + 0.5, CrewkitAnchors.AGENT[1] + 2.1, CrewkitAnchors.AGENT[2] + 0.5);
			particle(server, "minecraft:falling_water", head, 0.3, 0.1, 0.3, 0, 4);
			particle(server, "minecraft:splash", head, 0.35, 0.15, 0.35, 0.05, 6);
		}
		if (since % 30 == 0) particle(server, "minecraft:flame", stoveTop(), 0.9, 0.1, 0.2, 0.02, 4);
	}

	private void updateSignText() {
		if (signText != null) signText.text(signComponent());
	}

	private String signComponent() {
		String second;
		int color;
		if (retryLeft > 0) {
			second = "retry in " + retryLeft + "…";
			color = retryLeft <= 2 ? CrewkitText.RED : CrewkitText.AMBER;
		} else {
			second = "retrying…";
			color = CrewkitText.GREEN;
		}
		return CrewkitText.join(
				CrewkitText.of("REAP IS BUSY\n", CrewkitText.RED, true),
				CrewkitText.of(second, color, true));
	}

	private void flareStove(MinecraftServer server) {
		Vec3 stove = stoveTop();
		particle(server, "minecraft:flame", stove, 1.0, 0.3, 0.25, 0.08, 45);
		particle(server, "minecraft:lava", stove, 0.8, 0.2, 0.2, 0, 6);
		particle(server, "minecraft:large_smoke", stove.add(0, 0.6, 0), 0.8, 0.3, 0.2, 0.03, 12);
		CrewkitSounds.play(server, stove, "minecraft:item.firecharge.use", 0.8f, 0.9f);
	}

	// ---------------------------------------------------------------- rush meter

	private void ensureMeter(MinecraftServer server) {
		if (meter != null && meter.alive()) return;
		// Above the east delivery door (x=27, z 5..6, lintel y=3), turned toward the camera.
		meter = CrewkitDisplays.text(server, rel(METER_X, METER_Y, METER_Z), TAG, meterComponent(server.getTickCount()), 1.1f,
				"background:" + CrewkitText.argb(200, 0x111111) + ",line_width:300");
		meter.merge("{Rotation:[70f,0f]}");
		meterShown = "";
	}

	private void bumpRush(MinecraftServer server, double amount) {
		rush = Math.min(1.0, rush + amount);
		if (rush >= RED_AT && !wasRed) {
			wasRed = true;
			int now = server.getTickCount();
			if (now - lastBellTick > 60) {
				lastBellTick = now;
				Vec3 at = rel(METER_X, METER_Y + 0.3, METER_Z);
				CrewkitSounds.play(server, at, "minecraft:block.bell.use", 0.9f, 1.6f);
				CrewkitSchedule.after(4, () -> CrewkitSounds.play(server, at, "minecraft:block.bell.use", 0.7f, 1.9f));
			}
		}
	}

	private void tickMeter(int now) {
		if (rush > 0) rush = Math.max(0, rush - RUSH_COOL_PER_TICK);
		if (rush < RED_AT - 0.1) wasRed = false;
		if (meter == null || now % 5 != 0) return;
		String component = meterComponent(now);
		if (!component.equals(meterShown)) {
			meterShown = component;
			meter.text(component);
		}
	}

	private String meterComponent(int now) {
		int filled = (int) Math.round(rush * METER_CELLS);
		int color = rush >= RED_AT ? CrewkitText.RED : rush >= YELLOW_AT ? CrewkitText.AMBER : CrewkitText.GREEN;
		String label = rush >= RED_AT
				? CrewkitText.of("\nKITCHEN RUSH!", (now / 10) % 2 == 0 ? CrewkitText.RED : CrewkitText.WHITE, true)
				: CrewkitText.of("\nrush meter", CrewkitText.MUTED, false);
		return CrewkitText.join(
				CrewkitText.of("▮".repeat(filled), color, true),
				CrewkitText.of("▮".repeat(METER_CELLS - filled), 0x333333, true),
				label);
	}

	// ---------------------------------------------------------------- hood smoke

	private void tickSmoke(MinecraftServer server, int now) {
		if (!runActive || now < nextSmokeTick) return;
		nextSmokeTick = now + (int) Math.round(30 - rush * 24); // every 1.5 s idle, every 0.3 s at full rush
		particle(server, "minecraft:smoke", stoveTop(), 0.9, 0.1, 0.2, 0.01, 3 + (int) (rush * 6));
		particle(server, "minecraft:campfire_cosy_smoke", rel(13.5, 6.4, 2.5), 0.25, 0.1, 0.25, 0.01, 1 + (int) (rush * 2));
	}

	// ---------------------------------------------------------------- helpers

	private static Vec3 stoveTop() {
		return rel(13.5, 2.05, 4.6);
	}

	private static Vec3 rel(double x, double y, double z) {
		BlockPos o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + x, o.getY() + y, o.getZ() + z);
	}

	private static void particle(MinecraftServer server, String id, Vec3 p, double dx, double dy, double dz, double speed, int count) {
		CrewkitDisplays.run(server, String.format(Locale.ROOT, "particle %s %.3f %.3f %.3f %.3f %.3f %.3f %.3f %d force",
				id, p.x, p.y, p.z, dx, dy, dz, speed, count));
	}

	private static int retrySeconds(JsonObject data) {
		for (String key : new String[] {"retryIn", "retryAfter", "retryAfterSeconds", "seconds"}) {
			JsonElement e = data.get(key);
			if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
				return Math.max(1, Math.min(30, (int) Math.ceil(e.getAsDouble())));
			}
		}
		JsonElement attempt = data.get("attempt");
		if (attempt != null && attempt.isJsonPrimitive() && attempt.getAsJsonPrimitive().isNumber()) {
			return Math.max(2, Math.min(9, 2 + attempt.getAsInt() * 2));
		}
		return DEFAULT_RETRY_SECONDS;
	}

	private static String str(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}
}
