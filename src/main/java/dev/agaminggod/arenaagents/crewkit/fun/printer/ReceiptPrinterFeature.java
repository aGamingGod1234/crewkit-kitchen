package dev.agaminggod.arenaagents.crewkit.fun.printer;

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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * A little receipt printer at the east end of the pass. Each finished {@code activity} quote prints a slip
 * ("QUOTE #n S$196.85" + OVER/OK stamp); each finished probe/search prints a short stock slip. Slips feed up
 * out of the printer, then flutter onto a messy pile beside it (max ~20, oldest crumple away). On
 * {@code completed} the pile bursts into confetti.
 */
public final class ReceiptPrinterFeature implements CrewkitFeature {
	private static final String TAG = "ck_printer";
	private static final int PILE_CAP = 20;
	private static final int PRINT_GAP = 22;
	private static final int INK = 0x1E1E1E;
	private static final int PAPER = 0xFFFDF2;

	/** Positions relative to the set origin (blocks, not centred). */
	private static final double[] BODY = {13.15, 2.0, 8.25};
	private static final double[] SLOT = {13.45, 2.32, 8.45};
	private static final double[] PILE = {14.45, 2.02, 8.75};

	private record Slip(CrewkitDisplay display, double x, double y, double z) {}

	private final Deque<Slip> pile = new ArrayDeque<>();
	private final List<CrewkitDisplay> body = new ArrayList<>();
	private final Random random = new Random();
	private int quoteCount;
	private int nextPrintTick;
	private int generation;

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		try {
			if (data == null) data = new JsonObject();
			switch (event) {
				case "activity" -> onActivity(server, data);
				case "completed" -> burst(server);
				case "reset" -> reset(server);
				default -> { }
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit printer: event {} failed", event, e);
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		generation++;
		pile.clear();
		body.clear();
		quoteCount = 0;
		nextPrintTick = 0;
		try {
			CrewkitDisplays.killTag(server, TAG);
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit printer: reset failed", e);
		}
	}

	private void onActivity(MinecraftServer server, JsonObject data) {
		String kind = str(data, "kind", "");
		String result = str(data, "result", "");
		if (result.isEmpty() || "pending".equals(result)) return;
		String text;
		if ("quote".equals(kind)) {
			quoteCount++;
			JsonObject amount = obj(data, "amount");
			String money = amount == null ? "" : CrewkitText.money(num(amount, "amount", 0), str(amount, "currency", "SGD"));
			String head = CrewkitText.of("QUOTE #" + quoteCount, INK, true);
			String price = CrewkitText.of(money.isEmpty() ? "\n" : "\n" + money, INK, true);
			String stamp = stamp(result);
			text = CrewkitText.join(head, price, stamp);
			queuePrint(server, text, 0.42f, true, result);
		} else if ("probe".equals(kind) || "search".equals(kind)) {
			String name = str(data, "realName", "");
			if (name.isEmpty()) name = str(data, "text", "item");
			if (name.length() > 20) name = name.substring(0, 19) + "…";
			int qty = (int) num(data, "qty", 0);
			String label = ("search".equals(kind) ? "FIND? " : "STOCK? ") + name + (qty > 0 ? " x" + qty : "");
			text = CrewkitText.join(CrewkitText.of(label, INK, false), CrewkitText.of("\n→ " + verdict(result), color(result), true));
			queuePrint(server, text, 0.3f, false, result);
		}
	}

	private static String stamp(String result) {
		return switch (result) {
			case "over" -> CrewkitText.of("\n[ OVER ]", CrewkitText.RED, true);
			case "ok" -> CrewkitText.of("\n[ OK ]", 0x1FA85A, true);
			default -> CrewkitText.of("\n[ " + result.toUpperCase(Locale.ROOT) + " ]", CrewkitText.AMBER, true);
		};
	}

	private static String verdict(String result) {
		return switch (result) {
			case "ok" -> "IN STOCK";
			case "sold_out" -> "SOLD OUT";
			case "over" -> "OVER";
			case "busy" -> "BUSY";
			case "error" -> "ERROR";
			default -> result.toUpperCase(Locale.ROOT);
		};
	}

	private static int color(String result) {
		return switch (result) {
			case "ok" -> 0x1FA85A;
			case "busy" -> 0xD08A00;
			default -> CrewkitText.RED;
		};
	}

	/** Prints are spaced out so a burst of events reads as a busy printer, not overlapping paper. */
	private void queuePrint(MinecraftServer server, String text, float scale, boolean big, String result) {
		int now = server.getTickCount();
		int start = Math.max(now, nextPrintTick);
		nextPrintTick = start + PRINT_GAP;
		int gen = generation;
		Runnable job = () -> {
			if (gen != generation) return;
			try {
				print(server, text, scale, big, result);
			} catch (RuntimeException e) {
				CrewkitDispatcher.LOGGER.warn("CrewKit printer: print failed", e);
			}
		};
		if (start <= now) job.run();
		else CrewkitSchedule.after(start - now, job);
	}

	private void ensureBody(MinecraftServer server) {
		if (!body.isEmpty() && body.get(0).alive()) return;
		for (CrewkitDisplay d : body) d.kill();
		body.clear();
		Vec3 b = rel(BODY);
		body.add(CrewkitDisplays.block(server, b, TAG, "minecraft:light_gray_concrete", 0.6f, 0.3f, 0.45f, ""));
		body.add(CrewkitDisplays.block(server, b.add(0.08, 0.3, 0.12), TAG, "minecraft:black_concrete", 0.44f, 0.02f, 0.12f, ""));
		body.add(CrewkitDisplays.block(server, b.add(0.42, 0.12, 0.455), TAG, "minecraft:lime_concrete", 0.08f, 0.06f, 0.01f, ""));
	}

	private void print(MinecraftServer server, String text, float scale, boolean big, String result) {
		ensureBody(server);
		int gen = generation;
		Vec3 slot = rel(SLOT);
		CrewkitDisplay slip = CrewkitDisplays.text(server, slot, TAG, text, scale, "");
		slip.merge("{background:" + CrewkitText.argb(255, PAPER) + ",shadow:0b,line_width:140,"
				+ "transformation:" + CrewkitDisplays.transformation(scale, 0.05f, scale, 0, -0.3f, 0, 0) + "}");
		CrewkitSounds.play(server, slot, "minecraft:ui.cartography_table.take_result", 0.6f, 1.3f);
		// Chunky feed: three stepped pushes out of the slot, each with a clack.
		for (int i = 1; i <= 3; i++) {
			final int step = i;
			CrewkitSchedule.after(2 + step * 4, () -> {
				if (gen != generation) return;
				float f = step / 3f;
				slip.transform(scale, scale * f, scale, 0, -0.3f * (1 - f), 0, 0, 3);
				CrewkitSounds.play(server, slot, "minecraft:block.piston.contract", 0.25f, 1.9f + step * 0.03f);
			});
		}
		if (big) {
			CrewkitSchedule.after(18, () -> {
				if (gen != generation) return;
				slip.transform(scale * 1.08f, scale * 1.08f, scale, 0, 0, 0, "over".equals(result) ? -0.08f : 0.06f, 2);
				if ("over".equals(result)) CrewkitSounds.play(server, slot, "minecraft:block.anvil.land", 0.3f, 1.7f);
				else CrewkitSounds.play(server, slot, "minecraft:entity.experience_orb.pickup", 0.5f, 1.4f);
			});
		}
		int hold = big ? 40 : 26;
		CrewkitSchedule.after(hold, () -> {
			if (gen != generation) return;
			flyToPile(server, slip);
		});
	}

	private void flyToPile(MinecraftServer server, CrewkitDisplay slip) {
		int gen = generation;
		int n = pile.size();
		Vec3 base = rel(PILE);
		double x = base.x + (random.nextDouble() - 0.5) * 0.35;
		double y = base.y + Math.min(n, PILE_CAP) * 0.035;
		double z = base.z + n * 0.004;
		float roll = (float) ((random.nextDouble() - 0.5) * 0.9);
		float pileScale = 0.2f;
		// Flutter: pop up and wobble, then settle on the pile.
		Vec3 up = slip.pos().add(0.4, 0.55, 0.1);
		slip.moveTo(up, 8);
		slip.transform(0.3f, 0.3f, 0.3f, 0, 0, 0, roll * -1.8f, 8);
		CrewkitSounds.play(server, up, "minecraft:item.book.page_turn", 0.7f, 1.2f + random.nextFloat() * 0.3f);
		CrewkitSchedule.after(9, () -> {
			if (gen != generation) return;
			slip.moveTo(new Vec3(x, y, z), 10);
			slip.transform(pileScale, pileScale, pileScale, 0, 0, 0, roll, 10);
			slip.merge(String.format(Locale.ROOT, "{Rotation:[%.1ff,%.1ff]}", (random.nextFloat() - 0.5f) * 30f, -20f - random.nextFloat() * 15f));
		});
		CrewkitSchedule.after(19, () -> {
			if (gen != generation) return;
			CrewkitSounds.play(server, new Vec3(x, y, z), "minecraft:item.book.put", 0.5f, 1.4f);
		});
		pile.addLast(new Slip(slip, x, y, z));
		while (pile.size() > PILE_CAP) crumple(server, pile.removeFirst());
	}

	private void crumple(MinecraftServer server, Slip old) {
		CrewkitDisplay d = old.display();
		d.transform(0.02f, 0.02f, 0.02f, 0, 0.05f, 0, 3.0f, 6);
		CrewkitSounds.play(server, d.pos(), "minecraft:block.azalea_leaves.break", 0.5f, 1.6f);
		particle(server, "minecraft:poof", d.pos().add(0, 0.1, 0), 0.05, 4, 0.02);
		CrewkitSchedule.after(7, d::kill);
	}

	private void burst(MinecraftServer server) {
		if (pile.isEmpty()) return;
		int gen = generation;
		Vec3 center = rel(PILE).add(0, 0.4, 0);
		CrewkitSounds.play(server, center, "minecraft:entity.firework_rocket.blast", 0.9f, 1.1f);
		CrewkitSchedule.after(4, () -> CrewkitSounds.play(server, center, "minecraft:entity.firework_rocket.twinkle", 0.8f, 1.2f));
		for (Slip s : pile) {
			CrewkitDisplay d = s.display();
			Vec3 to = d.pos().add((random.nextDouble() - 0.5) * 3, 1 + random.nextDouble() * 1.5, (random.nextDouble() - 0.2) * 1.5);
			d.moveTo(to, 10);
			d.transform(0.05f, 0.05f, 0.05f, 0, 0, 0, (float) ((random.nextDouble() - 0.5) * 12), 10);
			CrewkitSchedule.after(11, d::kill);
		}
		pile.clear();
		String[] colors = {"[1.0,0.3,0.25]", "[0.25,0.85,0.5]", "[0.3,0.55,1.0]", "[1.0,0.75,0.1]", "[0.95,0.4,0.9]"};
		for (int wave = 0; wave < 3; wave++) {
			final int w = wave;
			CrewkitSchedule.after(1 + wave * 4, () -> {
				if (gen != generation) return;
				for (String c : colors) {
					particle(server, "minecraft:dust{color:" + c + ",scale:1.4}", center.add(0, w * 0.3, 0), 0.9, 14, 0.4);
				}
				particle(server, "minecraft:firework", center, 0.4, 20, 0.15);
			});
		}
	}

	private static void particle(MinecraftServer server, String particle, Vec3 p, double spread, int count, double speed) {
		CrewkitDisplays.run(server, String.format(Locale.ROOT, "particle %s %.3f %.3f %.3f %.2f %.2f %.2f %.2f %d force",
				particle, p.x, p.y, p.z, spread, spread, spread, speed, count));
	}

	private static Vec3 rel(double[] r) {
		var o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + r[0], o.getY() + r[1], o.getZ() + r[2]);
	}

	private static String str(JsonObject o, String key, String fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}

	private static double num(JsonObject o, String key, double fallback) {
		JsonElement e = o.get(key);
		try {
			return e != null && e.isJsonPrimitive() ? e.getAsDouble() : fallback;
		} catch (RuntimeException ex) {
			return fallback;
		}
	}

	private static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}
}
