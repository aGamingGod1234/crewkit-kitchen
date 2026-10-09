package dev.agaminggod.arenaagents.crewkit.fun.ticker;

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
import dev.agaminggod.arenaagents.crewkit.core.CrewkitTween;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Stock-ticker quote history above the pass: each quote total slides in from the right, red while over
 * budget, green once within it, with a sparkline underneath. Every drop in the quote showers gold
 * nuggets into a glass jar on the pass whose label counts up the total saved versus the first quote.
 */
public final class QuoteTickerFeature implements CrewkitFeature {
	private static final String TAG = "ck_ticker";
	private static final int MAX_ENTRIES = 5;
	private static final int MAX_COINS = 20;
	private static final float TICKER_SCALE = 1.5f;
	private static final float SPARK_SCALE = 1.3f;
	private static final float LABEL_SCALE = 0.6f;
	private static final float COIN_SCALE = 0.45f;
	private static final String BARS = "▁▂▃▄▅▆▇█";

	// Relative to the set origin. Ticker spans x 4..16 above the pass (z 8..9); jar sits on the pass top (y 2.0).
	private static final double[] TICKER = {10.0, 5.5, 8.5};
	private static final double[] SPARK = {10.0, 5.0, 8.5};
	private static final double[] JAR = {13.65, 2.0, 8.2};
	private static final double[] LABEL = {14.0, 2.75, 8.95};

	private record Quote(double total, boolean over) {}

	private final List<Quote> history = new ArrayList<>();
	private final CrewkitTween saved = new CrewkitTween(0);
	private final Random random = new Random();
	private String currency = "SGD";
	private CrewkitDisplay ticker;
	private CrewkitDisplay spark;
	private CrewkitDisplay label;
	private int coinsAlive;
	private int generation;
	private String lastLabel = "";

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		try {
			if ("reset".equals(event)) {
				reset(server);
			} else if ("quote".equals(event)) {
				onQuote(server, data);
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit ticker: event {} failed", event, e);
		}
	}

	private void onQuote(MinecraftServer server, JsonObject data) {
		JsonObject totalObj = object(data, "total");
		if (totalObj == null) return;
		double total = number(totalObj, "amount", Double.NaN);
		if (Double.isNaN(total)) return;
		String cur = string(totalObj, "currency");
		if (cur != null) currency = cur;
		JsonObject left = object(data, "budgetRemaining");
		boolean over = left != null && number(left, "amount", 0) < 0;

		ensureSpawned(server);
		double previous = history.isEmpty() ? Double.NaN : history.get(history.size() - 1).total();
		history.add(new Quote(total, over));
		if (history.size() > 64) history.subList(1, history.size() - 32).clear(); // keep the first quote as baseline
		int now = server.getTickCount();

		// Slide the strip in from the right: jump to an offset, then ease home once the client has the start state.
		CrewkitDisplay t = ticker;
		CrewkitDisplay s = spark;
		t.text(tickerText());
		s.text(sparkText());
		t.transform(TICKER_SCALE, 2.5f, 0, 0, 0);
		s.transform(SPARK_SCALE, 2.5f, 0, 0, 0);
		int gen = generation;
		CrewkitSchedule.after(2, () -> {
			if (gen != generation) return;
			t.transform(TICKER_SCALE, 0, 0, 0, 10);
			s.transform(SPARK_SCALE, 0, 0, 0, 12);
		});
		CrewkitSounds.play(server, t.pos(), "minecraft:ui.button.click", 0.5f, 1.4f);

		double target = Math.max(0, history.get(0).total() - total);
		if (!Double.isNaN(previous) && total < previous - 0.005) {
			double drop = previous - total;
			int coins = 6 + (int) Math.min(4, Math.floor(drop / 15.0));
			showerCoins(server, coins, gen);
			// Count up as the coins land.
			CrewkitSchedule.after(14, () -> {
				if (gen != generation) return;
				saved.retarget(target, 30, server.getTickCount());
			});
		} else if (history.size() > 1) {
			saved.retarget(target, 20, now);
		}
	}

	private void ensureSpawned(MinecraftServer server) {
		if (ticker != null && ticker.alive()) return;
		CrewkitDisplays.killTag(server, TAG);
		history.clear();
		coinsAlive = 0;
		String bg = "background:" + CrewkitText.argb(0xD8, 0x111318);
		ticker = CrewkitDisplays.text(server, rel(TICKER), TAG, CrewkitText.of(" ", CrewkitText.WHITE, true), TICKER_SCALE, "");
		ticker.merge("{" + bg + ",line_width:600}");
		spark = CrewkitDisplays.text(server, rel(SPARK), TAG, CrewkitText.of(" ", CrewkitText.WHITE, false), SPARK_SCALE, "");
		spark.merge("{" + bg + ",line_width:600}");
		CrewkitDisplays.block(server, rel(JAR), TAG, "minecraft:glass", 0.7f, 0.7f, 0.7f, "");
		label = CrewkitDisplays.text(server, rel(LABEL), TAG, labelText(0), LABEL_SCALE, "");
		label.merge("{background:" + CrewkitText.argb(0xC0, 0x111318) + "}");
		saved.snap(0);
		lastLabel = "";
	}

	private void showerCoins(MinecraftServer server, int count, int gen) {
		Vec3 target = rel(new double[] {JAR[0] + 0.35, JAR[1] + 0.3, JAR[2] + 0.35});
		for (int i = 0; i < count; i++) {
			if (coinsAlive >= MAX_COINS) break;
			coinsAlive++;
			int delay = 1 + i * 2;
			double sx = 9.0 + random.nextDouble() * 6.5;
			Vec3 start = rel(new double[] {sx, TICKER[1] - 0.1, TICKER[2] + (random.nextDouble() - 0.5) * 0.4});
			double apex = 0.6 + random.nextDouble() * 0.8;
			float pitch = 1.2f + random.nextFloat() * 0.8f;
			CrewkitSchedule.after(delay, () -> {
				if (gen != generation) {
					coinsAlive = Math.max(0, coinsAlive - 1);
					return;
				}
				CrewkitDisplay coin = CrewkitDisplays.item(server, start, TAG, "minecraft:gold_nugget", COIN_SCALE, "fixed", "");
				// Parabolic arc in 4 lerped hops: rise a little, then fall into the jar, spinning.
				int hop = 4;
				for (int step = 1; step <= 4; step++) {
					double t = step / 4.0;
					Vec3 p = start.lerp(target, t).add(0, apex * 4 * t * (1 - t), 0);
					float roll = (float) (step * 1.6);
					CrewkitSchedule.after(2 + (step - 1) * hop, () -> {
						if (gen != generation) return;
						coin.moveTo(p, hop);
						coin.transform(COIN_SCALE, COIN_SCALE, COIN_SCALE, 0, 0, 0, roll, hop);
					});
				}
				CrewkitSchedule.after(2 + 4 * hop, () -> {
					if (gen != generation) return;
					CrewkitSounds.play(server, target, "minecraft:entity.experience_orb.pickup", 0.6f, pitch);
					coin.kill();
					coinsAlive = Math.max(0, coinsAlive - 1);
				});
			});
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		if (label == null) return;
		int now = server.getTickCount();
		if (now % 2 != 0) return;
		try {
			String text = labelText(saved.value(now));
			if (!text.equals(lastLabel)) {
				lastLabel = text;
				label.text(text);
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.debug("CrewKit ticker tick failed", e);
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		generation++;
		try {
			CrewkitDisplays.killTag(server, TAG);
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.debug("CrewKit ticker reset failed", e);
		}
		history.clear();
		saved.snap(0);
		ticker = null;
		spark = null;
		label = null;
		coinsAlive = 0;
		lastLabel = "";
		currency = "SGD";
	}

	// ---- text ----

	private String tickerText() {
		int from = Math.max(0, history.size() - MAX_ENTRIES);
		List<String> parts = new ArrayList<>();
		for (int i = from; i < history.size(); i++) {
			Quote q = history.get(i);
			boolean newest = i == history.size() - 1;
			if (i > from) {
				double prev = history.get(i - 1).total();
				if (q.total() < prev - 0.005) parts.add(CrewkitText.of(" ▼ ", CrewkitText.GREEN, true));
				else if (q.total() > prev + 0.005) parts.add(CrewkitText.of(" ▲ ", CrewkitText.RED, true));
				else parts.add(CrewkitText.of(" = ", CrewkitText.MUTED, true));
			}
			String amount = CrewkitText.money(q.total(), currency);
			if (i != from) amount = amount.replace(CrewkitText.symbol(currency), "");
			int colour = q.over() ? CrewkitText.RED : CrewkitText.GREEN;
			parts.add(CrewkitText.of(amount, newest ? colour : dim(colour), newest));
			if (newest) parts.add(CrewkitText.of(q.over() ? " ✖" : " ✔", colour, true));
		}
		return CrewkitText.join(parts.toArray(new String[0]));
	}

	private String sparkText() {
		int from = Math.max(0, history.size() - 8);
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		for (int i = from; i < history.size(); i++) {
			min = Math.min(min, history.get(i).total());
			max = Math.max(max, history.get(i).total());
		}
		List<String> parts = new ArrayList<>();
		for (int i = from; i < history.size(); i++) {
			Quote q = history.get(i);
			int level = max - min < 0.005 ? BARS.length() / 2 : (int) Math.round((q.total() - min) / (max - min) * (BARS.length() - 1));
			level = Math.max(0, Math.min(BARS.length() - 1, level));
			String bar = BARS.charAt(level) + (i < history.size() - 1 ? " " : "");
			parts.add(CrewkitText.of(bar, q.over() ? CrewkitText.RED : CrewkitText.GREEN, false));
		}
		return CrewkitText.join(parts.toArray(new String[0]));
	}

	private String labelText(double amount) {
		return CrewkitText.join(
				CrewkitText.of("SAVED ", CrewkitText.AMBER, true),
				CrewkitText.of(CrewkitText.money(amount, currency), CrewkitText.GREEN, true));
	}

	private static int dim(int rgb) {
		int r = (rgb >> 16 & 0xFF) * 3 / 4;
		int g = (rgb >> 8 & 0xFF) * 3 / 4;
		int b = (rgb & 0xFF) * 3 / 4;
		return r << 16 | g << 8 | b;
	}

	private static Vec3 rel(double[] p) {
		BlockPos o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + p[0], o.getY() + p[1], o.getZ() + p[2]);
	}

	// ---- json helpers (amounts may arrive as numbers or decimal strings) ----

	private static JsonObject object(JsonObject data, String key) {
		JsonElement element = data == null ? null : data.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static double number(JsonObject data, String key, double fallback) {
		JsonElement element = data == null ? null : data.get(key);
		if (element == null || !element.isJsonPrimitive()) return fallback;
		try {
			return Double.parseDouble(element.getAsString().trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String string(JsonObject data, String key) {
		JsonElement element = data == null ? null : data.get(key);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}
}
