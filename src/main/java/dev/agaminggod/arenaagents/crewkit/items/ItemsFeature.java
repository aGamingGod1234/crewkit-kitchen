package dev.agaminggod.arenaagents.crewkit.items;

import carpet.patches.EntityPlayerMPFake;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.IntSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Chef head stack. item_added arcs a product from the pantry counter onto the chef's head; crosshair over a
 * stacked item fades in its label (real name, price, merchant); item_removed tumbles it off; completed hands
 * the list to the flow track via {@link #consumeStack()} and clears the stack.
 *
 * Optional stretch event "candidates": {query, options:[{realName, mcItem, price:{amount,currency}|number}], chosenIndex}
 * fans the search results out above the counter, dims and drops the rejects and glows the chosen one.
 *
 * Motions run one at a time through a queue so there is a single focal motion on screen.
 */
public final class ItemsFeature implements CrewkitFeature {
	/** What the flow track plates. qty is the purchased quantity; seats are guest names from the brief. */
	public record StackedItem(String id, String mcItem, String realName, int qty, List<String> seats) {}

	/** Pantry counter: west end of the counter line (layout: counter x 2..14, z 3..5, worktop at y 2). */
	static final double[] PANTRY = {3.5, 2.15, 4.5};

	static final double ITEM_SCALE = 0.42;
	static final double SLOT_HEIGHT = 0.36;
	/** Horizontal pitch between stack columns once the stack goes 2+ wide. */
	static final double COLUMN_PITCH = 0.46;
	/** Highest item top, relative to the set origin; the ceiling beams start at y=7. */
	static final double STACK_CEILING = 6.3;
	static final double LABEL_SCALE = 0.45;
	/** Hover label characters per line. */
	static final int LABEL_COLS = 28;
	static final int ARC_TICKS = 18;
	static final int TUMBLE_TICKS = 22;
	static final int HOVER_GRACE = 6;
	static final double HOVER_REACH = 48;

	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("crewkit-items");
	private static volatile ItemsFeature instance;
	private static List<StackedItem> handoff = List.of();

	private final List<Slot> stack = new ArrayList<>();
	private final List<Faller> fallers = new ArrayList<>();
	private final List<Candidate> fan = new ArrayList<>();
	private final Deque<IntSupplier> queue = new ArrayDeque<>();
	private final List<Scheduled> scheduled = new ArrayList<>();
	private MinecraftServer server;
	private long now;
	private long busyUntil;
	private long lastServerTick = -1;
	private boolean swept;
	private Entity chef;
	private long chefCheckedAt = -1000;
	private Slot hovered;

	static {
		ItemsDevHarness.install(); // /ckitems rehearsal command + self-tick until the dispatcher drives us
	}

	public ItemsFeature() {
		instance = this;
	}

	static ItemsFeature current() {
		return instance;
	}

	static ItemsFeature instance() {
		ItemsFeature i = instance;
		return i != null ? i : new ItemsFeature();
	}

	/**
	 * Hand-off for the flow track: the items bought in the completed order, then empties the hand-off.
	 * Before completed it returns a snapshot of the current head stack without clearing it.
	 */
	public static synchronized List<StackedItem> consumeStack() {
		if (!handoff.isEmpty()) {
			List<StackedItem> out = handoff;
			handoff = List.of();
			return out;
		}
		ItemsFeature i = instance;
		return i == null ? List.of() : i.snapshot();
	}

	// ---------------------------------------------------------------- events

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		this.server = server;
		if (data == null) data = new JsonObject();
		final JsonObject d = data;
		switch (event) {
			case "item_added" -> queue.add(() -> add(d));
			case "item_removed" -> queue.add(() -> remove(d));
			case "candidates" -> queue.add(() -> candidates(d));
			case "completed" -> {
				synchronized (ItemsFeature.class) {
					handoff = snapshot();
				}
				queue.add(this::clearStack);
			}
			case "reset" -> reset(server);
			default -> {}
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		try {
			tickInner(server);
		} catch (RuntimeException e) {
			// a visual glitch must never take the demo server down
			LOGGER.error("CrewKit items tick failed", e);
		}
	}

	private void tickInner(MinecraftServer server) {
		this.server = server;
		int serverTick = server.getTickCount();
		if (serverTick == lastServerTick) return; // dispatcher and dev harness may both tick us
		lastServerTick = serverTick;
		now++;
		ServerLevel level = server.overworld();
		if (!swept) {
			swept = true;
			Fx.cmd(server, "kill @e[tag=" + Fx.TAG + "]"); // leftovers saved with the world from an earlier session
		}

		// collect first: a task may schedule more tasks
		List<Scheduled> due = new ArrayList<>();
		for (Iterator<Scheduled> it = scheduled.iterator(); it.hasNext(); ) {
			Scheduled s = it.next();
			if (s.at <= now) {
				it.remove();
				due.add(s);
			}
		}
		for (Scheduled s : due) s.task.run();

		if (now >= busyUntil && !queue.isEmpty()) {
			int busy = queue.poll().getAsInt();
			busyUntil = now + Math.max(1, busy);
		}

		if (stack.isEmpty() && fallers.isEmpty() && fan.isEmpty()) return;
		refreshChef(level);
		Vec3 head = headTop();
		for (int i = 0; i < stack.size(); i++) stack.get(i).tick(level, i, stack.size(), head);
		for (Iterator<Faller> it = fallers.iterator(); it.hasNext(); ) if (it.next().tick(level)) it.remove();
		for (Iterator<Candidate> it = fan.iterator(); it.hasNext(); ) if (it.next().tick(level)) it.remove();
		hover(level);
	}

	@Override
	public void reset(MinecraftServer server) {
		this.server = server;
		ServerLevel level = server.overworld();
		for (Slot s : stack) s.discardAll(level);
		for (Faller f : fallers) Fx.discard(level, f.display);
		for (Candidate c : fan) c.discardAll(level);
		stack.clear();
		fallers.clear();
		fan.clear();
		queue.clear();
		scheduled.clear();
		busyUntil = now;
		hovered = null;
		synchronized (ItemsFeature.class) {
			handoff = List.of();
		}
		Fx.cmd(server, "kill @e[tag=" + Fx.TAG + "]");
	}

	// ---------------------------------------------------------------- chef

	private void refreshChef(ServerLevel level) {
		if (chef != null && chef.isAlive() && !chef.isRemoved() && now - chefCheckedAt < 40) return;
		chefCheckedAt = now;
		Vec3 anchor = CrewkitAnchors.at(CrewkitAnchors.AGENT);
		Entity best = null;
		double bestDist = 24 * 24;
		for (ServerPlayer p : level.players()) {
			if (!(p instanceof EntityPlayerMPFake) || p.isSpectator() || !p.isAlive()) continue;
			double dist = p.position().distanceToSqr(anchor);
			if (dist < bestDist) {
				bestDist = dist;
				best = p;
			}
		}
		chef = best;
	}

	private Vec3 headTop() {
		if (chef != null && chef.isAlive()) return chef.position().add(0, chef.getBbHeight() + 0.12, 0);
		return CrewkitAnchors.at(CrewkitAnchors.AGENT).add(0, 1.92, 0);
	}

	/**
	 * Slot centre on the chef's head. One column while it fits under STACK_CEILING; past that the stack
	 * becomes a grid 2 (then 3) wide, filled row by row, so the top item never reaches the beams.
	 */
	private static Vec3 slotPos(Vec3 head, int index, int count, long now) {
		double room = CrewkitAnchors.origin.getY() + STACK_CEILING - head.y - ITEM_SCALE;
		int rows = Math.max(1, (int) Math.floor(room / SLOT_HEIGHT) + 1);
		int cols = Math.max(1, Math.min(3, (count + rows - 1) / rows));
		int row = index / cols;
		int col = index % cols;
		double sway = Math.sin(now * 0.07 + row * 0.9) * 0.012 * row;
		double x = (col - (cols - 1) * 0.5) * COLUMN_PITCH;
		return head.add(x + sway, ITEM_SCALE * 0.5 + Math.min(row, rows - 1) * SLOT_HEIGHT, 0);
	}

	/** Product name on at most two lines of {@code cols}, broken at a space when possible; the rest is clipped. */
	static String wrapName(String name, int cols) {
		String s = name == null ? "" : name.strip();
		if (s.length() <= cols) return s;
		int cut = s.lastIndexOf(' ', cols);
		if (cut < cols / 2) cut = cols;
		return s.substring(0, cut).stripTrailing() + "\n" + Fx.truncate(s.substring(cut).strip(), cols);
	}

	private static Vec3 rel(double[] r) {
		var o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + r[0], o.getY() + r[1], o.getZ() + r[2]);
	}

	// ---------------------------------------------------------------- item_added

	private int add(JsonObject d) {
		String id = str(d, "id", "item-" + now);
		int qty = Math.max(1, intOf(d, "qty", 1));
		Slot existing = find(id);
		if (existing != null) {
			existing.qty += qty;
			existing.refreshBadgeAndLabel();
			existing.bounce();
			Fx.sound(server.overworld(), existing.pos, SoundEvents.ITEM_PICKUP, 0.8f, pitchFor(stack.indexOf(existing)));
			return 8;
		}
		Slot s = new Slot();
		s.id = id;
		s.realName = str(d, "realName", "Item");
		s.merchant = str(d, "merchant", "");
		s.mcItem = normalizeItem(str(d, "mcItem", "minecraft:paper"));
		JsonObject price = d.has("unitPrice") && d.get("unitPrice").isJsonObject() ? d.getAsJsonObject("unitPrice") : new JsonObject();
		s.unitPrice = dbl(price, "amount", d.has("unitPrice") && d.get("unitPrice").isJsonPrimitive() ? d.get("unitPrice").getAsDouble() : 0);
		s.currency = str(price, "currency", "SGD");
		s.qty = qty;
		s.seats = strings(d, "seats");
		s.from = rel(PANTRY);
		s.pos = s.from;
		s.flying = true;
		s.t = 0;
		s.arcTicks = queue.size() > 3 ? ARC_TICKS - 5 : ARC_TICKS;
		s.display = Fx.summon(server, "minecraft:item_display", s.from,
				"item:" + itemSnbt(s.mcItem, s.realName) + ",item_display:\"fixed\",billboard:\"vertical\",teleport_duration:2"
						+ ",CustomName:" + Fx.component(Fx.text(s.realName, null, false))
						+ ",view_range:2.0f,shadow_radius:0.25f,shadow_strength:0.6f," + Fx.tf(0.15));
		UUID display = s.display;
		later(1, () -> Fx.merge(server, display, Fx.tf(ITEM_SCALE) + "," + Fx.interp(6)));
		stack.add(s);
		return s.arcTicks + 4;
	}

	private static float pitchFor(int index) {
		return (float) Math.min(2.0, 1.0 + 0.05 * Math.max(0, index));
	}

	// ---------------------------------------------------------------- item_removed

	private int remove(JsonObject d) {
		String id = str(d, "id", "");
		Slot s = find(id);
		if (s == null) return 0;
		int removed = intOf(d, "qtyRemoved", s.qty);
		ServerLevel level = server.overworld();
		if (removed > 0 && removed < s.qty) {
			s.qty -= removed;
			s.refreshBadgeAndLabel();
			s.shake();
			Fx.sound(level, s.pos, SoundEvents.ITEM_PICKUP, 0.7f, 0.6f);
			return 10;
		}
		int index = stack.indexOf(s);
		stack.remove(s);
		if (hovered == s) hovered = null;
		s.discardExtras(level);
		Fx.sound(level, s.pos, SoundEvents.ITEM_PICKUP, 0.7f, 0.6f);
		double floorY = headTop().y - 1.92 + 0.12; // chef's feet, item rests just above the floor
		fallers.add(new Faller(s.display, s.pos, index % 2 == 0 ? 1 : -1, floorY));
		return TUMBLE_TICKS - 4;
	}

	// ---------------------------------------------------------------- completed

	private int clearStack() {
		ServerLevel level = server.overworld();
		List<Slot> leaving = new ArrayList<>(stack);
		stack.clear();
		hovered = null;
		for (int i = 0; i < leaving.size(); i++) {
			Slot s = leaving.get(i);
			s.discardExtras(level);
			int delay = (leaving.size() - 1 - i) * 2; // top first
			UUID display = s.display;
			Vec3 at = s.pos;
			later(delay, () -> Fx.merge(server, display, Fx.tf(0, 0.35, 0, new Quaternionf(), 0.0, 0.0, 0.0) + "," + Fx.interp(7)));
			later(delay + 8, () -> Fx.discard(server.overworld(), display));
			if (i == leaving.size() - 1) later(delay, () -> Fx.sound(server.overworld(), at, SoundEvents.ITEM_PICKUP, 0.6f, 1.4f));
		}
		return leaving.size() * 2 + 8;
	}

	List<StackedItem> snapshot() {
		List<StackedItem> out = new ArrayList<>();
		for (Slot s : stack) out.add(new StackedItem(s.id, s.mcItem, s.realName, s.qty, List.copyOf(s.seats)));
		return List.copyOf(out);
	}

	// ---------------------------------------------------------------- candidates (stretch)

	private int candidates(JsonObject d) {
		JsonArray options = d.has("options") && d.get("options").isJsonArray() ? d.getAsJsonArray("options") : new JsonArray();
		if (options.isEmpty()) return 0;
		int chosen = intOf(d, "chosenIndex", -1);
		int n = Math.min(options.size(), 5);
		Vec3 center = CrewkitAnchors.at(CrewkitAnchors.AGENT).add(0, 2.9, 1.2);
		Vec3 from = rel(PANTRY);
		ServerLevel level = server.overworld();
		Fx.sound(level, from, SoundEvents.BOOK_PAGE_TURN, 0.8f, 1.2f);
		for (int i = 0; i < n; i++) {
			JsonObject o = options.get(i).isJsonObject() ? options.get(i).getAsJsonObject() : new JsonObject();
			String name = str(o, "realName", "Option " + (i + 1));
			String mc = normalizeItem(str(o, "mcItem", "minecraft:paper"));
			String price = "";
			if (o.has("price")) {
				JsonElement p = o.get("price");
				if (p.isJsonObject()) price = money(dbl(p.getAsJsonObject(), "amount", 0), str(p.getAsJsonObject(), "currency", "SGD"));
				else if (p.isJsonPrimitive()) price = money(p.getAsDouble(), "SGD");
			}
			double spread = (i - (n - 1) / 2.0);
			Vec3 target = center.add(spread * 1.25, -Math.abs(spread) * 0.18, 0);
			Candidate c = new Candidate();
			c.target = target;
			c.from = from;
			c.pos = from;
			c.delay = i * 3;
			c.chosen = i == chosen;
			c.display = Fx.summon(server, "minecraft:item_display", from,
					"item:" + itemSnbt(mc, name) + ",item_display:\"fixed\",billboard:\"vertical\",teleport_duration:2," + Fx.tf(0.1));
			c.label = Fx.summon(server, "minecraft:text_display", target.add(0, -0.62, 0),
					"text:" + Fx.component(Fx.text(Fx.truncate(name, 22) + "\n", "white", true), Fx.text(price, "#FFD24A", false))
							+ ",billboard:\"center\",line_width:120,text_opacity:16,background:0,alignment:\"center\"," + Fx.tf(0.55));
			fan.add(c);
		}
		int settle = n * 3 + 14;
		// verdict: chosen glows, rejects dim and drop
		later(settle + 16, () -> {
			for (Candidate c : fan) c.verdict(level);
			Fx.sound(level, center, SoundEvents.AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
		});
		return settle + 16 + 30;
	}

	// ---------------------------------------------------------------- hover labels

	private void hover(ServerLevel level) {
		Slot target = null;
		double best = Double.MAX_VALUE;
		boolean anyStacked = false;
		for (Slot s : stack) anyStacked |= !s.flying;
		if (anyStacked) {
			for (ServerPlayer p : level.players()) {
				if (p instanceof EntityPlayerMPFake) continue;
				Vec3 eye = p.getEyePosition();
				Vec3 end = eye.add(p.getViewVector(1.0f).scale(HOVER_REACH));
				for (Slot s : stack) {
					if (s.flying) continue;
					double h = ITEM_SCALE * 0.5;
					AABB box = new AABB(s.pos.x - h, s.pos.y - SLOT_HEIGHT * 0.5, s.pos.z - h, s.pos.x + h, s.pos.y + SLOT_HEIGHT * 0.5, s.pos.z + h);
					var hit = box.clip(eye, end);
					if (hit.isPresent()) {
						double dist = hit.get().distanceToSqr(eye);
						if (dist < best) {
							best = dist;
							target = s;
						}
					}
				}
			}
		}
		if (target != null) {
			target.lastHover = now;
			if (hovered != target) {
				if (hovered != null) hovered.hideLabel();
				hovered = target;
				target.showLabel();
			}
		} else if (hovered != null && now - hovered.lastHover > HOVER_GRACE) {
			hovered.hideLabel();
			hovered = null;
		}
	}

	// ---------------------------------------------------------------- helpers

	private Slot find(String id) {
		for (int i = stack.size() - 1; i >= 0; i--) if (stack.get(i).id.equals(id)) return stack.get(i);
		return null;
	}

	private void later(int ticks, Runnable task) {
		scheduled.add(new Scheduled(now + Math.max(0, ticks), task));
	}

	private record Scheduled(long at, Runnable task) {}

	static String money(double amount, String currency) {
		return (currency == null || currency.isEmpty() ? "SGD" : currency) + " " + String.format(Locale.ROOT, "%.2f", amount);
	}

	static String normalizeItem(String id) {
		id = id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
		if (!id.matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?")) return "minecraft:paper";
		return id.contains(":") ? id : "minecraft:" + id;
	}

	static String itemSnbt(String mcItem, String realName) {
		return "{id:\"" + mcItem + "\",count:1,components:{\"minecraft:custom_name\":" + Fx.component(Fx.text(realName, null, false)) + "}}";
	}

	private static String str(JsonObject o, String key, String fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}

	private static int intOf(JsonObject o, String key, int fallback) {
		JsonElement e = o.get(key);
		try {
			return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static double dbl(JsonObject o, String key, double fallback) {
		JsonElement e = o.get(key);
		try {
			return e != null && e.isJsonPrimitive() ? e.getAsDouble() : fallback;
		} catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private static List<String> strings(JsonObject o, String key) {
		List<String> out = new ArrayList<>();
		JsonElement e = o.get(key);
		if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
		return out;
	}

	// ---------------------------------------------------------------- stack slot

	private final class Slot {
		String id, realName, merchant, mcItem, currency;
		double unitPrice;
		int qty;
		List<String> seats = List.of();
		UUID display, hitbox, label, badge;
		Vec3 from, pos;
		boolean flying;
		int t, arcTicks;
		long lastHover;
		boolean labelShown;

		void tick(ServerLevel level, int index, int count, Vec3 head) {
			Vec3 target = slotPos(head, index, count, now);
			if (flying) {
				t++;
				double s = Math.min(1.0, t / (double) arcTicks);
				double e = Fx.easeInOut(s);
				// Arc peak stays under the beams too.
				double lift = Math.min(1.4 + 0.25 * index, Math.max(0.3, CrewkitAnchors.origin.getY() + STACK_CEILING - Math.max(from.y, target.y)));
				pos = from.lerp(target, e).add(0, Math.sin(Math.PI * s) * lift, 0);
				Fx.move(level, display, pos);
				if (t >= arcTicks) land(level, index, target);
				return;
			}
			// ease toward the slot: follows the chef and slides down smoothly after a removal
			pos = pos.lerp(target, 0.5);
			Fx.move(level, display, pos);
			Fx.move(level, hitbox, pos.add(0, -SLOT_HEIGHT * 0.5, 0));
			Fx.move(level, badge, badgePos());
			Fx.move(level, label, labelPos());
		}

		private void land(ServerLevel level, int index, Vec3 target) {
			flying = false;
			pos = target;
			Fx.move(level, display, pos);
			bounce();
			Fx.sound(level, pos, SoundEvents.ITEM_PICKUP, 0.8f, pitchFor(index));
			hitbox = Fx.summon(server, "minecraft:interaction", pos.add(0, -SLOT_HEIGHT * 0.5, 0),
					"width:" + Fx.f(ITEM_SCALE + 0.05) + ",height:" + Fx.f(SLOT_HEIGHT) + ",response:false");
			label = Fx.summon(server, "minecraft:text_display", labelPos(),
					"text:" + labelText() + ",billboard:\"center\",line_width:200,alignment:\"center\",text_opacity:16,background:0"
							+ ",teleport_duration:2,shadow:true,view_range:2.0f," + Fx.tf(LABEL_SCALE * 0.85));
			if (qty > 1) spawnBadge();
		}

		void bounce() {
			UUID d = display;
			Fx.merge(server, d, Fx.tf(0, -0.04, 0, new Quaternionf(), ITEM_SCALE * 1.25, ITEM_SCALE * 0.72, ITEM_SCALE * 1.25) + "," + Fx.interp(2));
			later(3, () -> Fx.merge(server, d, Fx.tf(ITEM_SCALE) + "," + Fx.interp(5)));
		}

		void shake() {
			UUID d = display;
			Fx.merge(server, d, Fx.tf(0, 0, 0, new Quaternionf().rotateZ(0.25f), ITEM_SCALE, ITEM_SCALE, ITEM_SCALE) + "," + Fx.interp(2));
			later(2, () -> Fx.merge(server, d, Fx.tf(0, 0, 0, new Quaternionf().rotateZ(-0.2f), ITEM_SCALE, ITEM_SCALE, ITEM_SCALE) + "," + Fx.interp(3)));
			later(5, () -> Fx.merge(server, d, Fx.tf(ITEM_SCALE) + "," + Fx.interp(3)));
		}

		/** Small badge on the item's lower-left corner, in front of it (camera side). */
		Vec3 badgePos() {
			return pos.add(-ITEM_SCALE * 0.42, -0.14, 0.18);
		}

		/** Label centred just above the item, slightly toward the camera; the text grows upward from here. */
		Vec3 labelPos() {
			return pos.add(0, ITEM_SCALE * 0.5 + 0.04, 0.2);
		}

		String labelText() {
			String priceLine = money(unitPrice, currency) + (qty > 1 ? " x" + qty + " = " + money(unitPrice * qty, currency) : "");
			return Fx.component(
					Fx.text(wrapName(realName, LABEL_COLS) + "\n", "white", true),
					Fx.text(Fx.truncate(priceLine, LABEL_COLS) + (merchant.isEmpty() ? "" : "\n"), "#FFD24A", false),
					Fx.text(Fx.truncate(merchant, LABEL_COLS), "#A8B3BD", false));
		}

		void spawnBadge() {
			badge = Fx.summon(server, "minecraft:text_display", badgePos(),
					"text:" + Fx.component(Fx.text("x" + qty, "#FFD24A", true))
							+ ",billboard:\"center\",background:" + 0xC0141414 + ",teleport_duration:2,view_range:2.0f," + Fx.tf(0.0));
			UUID b = badge;
			later(1, () -> Fx.merge(server, b, Fx.tf(0.45) + "," + Fx.interp(4)));
		}

		void refreshBadgeAndLabel() {
			if (flying) return; // landing builds them with the current qty
			if (qty > 1 && badge == null) spawnBadge();
			else if (qty > 1) Fx.merge(server, badge, "text:" + Fx.component(Fx.text("x" + qty, "#FFD24A", true)));
			else if (badge != null) {
				UUID b = badge;
				badge = null;
				Fx.merge(server, b, Fx.tf(0.0) + "," + Fx.interp(4));
				later(5, () -> Fx.discard(server.overworld(), b));
			}
			Fx.merge(server, label, "text:" + labelText());
		}

		void showLabel() {
			labelShown = true;
			Fx.merge(server, label, "text_opacity:255,background:" + 0xD0101418 + "," + Fx.tf(LABEL_SCALE) + "," + Fx.interp(4));
		}

		void hideLabel() {
			labelShown = false;
			Fx.merge(server, label, "text_opacity:16,background:" + 0x00101418 + "," + Fx.tf(LABEL_SCALE * 0.85) + "," + Fx.interp(6));
		}

		void discardExtras(ServerLevel level) {
			Fx.discard(level, hitbox);
			Fx.discard(level, badge);
			if (labelShown) {
				hideLabel();
				UUID l = label;
				later(7, () -> Fx.discard(server.overworld(), l));
			} else Fx.discard(level, label);
			hitbox = badge = label = null;
		}

		void discardAll(ServerLevel level) {
			Fx.discard(level, display);
			Fx.discard(level, hitbox);
			Fx.discard(level, badge);
			Fx.discard(level, label);
		}
	}

	// ---------------------------------------------------------------- tumbling item

	private final class Faller {
		final UUID display;
		final double floorY;
		Vec3 pos, vel;
		int t;

		Faller(UUID display, Vec3 start, int side, double floorY) {
			this.display = display;
			this.floorY = floorY;
			this.pos = start;
			this.vel = new Vec3(0.09 * side, 0.2, 0.05);
			Quaternionf spin = new Quaternionf().rotateZ((float) (-side * Math.toRadians(150))).rotateX(0.5f);
			Fx.merge(server, display, Fx.tf(0, 0, 0, spin, ITEM_SCALE, ITEM_SCALE, ITEM_SCALE) + "," + Fx.interp(TUMBLE_TICKS));
			later(TUMBLE_TICKS / 2, () -> Fx.merge(server, display, Fx.tf(0, 0, 0, spin, 0, 0, 0) + "," + Fx.interp(TUMBLE_TICKS / 2)));
		}

		boolean tick(ServerLevel level) {
			t++;
			vel = new Vec3(vel.x * 0.97, vel.y - 0.04, vel.z * 0.97);
			pos = pos.add(vel);
			if (pos.y < floorY) { // small bounce instead of sinking into the floor
				pos = new Vec3(pos.x, floorY, pos.z);
				vel = new Vec3(vel.x * 0.6, -vel.y * 0.3, vel.z * 0.6);
			}
			Fx.move(level, display, pos);
			if (t >= TUMBLE_TICKS + 1) {
				Fx.discard(level, display);
				return true;
			}
			return false;
		}
	}

	// ---------------------------------------------------------------- candidate fan

	private final class Candidate {
		UUID display, label;
		Vec3 from, target, pos;
		int t, delay;
		boolean chosen;
		int phase; // 0 fan out, 1 hold, 2 rejected drop, 3 chosen pulse
		int phaseT;

		boolean tick(ServerLevel level) {
			t++;
			if (phase == 0) {
				if (t <= delay) return false;
				if (t == delay + 1) Fx.merge(server, display, Fx.tf(0.6) + "," + Fx.interp(8));
				double s = Math.min(1.0, (t - delay) / 14.0);
				double e = Fx.easeInOut(s);
				pos = from.lerp(target, e).add(0, Math.sin(Math.PI * s) * 0.8, 0);
				Fx.move(level, display, pos);
				if (s >= 1.0) {
					phase = 1;
					Fx.merge(server, label, "text_opacity:255,background:" + 0xC0101418 + "," + Fx.interp(5));
				}
				return false;
			}
			if (phase == 2) {
				phaseT++;
				pos = pos.add(0, -0.012 * phaseT, 0);
				Fx.move(level, display, pos);
				if (phaseT >= 20) {
					discardAll(level);
					return true;
				}
				return false;
			}
			if (phase == 3) {
				phaseT++;
				if (phaseT >= 34) {
					discardAll(level);
					return true;
				}
			}
			return false;
		}

		void verdict(ServerLevel level) {
			phaseT = 0;
			if (chosen) {
				phase = 3;
				Fx.merge(server, display, "Glowing:1b,glow_color_override:" + 0xFFD24A + "," + Fx.tf(0.8) + "," + Fx.interp(6));
				UUID d = display, l = label;
				later(24, () -> {
					Fx.merge(server, d, Fx.tf(0.0) + "," + Fx.interp(8));
					Fx.merge(server, l, "text_opacity:16,background:0," + Fx.interp(6));
				});
			} else {
				phase = 2;
				Fx.merge(server, display, "brightness:{sky:2,block:2}," + Fx.tf(0, 0, 0, new Quaternionf().rotateZ(0.6f), 0.0, 0.0, 0.0) + "," + Fx.interp(18));
				Fx.merge(server, label, "text_opacity:16,background:0," + Fx.interp(5));
			}
		}

		void discardAll(ServerLevel level) {
			Fx.discard(level, display);
			Fx.discard(level, label);
		}
	}
}
