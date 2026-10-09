package dev.agaminggod.arenaagents.crewkit.fun.soldout;

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
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Random;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Rubber-stamp gag: "SOLD OUT!" (red) on sold-out activity / item_removed, "OVER BUDGET" (orange) on
 * gate_blocked. Stamps slam in front of the pass; bursts are queued 8 ticks (0.4 s) apart. A tally sign
 * in the corner counts sold-outs with a ding.
 */
public final class SoldOutFeature implements CrewkitFeature {
	private static final String TAG = "ck_soldout";
	private static final int ORANGE = 0xFF8A1F;
	private static final int STAGGER_TICKS = 8;
	private static final int[] STAMP = {11, 2, 10};
	private static final int[] TALLY = {4, 5, 9};

	private record Stamp(boolean soldOut, String mcItem) {}

	private final ArrayDeque<Stamp> queue = new ArrayDeque<>();
	private final Random random = new Random();
	private int nextAllowedTick;
	private int tally;
	private CrewkitDisplay tallySign;

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		try {
			if ("reset".equals(event)) {
				reset(server);
				return;
			}
			if ("activity".equals(event) && "sold_out".equals(str(data, "result"))) {
				enqueue(new Stamp(true, str(data, "mcItem")));
			} else if ("item_removed".equals(event) && "sold_out".equals(str(data, "why"))) {
				enqueue(new Stamp(true, str(data, "mcItem")));
			} else if ("gate_blocked".equals(event)) {
				enqueue(new Stamp(false, null));
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit soldout: event failed", e);
		}
	}

	private void enqueue(Stamp stamp) {
		// Cap the backlog so a flood of events cannot keep stamping for minutes.
		if (queue.size() < 6) queue.add(stamp);
	}

	@Override
	public void tick(MinecraftServer server) {
		try {
			if (queue.isEmpty()) return;
			int now = server.getTickCount();
			if (now < nextAllowedTick) return;
			Stamp stamp = queue.poll();
			nextAllowedTick = now + STAGGER_TICKS;
			play(server, stamp);
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit soldout: tick failed", e);
		}
	}

	private void play(MinecraftServer server, Stamp stamp) {
		Vec3 base = CrewkitAnchors.at(STAMP).add((random.nextDouble() - 0.5) * 1.2, (random.nextDouble() - 0.5) * 0.4, 0);
		int color = stamp.soldOut() ? CrewkitText.RED : ORANGE;
		String label = stamp.soldOut() ? "SOLD OUT!" : "OVER BUDGET";
		float settle = stamp.soldOut() ? 1.6f : 2.0f;
		float tilt = (float) Math.toRadians((random.nextDouble() - 0.5) * 16);
		int bg = CrewkitText.argb(0xE0, stamp.soldOut() ? 0x200404 : 0x241002);

		CrewkitDisplay text = CrewkitDisplays.text(server, base, TAG,
				CrewkitText.join(CrewkitText.of("▌", color, true), CrewkitText.of(label, color, true), CrewkitText.of("▐", color, true)),
				4f, "background:" + bg + ",shadow:0b");
		text.transform(4f, 4f, 4f, 0f, 1.2f, 0f, tilt * 2.5f, 0);

		// Slam down past the target, bounce, then settle.
		CrewkitSchedule.after(2, () -> text.transform(settle * 0.82f, settle * 0.82f, settle, 0f, 0f, 0f, tilt, 3));
		CrewkitSchedule.after(5, () -> {
			slamFx(server, base, stamp.soldOut());
			text.transform(settle * 1.08f, settle * 1.08f, settle, 0f, 0.02f, 0f, tilt, 2);
		});
		CrewkitSchedule.after(7, () -> text.transform(settle, settle, settle, 0f, 0f, 0f, tilt, 3));
		// Squash away after ~2 s (text opacity is not interpolated, so shrink instead of fading).
		CrewkitSchedule.after(45, () -> text.transform(settle * 1.15f, 0.05f, settle, 0f, 0.3f, 0f, tilt, 5));
		CrewkitSchedule.after(51, text::kill);

		if (stamp.soldOut()) {
			ghost(server, base, stamp.mcItem());
			CrewkitSchedule.after(10, () -> bumpTally(server));
		}
	}

	private void slamFx(MinecraftServer server, Vec3 at, boolean soldOut) {
		CrewkitSounds.play(server, at, "minecraft:block.anvil.land", 0.9f, soldOut ? 0.8f : 0.6f);
		Vec3 p = at.add(0, 0.4, 0.2);
		CrewkitDisplays.run(server, String.format(Locale.ROOT,
				"particle minecraft:large_smoke %.3f %.3f %.3f 1.2 0.3 0.1 0.03 18 force", p.x, p.y, p.z));
		CrewkitDisplays.run(server, String.format(Locale.ROOT,
				"particle minecraft:crit %.3f %.3f %.3f 1.4 0.4 0.1 0.6 30 force", p.x, p.y, p.z));
	}

	/** Ghost of the sold-out item with a red X that spins and shatters away. */
	private void ghost(MinecraftServer server, Vec3 base, String mcItem) {
		String item = mcItem == null || mcItem.isBlank() ? "minecraft:paper" : (mcItem.contains(":") ? mcItem : "minecraft:" + mcItem);
		Vec3 at = base.add(-2.6, 0.6, 0.15);
		CrewkitDisplay ghost = CrewkitDisplays.item(server, at, TAG, item, 0.9f, "fixed", "");
		CrewkitDisplay cross = CrewkitDisplays.text(server, at.add(0, -0.45, 0.08), TAG,
				CrewkitText.of("✖", CrewkitText.RED, true), 3.2f, "");
		float dir = random.nextBoolean() ? 1f : -1f;
		CrewkitSchedule.after(14, () -> {
			Vec3 away = at.add(-1.4 * dir - 0.6, -1.2, 0.6);
			ghost.transform(0.05f, 0.05f, 0.05f, 0f, 0f, 0f, dir * 9f, 14);
			ghost.moveTo(away, 14);
			cross.transform(0.2f, 0.2f, 0.2f, 0f, 0f, 0f, dir * -6f, 14);
			cross.moveTo(away.add(0, -0.3, 0.08), 14);
			CrewkitDisplays.run(server, String.format(Locale.ROOT,
					"particle minecraft:item{item:\"%s\"} %.3f %.3f %.3f 0.2 0.2 0.1 0.12 14 force",
					CrewkitText.escape(item), at.x, at.y + 0.3, at.z));
			CrewkitSounds.play(server, at, "minecraft:entity.item.break", 0.8f, 1.1f);
		});
		CrewkitSchedule.after(30, () -> {
			ghost.kill();
			cross.kill();
		});
	}

	private void bumpTally(MinecraftServer server) {
		tally++;
		String component = CrewkitText.join(CrewkitText.of("SOLD OUT ", CrewkitText.RED, true), CrewkitText.of("x" + tally, CrewkitText.WHITE, true));
		Vec3 at = CrewkitAnchors.at(TALLY);
		if (tallySign == null || !tallySign.alive()) {
			tallySign = CrewkitDisplays.text(server, at, TAG, component, 1.1f,
					"background:" + CrewkitText.argb(0xD0, 0x1A0606));
		} else {
			tallySign.text(component);
		}
		CrewkitDisplay sign = tallySign;
		sign.transform(1.5f, 0f, 0f, 0f, 2);
		CrewkitSchedule.after(3, () -> sign.transform(1.1f, 0f, 0f, 0f, 5));
		CrewkitSounds.play(server, at, "minecraft:block.note_block.bell", 0.8f, Math.min(2.0f, 1.0f + tally * 0.08f));
	}

	@Override
	public void reset(MinecraftServer server) {
		queue.clear();
		tally = 0;
		tallySign = null;
		nextAllowedTick = 0;
		try {
			CrewkitDisplays.killTag(server, TAG);
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit soldout: reset failed", e);
		}
	}

	private static String str(JsonObject data, String key) {
		if (data == null) return null;
		JsonElement e = data.get(key);
		return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
	}
}
