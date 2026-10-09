package dev.agaminggod.arenaagents.crewkit.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplays;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSchedule;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSounds;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitText;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * On {@code record}, hands every player near the kitchen a written book "CrewKit receipt" by Chef with the
 * order lines, shipping, total vs budget and variance. With nobody nearby the book drops at the pass.
 * Spawned only through vanilla commands, so the book uses the 26.1 written_book_content component.
 */
public final class ReceiptFeature implements CrewkitFeature {
	private static final double RADIUS = 32;
	private static final int LINES_PER_PAGE = 3;
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'SGT'", Locale.ROOT);

	private record Line(String name, String merchant, double unit, String currency, int qty) {}

	private final Map<String, Line> lines = new LinkedHashMap<>();
	private double shipping = Double.NaN;
	private String currency = "SGD";
	private String title = "";

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		if (data == null) data = new JsonObject();
		switch (event) {
			case "brief" -> {
				lines.clear();
				shipping = Double.NaN;
				title = str(data, "title", "");
				JsonObject b = obj(data, "budget");
				if (b != null) currency = str(b, "currency", currency);
			}
			case "item_added" -> {
				String id = str(data, "id", "item" + lines.size());
				JsonObject price = obj(data, "unitPrice");
				int qty = (int) Math.max(1, num(data, "qty", 1));
				Line old = lines.get(id);
				lines.put(id, new Line(str(data, "realName", id), str(data, "merchant", ""),
					price == null ? 0 : num(price, "amount", 0), price == null ? currency : str(price, "currency", currency),
					old == null ? qty : old.qty() + qty));
			}
			case "item_removed" -> {
				String id = str(data, "id", "");
				Line old = lines.get(id);
				if (old == null) break;
				int left = old.qty() - (int) num(data, "qtyRemoved", old.qty());
				if (left <= 0) lines.remove(id);
				else lines.put(id, new Line(old.name(), old.merchant(), old.unit(), old.currency(), left));
			}
			case "quote" -> {
				JsonObject ship = obj(data, "shipping");
				if (ship != null) shipping = num(ship, "amount", 0);
			}
			case "record" -> giveReceipt(server, data);
			case "reset" -> reset(server);
			default -> { }
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		lines.clear();
		shipping = Double.NaN;
		title = "";
		currency = "SGD";
	}

	private void giveReceipt(MinecraftServer server, JsonObject data) {
		String cur = str(data, "currency", currency);
		double budget = num(data, "budget", 0);
		double quoted = num(data, "quoted", 0);
		double charged = num(data, "charged", 0);
		double variance = num(data, "variance", charged - quoted);
		String orderId = str(data, "orderId", "-");
		String item = "minecraft:written_book[written_book_content=" + bookSnbt(cur, budget, charged, variance, orderId) + "]";

		Vec3 centre = new Vec3(CrewkitAnchors.origin.getX() + 14, CrewkitAnchors.origin.getY() + 2, CrewkitAnchors.origin.getZ() + 11);
		List<ServerPlayer> near = new ArrayList<>();
		for (ServerPlayer p : server.overworld().players()) {
			if (p.position().distanceTo(centre) <= RADIUS) near.add(p);
		}
		// Wait for the bill board to finish typing so the book lands as the last beat.
		CrewkitSchedule.after(20, () -> {
			if (near.isEmpty()) {
				Vec3 pass = new Vec3(CrewkitAnchors.origin.getX() + 7.5, CrewkitAnchors.origin.getY() + 2.3, CrewkitAnchors.origin.getZ() + 9.9);
				CrewkitDisplays.run(server, String.format(Locale.ROOT, "summon minecraft:item %.3f %.3f %.3f {Tags:[\"crewkit\",\"ck_receipt\"],PickupDelay:10s,Motion:[0d,0.15d,0.1d],Item:{id:\"minecraft:written_book\",count:1,components:{\"minecraft:written_book_content\":%s}}}",
					pass.x, pass.y, pass.z, bookSnbt(cur, budget, charged, variance, orderId)));
				CrewkitSounds.play(server, pass, "minecraft:item.book.page_turn", 1.0f, 1.0f);
				return;
			}
			for (ServerPlayer p : near) {
				CrewkitDisplays.run(server, "give " + p.getStringUUID() + " " + item);
				CrewkitSounds.play(server, p.position(), "minecraft:item.book.page_turn", 1.0f, 1.0f);
			}
		});
	}

	private String bookSnbt(String cur, double budget, double charged, double variance, String orderId) {
		List<String> pages = new ArrayList<>();
		String merchant = lines.values().stream().map(Line::merchant).filter(m -> !m.isBlank()).distinct()
			.reduce((a, b) -> a + ", " + b).orElse("-");
		pages.add("CREWKIT RECEIPT\n\n" + (title.isBlank() ? "" : clip(title, 60) + "\n\n") + "Order\n" + orderId + "\n\nMerchant\n" + merchant
			+ "\n\nPaid by Chef via Reap");
		List<Line> all = new ArrayList<>(lines.values());
		for (int i = 0; i < all.size(); i += LINES_PER_PAGE) {
			StringBuilder page = new StringBuilder(i == 0 ? "ITEMS\n\n" : "ITEMS (cont.)\n\n");
			for (int k = i; k < Math.min(all.size(), i + LINES_PER_PAGE); k++) {
				Line l = all.get(k);
				page.append(l.qty()).append(" × ").append(clip(l.name(), 34)).append("\n  @ ")
					.append(CrewkitText.money(l.unit(), l.currency())).append(" = ")
					.append(CrewkitText.money(l.unit() * l.qty(), l.currency())).append("\n\n");
			}
			pages.add(page.toString().stripTrailing());
		}
		String verdict = charged <= budget + 0.004 ? "within budget" : "OVER budget";
		pages.add("TOTALS\n\nShipping\n" + (Double.isNaN(shipping) ? "-" : CrewkitText.money(shipping, cur))
			+ "\n\nCharged " + CrewkitText.money(charged, cur) + "\nBudget  " + CrewkitText.money(budget, cur)
			+ "\n(" + verdict + ")\n\nVariance " + (variance > 0.004 ? "+" : "") + CrewkitText.money(variance, cur)
			+ "\n\n" + ZonedDateTime.now(ZoneId.of("Asia/Singapore")).format(STAMP)
			+ "\n\nReap sandbox — no real money moved");
		StringBuilder sb = new StringBuilder("{title:\"CrewKit receipt\",author:\"Chef\",resolved:1b,pages:[");
		for (int i = 0; i < pages.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append('"').append(CrewkitText.escape(pages.get(i))).append('"');
		}
		return sb.append("]}").toString();
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	private static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static String str(JsonObject o, String key, String fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}

	private static double num(JsonObject o, String key, double fallback) {
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive()) return fallback;
		try {
			return Double.parseDouble(e.getAsString().trim());
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}
}
