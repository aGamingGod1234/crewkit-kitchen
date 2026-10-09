package dev.agaminggod.arenaagents.crewkit.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * Back-wall boards. Budget board at ck_budget: big remaining number that rolls with ticks, a bar, ticket
 * timer, Reap-calls counter and a LIVE SANDBOX label. Bill board at ck_ledger: budget, quoted, charged,
 * variance and order id stamped one row at a time on {@code record}.
 */
public final class BoardsFeature implements CrewkitFeature {
	static final String TAG = "ck_boards";

	/** Displays sit just in front of the wall face (anchor z is the wall block centre). */
	private static final double WALL_FRONT = 0.55;
	private static final float BAR_WIDTH = 7.6f;
	private static final float BAR_HEIGHT = 0.32f;
	private static final float NUMBER_SCALE = 5.0f;
	private static final int ROLL_TICKS = 24;
	private static final int MAX_ROLL_SOUNDS = 8;

	private static final String[] ROW_LABELS = {"BUDGET", "QUOTED", "CHARGED", "VARIANCE", "ORDER"};

	// Budget board
	private CrewkitDisplay panel, header, sandbox, number, status, track, fill, timer, calls;
	private final CrewkitTween remaining = new CrewkitTween(0);
	private double budget;
	private String currency = "SGD";
	private double cart;
	private final Map<String, Double> unitPrices = new HashMap<>();
	private String lastNumberText = "";
	private int lastNumberColor = -1;
	private float lastFillWidth = -1;
	private String lastFillBlock = "";
	private int rollSounds;
	private int lastRollSoundTick;
	private int timerStart = -1;
	private int timerStop = -1;
	private int timerColor = CrewkitText.WHITE;
	private String lastTimerText = "";
	private int callCount;

	// Bill board
	private CrewkitDisplay billPanel, billTitle;
	private final CrewkitDisplay[] rowLabels = new CrewkitDisplay[ROW_LABELS.length];
	private final CrewkitDisplay[] rowValues = new CrewkitDisplay[ROW_LABELS.length];

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		int now = server.getTickCount();
		switch (event) {
			case "brief" -> {
				JsonObject money = object(data, "budget");
				budget = amount(money);
				currency = currency(money, currency);
				cart = 0;
				unitPrices.clear();
				callCount = 0;
				ensureBudgetBoard(server);
				ensureBill(server);
				remaining.snap(0);
				rollTo(budget, 30, now);
				setStatus("OF " + CrewkitText.money(budget, currency) + " ALL-IN", CrewkitText.MUTED);
				track.merge("{block_state:{Name:\"minecraft:gray_concrete\"}}");
				timerStart = now;
				timerStop = -1;
				timerColor = CrewkitText.WHITE;
				updateCalls(server, 0, false);
				CrewkitSounds.play(server, timer.pos(), "minecraft:block.note_block.hat", 0.8f, 1.5f);
			}
			case "item_added" -> {
				ensureBudgetBoard(server);
				String id = string(data, "id");
				double unit = amount(object(data, "unitPrice"));
				double qty = number(data, "qty", 1);
				if (id != null) unitPrices.put(id, unit);
				cart += unit * qty;
				rollTo(budget - cart, ROLL_TICKS, now);
				updateCalls(server, callCount + 1, true);
			}
			case "item_removed" -> {
				ensureBudgetBoard(server);
				Double unit = unitPrices.get(string(data, "id"));
				if (unit != null) {
					cart = Math.max(0, cart - unit * number(data, "qtyRemoved", 1));
					rollTo(budget - cart, ROLL_TICKS, now);
				}
			}
			case "quote" -> {
				ensureBudgetBoard(server);
				JsonObject left = object(data, "budgetRemaining");
				double value = left != null ? amount(left) : budget - amount(object(data, "total"));
				rollTo(value, ROLL_TICKS, now);
				JsonObject shipping = object(data, "shipping");
				setStatus("QUOTED " + CrewkitText.money(amount(object(data, "total")), currency)
						+ (shipping != null ? "  (SHIP " + CrewkitText.money(amount(shipping), currency) + ")" : ""), CrewkitText.WHITE);
			}
			case "gate_blocked" -> {
				ensureBudgetBoard(server);
				double over = Math.abs(amount(object(data, "over")));
				rollTo(-over, ROLL_TICKS + 6, now);
				setStatus("OVER BY " + CrewkitText.money(over, currency) + " - CHECKOUT BLOCKED", CrewkitText.RED);
				track.merge("{block_state:{Name:\"minecraft:red_concrete\"}}");
				pulse(number, NUMBER_SCALE, 1.15f);
			}
			case "gate_passed" -> {
				ensureBudgetBoard(server);
				double total = amount(object(data, "total"));
				rollTo(budget - total, ROLL_TICKS + 6, now);
				setStatus("WITHIN BUDGET - " + CrewkitText.money(total, currency), CrewkitText.GREEN);
				track.merge("{block_state:{Name:\"minecraft:gray_concrete\"}}");
			}
			case "checkout" -> {
				ensureBudgetBoard(server);
				setStatus("WAITING FOR HUMAN APPROVAL", CrewkitText.BLUE);
			}
			case "completed" -> {
				ensureBudgetBoard(server);
				JsonObject paid = object(data, "finalAmount");
				if (paid != null) rollTo(budget - amount(paid), ROLL_TICKS, now);
				setStatus("ORDER PLACED - " + (paid != null ? CrewkitText.money(amount(paid), currency) : ""), CrewkitText.GREEN);
				stopTimer(now, CrewkitText.GREEN);
			}
			case "failed", "expired" -> {
				ensureBudgetBoard(server);
				String reason = string(data, "reason");
				setStatus(event.toUpperCase(Locale.ROOT) + (reason != null ? " - " + clip(reason, 34) : ""), CrewkitText.RED);
				stopTimer(now, CrewkitText.RED);
			}
			case "calls" -> {
				ensureBudgetBoard(server);
				updateCalls(server, (int) number(data, "count", callCount), true);
			}
			case "record" -> stampRecord(server, data);
			default -> {
			}
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		if (number == null) return;
		int now = server.getTickCount();
		double value = remaining.value(now);
		renderNumber(value);
		renderBar(value);
		if (remaining.active(now) && rollSounds < MAX_ROLL_SOUNDS && now - lastRollSoundTick >= 3) {
			rollSounds++;
			lastRollSoundTick = now;
			CrewkitSounds.play(server, number.pos(), "minecraft:block.note_block.hat", 0.3f, 1.8f);
		}
		if (timerStart >= 0) {
			int end = timerStop >= 0 ? timerStop : now;
			int seconds = Math.max(0, (end - timerStart) / 20);
			String text = String.format(Locale.ROOT, "TICKET %02d:%02d", seconds / 60, seconds % 60);
			if (!text.equals(lastTimerText)) {
				lastTimerText = text;
				timer.text(label(text, timerColor));
			}
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		CrewkitDisplays.killTag(server, TAG);
		panel = header = sandbox = number = status = track = fill = timer = calls = null;
		billPanel = billTitle = null;
		java.util.Arrays.fill(rowLabels, null);
		java.util.Arrays.fill(rowValues, null);
		remaining.snap(0);
		budget = 0;
		cart = 0;
		currency = "SGD";
		unitPrices.clear();
		lastNumberText = "";
		lastNumberColor = -1;
		lastFillWidth = -1;
		lastFillBlock = "";
		rollSounds = 0;
		timerStart = timerStop = -1;
		timerColor = CrewkitText.WHITE;
		lastTimerText = "";
		callCount = 0;
	}

	// ---- budget board ----

	private void ensureBudgetBoard(MinecraftServer server) {
		if (number != null) return;
		Vec3 a = CrewkitAnchors.at(CrewkitAnchors.BUDGET);
		double x = a.x, y = a.y, z = a.z + WALL_FRONT;
		panel = CrewkitDisplays.block(server, new Vec3(x - 4.5, y - 2.2, z - 0.04), TAG, "minecraft:black_concrete", 9f, 4.9f, 0.02f, "");
		header = CrewkitDisplays.text(server, new Vec3(x - 1.2, y + 1.95, z), TAG, CrewkitText.of("BUDGET LEFT", CrewkitText.MUTED, true), 1.9f, "");
		sandbox = CrewkitDisplays.text(server, new Vec3(x + 2.9, y + 1.95, z), TAG, CrewkitText.of(" LIVE SANDBOX ", CrewkitText.WHITE, true), 1.5f,
				"background:" + CrewkitText.argb(255, 0xC2410C));
		number = CrewkitDisplays.text(server, new Vec3(x, y + 0.45, z), TAG, CrewkitText.of(CrewkitText.money(0, currency), CrewkitText.GREEN, true), NUMBER_SCALE, "");
		status = CrewkitDisplays.text(server, new Vec3(x, y - 0.1, z), TAG, CrewkitText.of("", CrewkitText.MUTED, false), 1.6f, "");
		track = CrewkitDisplays.block(server, new Vec3(x - BAR_WIDTH / 2, y - 0.75, z - 0.02), TAG, "minecraft:gray_concrete", BAR_WIDTH, BAR_HEIGHT, 0.01f, "");
		fill = CrewkitDisplays.block(server, new Vec3(x - BAR_WIDTH / 2, y - 0.75, z - 0.005), TAG, "minecraft:lime_concrete", 0.001f, BAR_HEIGHT, 0.01f, "");
		timer = CrewkitDisplays.text(server, new Vec3(x - 2.2, y - 1.7, z), TAG, label("TICKET 00:00", CrewkitText.WHITE), 1.9f, "");
		calls = CrewkitDisplays.text(server, new Vec3(x + 2.2, y - 1.7, z), TAG, callsText(0), 1.9f, "");
		lastFillWidth = 0.001f;
		lastFillBlock = "minecraft:lime_concrete";
	}

	private void rollTo(double target, int ticks, int now) {
		if (Math.abs(target - remaining.target()) < 0.005 && !remaining.active(now)) return;
		remaining.retarget(target, ticks, now);
		rollSounds = 0;
	}

	private void renderNumber(double value) {
		String text = CrewkitText.money(value, currency);
		int color = colorFor(value);
		if (text.equals(lastNumberText) && color == lastNumberColor) return;
		lastNumberText = text;
		lastNumberColor = color;
		number.text(CrewkitText.of(text, color, true));
	}

	private void renderBar(double value) {
		double ratio = budget > 0 ? Math.max(0, Math.min(1, value / budget)) : 0;
		float width = (float) Math.max(0.001, BAR_WIDTH * ratio);
		String block = switch (colorFor(value)) {
			case CrewkitText.GREEN -> "minecraft:lime_concrete";
			case CrewkitText.AMBER -> "minecraft:orange_concrete";
			default -> "minecraft:red_concrete";
		};
		if (!block.equals(lastFillBlock)) {
			lastFillBlock = block;
			fill.merge("{block_state:{Name:\"" + block + "\"}}");
		}
		// The number already eases every tick; give the bar a short interpolation so it glides between updates.
		if (Math.abs(width - lastFillWidth) > 0.01f) {
			lastFillWidth = width;
			fill.transform(width, BAR_HEIGHT, 0.01f, 0, 0, 0, 0, 3);
		}
	}

	private int colorFor(double value) {
		if (value < -0.004) return CrewkitText.RED;
		if (budget <= 0) return CrewkitText.GREEN;
		// Red means over budget only, so a tight-but-passing cart reads amber, never red.
		return value / budget < 0.25 ? CrewkitText.AMBER : CrewkitText.GREEN;
	}

	private void setStatus(String text, int color) {
		if (status != null) status.text(CrewkitText.of(text, color, true));
	}

	private void stopTimer(int now, int color) {
		if (timerStart < 0) return;
		timerStop = now;
		timerColor = color;
		lastTimerText = "";
	}

	private void updateCalls(MinecraftServer server, int count, boolean animate) {
		if (calls == null || count == callCount && animate) return;
		callCount = Math.max(0, count);
		calls.text(callsText(callCount));
		if (!animate) return;
		pulse(calls, 1.9f, 1.25f);
		CrewkitSounds.play(server, calls.pos(), "minecraft:ui.button.click", 0.4f, 1.6f);
	}

	private static String callsText(int count) {
		return CrewkitText.join(CrewkitText.of("REAP CALLS ", CrewkitText.MUTED, true), CrewkitText.of(Integer.toString(count), CrewkitText.BLUE, true));
	}

	private static String label(String text, int color) {
		return CrewkitText.of(text, color, true);
	}

	/** Scale up briefly then settle, both interpolated. */
	private static void pulse(CrewkitDisplay display, float base, float factor) {
		if (display == null) return;
		display.transform(base * factor, 0, 0, 0, 3);
		CrewkitSchedule.after(4, () -> display.transform(base, 0, 0, 0, 5));
	}

	// ---- bill board ----

	private void ensureBill(MinecraftServer server) {
		if (billTitle != null) return;
		Vec3 a = CrewkitAnchors.at(CrewkitAnchors.LEDGER);
		double x = a.x, y = a.y, z = a.z + WALL_FRONT;
		billPanel = CrewkitDisplays.block(server, new Vec3(x - 5, y - 2.4, z - 0.04), TAG, "minecraft:black_concrete", 10f, 5.1f, 0.02f, "");
		billTitle = CrewkitDisplays.text(server, new Vec3(x, y + 1.85, z), TAG, CrewkitText.of("THE BILL", CrewkitText.WHITE, true), 2.4f, "");
		for (int i = 0; i < ROW_LABELS.length; i++) {
			double rowY = y + 1.0 - i * 0.78;
			rowLabels[i] = CrewkitDisplays.text(server, new Vec3(x - 2.7, rowY, z), TAG, CrewkitText.of(ROW_LABELS[i], CrewkitText.MUTED, true), 1.9f, "");
			rowValues[i] = CrewkitDisplays.text(server, new Vec3(x + 1.9, rowY, z), TAG, CrewkitText.of("-", CrewkitText.MUTED, true), 2.1f, "");
		}
	}

	private void stampRecord(MinecraftServer server, JsonObject data) {
		ensureBill(server);
		String cur = string(data, "currency") != null ? string(data, "currency") : currency;
		double budgetValue = number(data, "budget", budget);
		double quoted = number(data, "quoted", 0);
		double charged = number(data, "charged", 0);
		double variance = number(data, "variance", budgetValue - charged);
		String orderId = string(data, "orderId");
		int verdict = charged <= budgetValue + 0.004 ? CrewkitText.GREEN : CrewkitText.RED;
		String[] values = {
				CrewkitText.money(budgetValue, cur),
				CrewkitText.money(quoted, cur),
				CrewkitText.money(charged, cur),
				(variance > 0.004 ? "+" : "") + CrewkitText.money(variance, cur),
				orderId == null ? "-" : clip(orderId, 18),
		};
		int[] colors = {CrewkitText.WHITE, CrewkitText.WHITE, CrewkitText.WHITE, verdict, CrewkitText.BLUE};
		for (int i = 0; i < values.length; i++) {
			CrewkitDisplay value = rowValues[i];
			String text = CrewkitText.of(values[i], colors[i], true);
			float rest = i == 4 ? 1.7f : 2.1f;
			int at = 1 + i * 12;
			// Stamp: swap in the text at 1.8x, then slam down to rest size; the sound lands with the slam.
			CrewkitSchedule.after(at, () -> value.text(text).transform(rest * 1.8f, 0, 0, 0, 0));
			CrewkitSchedule.after(at + 2, () -> value.transform(rest, 0, 0, 0, 3));
			CrewkitSchedule.after(at + 4, () -> CrewkitSounds.play(server, value.pos(), "minecraft:ui.cartography_table.take_result", 1.0f, 1.0f));
		}
	}

	// ---- json helpers (amounts may arrive as numbers or decimal strings) ----

	private static JsonObject object(JsonObject data, String key) {
		JsonElement element = data == null ? null : data.get(key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static double amount(JsonObject money) {
		return money == null ? 0 : number(money, "amount", 0);
	}

	private static String currency(JsonObject money, String fallback) {
		String value = money == null ? null : string(money, "currency");
		return value == null ? fallback : value;
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

	private static String clip(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}
}
