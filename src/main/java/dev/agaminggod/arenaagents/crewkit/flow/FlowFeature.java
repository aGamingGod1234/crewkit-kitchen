package dev.agaminggod.arenaagents.crewkit.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The order flow at the pass: ticket, gate bars, checkout QR, courier delivery and plating.
 * Everything is spawned with vanilla commands (display entities, glow item frames) tagged
 * "crewkit" + "ck_flow", so reset is one kill selector. Motion uses teleport_duration and
 * transformation interpolation so nothing snaps.
 */
public final class FlowFeature implements CrewkitFeature {
	private static final Logger LOGGER = LoggerFactory.getLogger(FlowFeature.class);
	private static final String TAG = "ck_flow";

	/**
	 * Optional hook for the items track: when set, delivery plates what it returns (and the items
	 * track empties the chef's head stack). When null, flow plates from its own ledger of
	 * item_added / item_removed events.
	 */
	public static volatile DeliverySource deliverySource = null;

	public interface DeliverySource {
		List<DeliveryItem> consume();
	}

	/** One product line to plate. seats = guest names; 1 = own plate, 2 = shared tray, else room tray. */
	public record DeliveryItem(String mcItem, String realName, List<String> seats) {}

	// Layout, in blocks relative to CrewkitAnchors.origin (x east, y up, z south). Camera looks north.
	private static final double PASS_Z = 9.45;
	private static final double TICKET_Y = 2.35;
	private static final double RAIL_X = 4.0;
	private static final double FIRED_X = 6.35;
	private static final double OFFSTAGE_X = 0.4;
	private static final int QR_TILES = 3;
	private static final int QR_X = 8;
	private static final int QR_Y = 2;
	private static final int QR_Z = 9;
	private static final double BAR_X0 = 6.0;
	private static final int BAR_COUNT = 7;
	private static final double BAR_Z = 9.7;
	private static final double BAR_DOWN_Y = 1.25;
	private static final double BAR_UP_Y = 7.2;
	private static final double BAR_HEIGHT = 3.0;
	private static final double HOURGLASS_X = 12.3;
	private static final double PLATE_Y = 2.0;
	private static final double[] BAG = {25.5, 1.0, 6.5};

	private static final int PAPER = argb(0xFFF3E9CF);
	private static final int RED = argb(0xFFD83A3A);
	private static final int GREEN = argb(0xFF3FAE5A);
	private static final int GATE_RED = argb(0xE0B91C1C);

	private final List<Task> tasks = new ArrayList<>();
	private long now;

	private String title = "Order";
	private final List<String> guests = new ArrayList<>();
	private final List<String> needs = new ArrayList<>();
	private String budget = "";
	private int ticketBg = PAPER;
	private String status = "";
	private String statusColor = "#555555";
	private boolean ticketSpawned;
	private boolean gateDown;
	private String qrUrl;
	private boolean hourglass;
	private long hourglassStart;
	private int hourglassTurn;
	private boolean platesSet;
	private boolean delivered;
	private int itemSerial;
	private final Map<String, Line> ledger = new LinkedHashMap<>();
	/** Map ids and pixel data of the QR on show; pushed to clients because frame sync alone sends only the id. */
	private final List<MapId> qrMapIds = new ArrayList<>();
	private final List<MapItemSavedData> qrMaps = new ArrayList<>();

	private static final class Line {
		String mcItem;
		String realName;
		int qty;
		List<String> seats = new ArrayList<>();
	}

	private record Task(long at, Runnable run) {}

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		if (data == null) data = new JsonObject();
		switch (event) {
			case "brief" -> onBrief(server, data);
			case "item_added" -> onItemAdded(data);
			case "item_removed" -> onItemRemoved(data);
			case "gate_blocked" -> onGateBlocked(server, data);
			case "gate_passed" -> onGatePassed(server, data);
			case "checkout" -> onCheckout(server, data);
			case "completed" -> onCompleted(server, data);
			case "failed", "expired" -> onFailed(server, event, data);
			case "reset" -> reset(server);
			default -> { }
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		now++;
		if (!tasks.isEmpty()) {
			List<Task> due = new ArrayList<>();
			for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
				Task t = it.next();
				if (t.at <= now) {
					due.add(t);
					it.remove();
				}
			}
			for (Task t : due) {
				try {
					t.run.run();
				} catch (RuntimeException e) {
					LOGGER.warn("CrewKit flow step failed", e);
				}
			}
		}
		if (hourglass && (now - hourglassStart) % 20 == 0) flipHourglass(server);
		if (!qrMaps.isEmpty() && now % 100 == 0) syncQrMaps(server);
	}

	@Override
	public void reset(MinecraftServer server) {
		tasks.clear();
		run(server, "kill @e[tag=" + TAG + "]");
		title = "Order";
		guests.clear();
		needs.clear();
		budget = "";
		ticketBg = PAPER;
		status = "";
		ticketSpawned = false;
		gateDown = false;
		qrUrl = null;
		hourglass = false;
		platesSet = false;
		delivered = false;
		ledger.clear();
	}

	// ---------------------------------------------------------------- brief

	private void onBrief(MinecraftServer server, JsonObject data) {
		reset(server);
		title = str(data, "title", "Order");
		for (JsonElement g : arr(data, "guests")) {
			String name = g.isJsonObject() ? str(g.getAsJsonObject(), "name", "Guest") : g.getAsString();
			guests.add(name);
		}
		for (JsonElement n : arr(data, "needs")) needs.add(n.isJsonPrimitive() ? n.getAsString() : n.toString());
		budget = money(data.get("budget"));
		status = "NEW ORDER";
		statusColor = "#555555";

		// Ticket enters from inside the west wall and slides along the rail.
		Pos off = rel(OFFSTAGE_X, TICKET_Y, PASS_Z);
		run(server, "summon minecraft:text_display " + off + " {" + tags("ck_flow_ticket")
			+ ",billboard:\"vertical\",alignment:\"left\",line_width:130,shadow:0b,teleport_duration:14"
			+ ",brightness:{sky:15,block:15},background:" + ticketBg + ",text:" + ticketText() + "}");
		ticketSpawned = true;
		later(2, () -> {
			tp(server, "ck_flow_ticket", rel(RAIL_X, TICKET_Y, PASS_Z));
			sound(server, "item.book.page_turn", rel(RAIL_X, TICKET_Y, PASS_Z), 1.0, 1.0);
		});

		// Table setting: thin white plates scale in under each seated guest, after the ticket lands.
		later(30, () -> setPlates(server));
	}

	private void setPlates(MinecraftServer server) {
		if (platesSet) return;
		platesSet = true;
		int n = Math.min(guests.size(), CrewkitAnchors.SEATS.length);
		for (int i = 0; i < n; i++) {
			final int seat = i;
			Pos p = platePos(seat);
			String tag = "ck_flow_plate_" + seat;
			run(server, "summon minecraft:block_display " + p + " {" + tags(tag)
				+ ",block_state:{Name:\"minecraft:white_concrete\"},brightness:{sky:15,block:15}"
				+ ",transformation:" + box(0.01, 0.01, 0.01) + "}");
			later(2 + i, () -> setTransform(server, tag, box(0.6, 0.05, 0.6), 6));
		}
	}

	// ---------------------------------------------------------------- items ledger

	private void onItemAdded(JsonObject data) {
		String id = str(data, "id", "item" + ledger.size());
		Line line = ledger.computeIfAbsent(id, k -> new Line());
		line.mcItem = safeItem(str(data, "mcItem", "minecraft:paper"));
		line.realName = str(data, "realName", id);
		line.qty += Math.max(1, intOf(data, "qty", 1));
		for (JsonElement s : arr(data, "seats")) line.seats.add(s.getAsString());
	}

	private void onItemRemoved(JsonObject data) {
		String id = str(data, "id", "");
		Line line = ledger.get(id);
		if (line == null) return;
		int removed = intOf(data, "qtyRemoved", line.qty);
		line.qty -= removed;
		for (int i = 0; i < removed && line.seats.size() > 1; i++) line.seats.remove(line.seats.size() - 1);
		if (line.qty <= 0) ledger.remove(id);
	}

	// ---------------------------------------------------------------- gate

	private void onGateBlocked(MinecraftServer server, JsonObject data) {
		String over = money(data.get("over"));
		setTicket(server, RED, over.isEmpty() ? "OVER BUDGET" : "OVER BUDGET by " + over, "#FFFFFF");
		pop(server, "ck_flow_ticket");
		Pos t = rel(RAIL_X, TICKET_Y, PASS_Z);
		sound(server, "entity.villager.no", t, 1.0, 1.0);

		String sign = gateSign(over);
		if (gateDown) {
			run(server, "data merge entity @e[tag=ck_flow_gate_sign,limit=1] {text:" + sign + "}");
			return;
		}
		gateDown = true;
		// Bars wait above the ceiling, then drop across the pass after the ticket has gone red.
		for (int i = 0; i < BAR_COUNT; i++) {
			run(server, "summon minecraft:block_display " + rel(BAR_X0 + i, BAR_UP_Y, BAR_Z) + " {" + tags("ck_flow_bar", "ck_flow_bar_" + i)
				+ ",teleport_duration:7,brightness:{sky:15,block:15}"
				+ ",block_state:{Name:\"minecraft:iron_bars\",Properties:{east:\"true\",west:\"true\"}}"
				+ ",transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,-0.5f],scale:[1f,"
				+ f(BAR_HEIGHT) + "f,1f]}}");
		}
		run(server, "summon minecraft:text_display " + rel(BAR_X0 + BAR_COUNT / 2.0, BAR_UP_Y + 1.2, BAR_Z + 0.08) + " {"
			+ tags("ck_flow_gate_sign") + ",billboard:\"vertical\",teleport_duration:7,shadow:0b,line_width:200"
			+ ",brightness:{sky:15,block:15},background:" + GATE_RED
			+ ",transformation:" + scaleOnly(1.3) + ",text:" + sign + "}");
		later(10, () -> {
			for (int i = 0; i < BAR_COUNT; i++) {
				tp(server, "ck_flow_bar_" + i, rel(BAR_X0 + i, BAR_DOWN_Y, BAR_Z));
			}
			tp(server, "ck_flow_gate_sign", rel(BAR_X0 + BAR_COUNT / 2.0, BAR_DOWN_Y + 1.2, BAR_Z + 0.08));
		});
		later(17, () -> sound(server, "block.iron_door.close", rel(BAR_X0 + BAR_COUNT / 2.0, BAR_DOWN_Y, BAR_Z), 1.0, 0.8));
	}

	private void liftGate(MinecraftServer server) {
		if (!gateDown) return;
		gateDown = false;
		Pos mid = rel(BAR_X0 + BAR_COUNT / 2.0, BAR_DOWN_Y, BAR_Z);
		sound(server, "block.iron_door.open", mid, 1.0, 1.0);
		for (int i = 0; i < BAR_COUNT; i++) {
			tp(server, "ck_flow_bar_" + i, rel(BAR_X0 + i, BAR_UP_Y, BAR_Z));
		}
		tp(server, "ck_flow_gate_sign", rel(BAR_X0 + BAR_COUNT / 2.0, BAR_UP_Y + 1.2, BAR_Z + 0.08));
		later(10, () -> {
			if (!gateDown) run(server, "kill @e[tag=ck_flow_bar]");
			if (!gateDown) run(server, "kill @e[tag=ck_flow_gate_sign]");
		});
	}

	private void onGatePassed(MinecraftServer server, JsonObject data) {
		liftGate(server);
		String total = money(data.get("total"));
		setTicket(server, GREEN, total.isEmpty() ? "WITHIN BUDGET" : "WITHIN BUDGET: " + total, "#FFFFFF");
		pop(server, "ck_flow_ticket");
		later(4, () -> sound(server, "block.note_block.chime", rel(RAIL_X, TICKET_Y, PASS_Z), 1.0, 1.4));
	}

	// ---------------------------------------------------------------- checkout QR

	private void onCheckout(MinecraftServer server, JsonObject data) {
		String url = str(data, "approvalUrl", "");
		if (url.isEmpty() || url.equals(qrUrl)) return;
		liftGate(server);
		boolean replacing = qrUrl != null;
		clearQr(server, false);
		qrUrl = url;

		setTicket(server, GREEN, "AWAITING APPROVAL", "#FFFFFF");
		tp(server, "ck_flow_ticket", rel(FIRED_X, TICKET_Y, PASS_Z));

		// Blue card grows behind where the QR will hang, then the map tiles appear on it.
		double cx = QR_X + QR_TILES / 2.0;
		double cy = QR_Y + QR_TILES / 2.0;
		double card = QR_TILES + 0.3;
		run(server, "summon minecraft:block_display " + rel(cx, cy, QR_Z - 0.1) + " {" + tags("ck_flow_qr_card")
			+ ",block_state:{Name:\"minecraft:blue_concrete\"},brightness:{sky:15,block:15}"
			+ ",transformation:" + centeredBox(0.01, 0.01, 0.04) + "}");
		later(2, () -> setTransform(server, "ck_flow_qr_card", centeredBox(card, card, 0.04), 7));

		int appear = replacing ? 6 : 10;
		later(appear, () -> {
			if (!url.equals(qrUrl)) return;
			if (!spawnQrMaps(server, url)) spawnQrPixels(server, url);
			Pos label = rel(cx, QR_Y + QR_TILES + 0.2, PASS_Z - 0.2);
			run(server, "summon minecraft:text_display " + label + " {" + tags("ck_flow_qr_label")
				+ ",billboard:\"vertical\",shadow:0b,line_width:240,brightness:{sky:15,block:15},background:" + argb(0xE0102A8C)
				+ ",transformation:" + scaleOnly(1.1)
				+ ",text:{text:\"SCAN TO APPROVE ON REAP\",color:\"#FFFFFF\",bold:true}}");
			sound(server, "block.amethyst_block.chime", rel(cx, cy, PASS_Z), 1.0, 1.0);
		});
		later(appear + 8, () -> {
			if (!url.equals(qrUrl)) return;
			Pos hg = rel(HOURGLASS_X, QR_Y + 1.0, PASS_Z);
			run(server, "summon minecraft:text_display " + hg + " {" + tags("ck_flow_hourglass", "ck_flow_hourglass_glyph")
				+ ",billboard:\"vertical\",shadow:0b,background:0,brightness:{sky:15,block:15}"
				+ ",transformation:" + hourglassTransform(0) + ",text:{text:\"⌛\",color:\"#F2B53A\"}}");
			run(server, "summon minecraft:text_display " + rel(HOURGLASS_X, QR_Y + 0.55, PASS_Z) + " {" + tags("ck_flow_hourglass")
				+ ",billboard:\"vertical\",shadow:0b,line_width:120,brightness:{sky:15,block:15},background:" + argb(0xC0000000)
				+ ",transformation:" + scaleOnly(0.8) + ",text:{text:\"waiting for approval\",color:\"#FFFFFF\"}}");
			hourglass = true;
			hourglassStart = now + 1;
			hourglassTurn = 0;
		});
	}

	/** Renders the QR into QR_TILES x QR_TILES locked filled maps shown in invisible glow item frames. */
	private boolean spawnQrMaps(MinecraftServer server, String url) {
		try {
			boolean[][] qr = QrEncoder.encode(url, QrEncoder.Ecc.M);
			int n = qr.length;
			int quiet = 4;
			int pixels = 128 * QR_TILES;
			int px = pixels / (n + 2 * quiet);
			if (px < 2) return false;
			int offset = (pixels - px * n) / 2;
			byte white = MapColor.SNOW.getPackedId(MapColor.Brightness.HIGH);
			byte black = MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.HIGH);
			ServerLevel level = server.overworld();
			for (int ty = 0; ty < QR_TILES; ty++) {
				for (int tx = 0; tx < QR_TILES; tx++) {
					MapItemSavedData map = MapItemSavedData.createFresh(0, 0, (byte) 0, false, false, level.dimension()).locked();
					for (int y = 0; y < 128; y++) {
						for (int x = 0; x < 128; x++) {
							int gx = (tx * 128 + x - offset);
							int gy = (ty * 128 + y - offset);
							boolean dark = gx >= 0 && gy >= 0 && gx < px * n && gy < px * n && qr[gy / px][gx / px];
							map.setColor(x, y, dark ? black : white);
						}
					}
					map.setDirty();
					MapId id = level.getFreeMapId();
					level.setMapData(id, map);
					qrMapIds.add(id);
					qrMaps.add(map);
					// Tile row 0 is the top; frames facing south hang on the north edge of their block.
					int bx = QR_X + tx;
					int by = QR_Y + (QR_TILES - 1 - ty);
					run(server, String.format(Locale.ROOT,
						"summon minecraft:glow_item_frame %d %d %d {%s,Facing:3b,Fixed:1b,Invisible:1b,Invulnerable:1b,Silent:1b"
							+ ",Item:{id:\"minecraft:filled_map\",count:1,components:{\"minecraft:map_id\":%d}}}",
						CrewkitAnchors.origin.getX() + bx, CrewkitAnchors.origin.getY() + by, CrewkitAnchors.origin.getZ() + QR_Z,
						tags("ck_flow_qr"), id.id()));
				}
			}
			syncQrMaps(server);
			later(5, () -> syncQrMaps(server));
			LOGGER.info("CrewKit QR: {} modules, {} px/module on {}x{} maps", n, px, QR_TILES, QR_TILES);
			return true;
		} catch (RuntimeException e) {
			LOGGER.warn("CrewKit QR map render failed, falling back to block pixels", e);
			return false;
		}
	}

	/** Fallback: one black block_display per horizontal run of dark modules, over the white card. */
	private void spawnQrPixels(MinecraftServer server, String url) {
		boolean[][] qr = QrEncoder.encode(url, QrEncoder.Ecc.M);
		int n = qr.length;
		double side = QR_TILES * 0.92;
		double m = side / n;
		double left = QR_X + (QR_TILES - side) / 2.0;
		double top = QR_Y + QR_TILES - (QR_TILES - side) / 2.0;
		run(server, "summon minecraft:block_display " + rel(QR_X + QR_TILES / 2.0, QR_Y + QR_TILES / 2.0, QR_Z + 0.0) + " {"
			+ tags("ck_flow_qr") + ",block_state:{Name:\"minecraft:white_concrete\"},brightness:{sky:15,block:15}"
			+ ",transformation:" + centeredBox(QR_TILES, QR_TILES, 0.02) + "}");
		for (int y = 0; y < n; y++) {
			int x = 0;
			while (x < n) {
				if (!qr[y][x]) { x++; continue; }
				int start = x;
				while (x < n && qr[y][x]) x++;
				Pos p = rel(left + start * m, top - (y + 1) * m, QR_Z + 0.03);
				run(server, "summon minecraft:block_display " + p + " {" + tags("ck_flow_qr")
					+ ",block_state:{Name:\"minecraft:black_concrete\"},brightness:{sky:15,block:15}"
					+ ",transformation:" + box((x - start) * m, m, 0.01) + "}");
			}
		}
	}

	private void syncQrMaps(MinecraftServer server) {
		ServerLevel level = server.overworld();
		for (int i = 0; i < qrMaps.size(); i++) {
			MapItemSavedData map = qrMaps.get(i);
			var packet = new ClientboundMapItemDataPacket(qrMapIds.get(i), map.scale, true, List.of(),
				new MapItemSavedData.MapPatch(0, 0, 128, 128, map.colors.clone()));
			for (var viewer : level.players()) viewer.connection.send(packet);
		}
	}

	private void clearQr(MinecraftServer server, boolean shrinkCard) {
		hourglass = false;
		qrMapIds.clear();
		qrMaps.clear();
		run(server, "kill @e[tag=ck_flow_qr]");
		run(server, "kill @e[tag=ck_flow_qr_label]");
		run(server, "kill @e[tag=ck_flow_hourglass]");
		if (shrinkCard) {
			setTransform(server, "ck_flow_qr_card", centeredBox(0.01, 0.01, 0.04), 7);
			later(8, () -> { if (qrUrl == null) run(server, "kill @e[tag=ck_flow_qr_card]"); });
		} else {
			run(server, "kill @e[tag=ck_flow_qr_card]");
		}
	}

	private void flipHourglass(MinecraftServer server) {
		sound(server, "block.note_block.hat", rel(HOURGLASS_X, QR_Y + 1.0, PASS_Z), 0.25, 1.0);
		// Two quarter turns per flip so the glyph rotates about its centre.
		int base = hourglassTurn;
		hourglassTurn += 2;
		setTransform(server, "ck_flow_hourglass_glyph", hourglassTransform(base + 1), 4);
		later(4, () -> { if (hourglass) setTransform(server, "ck_flow_hourglass_glyph", hourglassTransform(base + 2), 4); });
	}

	private static String hourglassTransform(int quarterTurns) {
		double s = 3.0;
		double h = 0.125 * s;
		double theta = quarterTurns * Math.PI / 2;
		double tx = h * Math.sin(theta);
		double ty = h * (1 - Math.cos(theta));
		double qz = Math.sin(theta / 2);
		double qw = Math.cos(theta / 2);
		return "{left_rotation:[0f,0f," + f(qz) + "f," + f(qw) + "f],right_rotation:[0f,0f,0f,1f],translation:["
			+ f(tx) + "f," + f(ty) + "f,0f],scale:[" + f(s) + "f," + f(s) + "f," + f(s) + "f]}";
	}

	// ---------------------------------------------------------------- completed: courier + plating

	private void onCompleted(MinecraftServer server, JsonObject data) {
		if (delivered) return;
		delivered = true;
		String order = str(data, "orderId", "");
		String paid = money(data.get("finalAmount"));
		qrUrl = null;
		clearQr(server, true);
		setTicket(server, GREEN, "PAID " + paid + (order.isEmpty() ? "" : "  #" + shortId(order)), "#FFFFFF");
		if (!platesSet) setPlates(server);

		List<DeliveryItem> items = collectDelivery();
		Pos door = rel(BAG[0] + 1.0, BAG[1] + 3.2, BAG[2]);
		Pos floor = rel(BAG[0], BAG[1] + 0.45, BAG[2]);
		long t = 6;
		later(t, () -> {
			sound(server, "block.wooden_door.open", rel(27.0, 1.5, 5.5), 1.0, 1.0);
			run(server, "summon minecraft:item_display " + door + " {" + tags("ck_flow_bag")
				+ ",item:{id:\"minecraft:bundle\",count:1},billboard:\"vertical\",teleport_duration:9"
				+ ",brightness:{sky:15,block:15},transformation:" + scaleOnly(0.01) + "}");
		});
		later(t + 2, () -> {
			setTransform(server, "ck_flow_bag", scaleOnly(1.6), 6);
			tp(server, "ck_flow_bag", floor);
		});
		later(t + 11, () -> sound(server, "item.bundle.drop_contents", floor, 1.0, 1.0));
		// Bag opens: a quick swell before items pop out.
		later(t + 18, () -> setTransform(server, "ck_flow_bag", scaleOnly(2.1), 4));
		later(t + 22, () -> setTransform(server, "ck_flow_bag", scaleOnly(1.6), 4));

		long start = t + 26;
		int[] perSeatCount = new int[CrewkitAnchors.SEATS.length];
		List<Wave> waves = buildWaves(items);
		for (int w = 0; w < waves.size(); w++) {
			Wave wave = waves.get(w);
			long at = start + w * 18L;
			final int li = w;
			later(at, () -> sound(server, "item.bundle.remove_one", floor, 0.8, 1.1 + 0.03 * li));
			List<Pos> targets = new ArrayList<>();
			if (wave.perPlate()) {
				for (int s : wave.seats()) targets.add(plateSlot(s, perSeatCount[s]++));
			} else if (wave.seats().isEmpty()) {
				targets.add(rel(12.0, PLATE_Y, 13.0));
			} else {
				targets.add(trayPos(server, wave.seats(), at));
			}
			for (int k = 0; k < targets.size(); k++) fly(server, wave.mcItem(), floor, targets.get(k), at + k);
		}
		int lineIndex = waves.size();
		long end = start + Math.max(1, lineIndex) * 18L + 12;
		later(end, () -> {
			sound(server, "block.note_block.bell", rel(RAIL_X, TICKET_Y, PASS_Z), 1.0, 1.0);
			setTicket(server, GREEN, "ORDER UP" + (paid.isEmpty() ? "" : "  " + paid), "#FFFFFF");
			pop(server, "ck_flow_ticket");
			setTransform(server, "ck_flow_bag", scaleOnly(0.01), 8);
		});
		later(end + 9, () -> run(server, "kill @e[tag=ck_flow_bag]"));
		later(end + 16, () -> sound(server, "ui.toast.challenge_complete", rel(14.0, 4.0, 14.0), 0.7, 1.0));
	}

	/** One item arcs from the bag to its target in four interpolated hops, then sets down. */
	private void fly(MinecraftServer server, String mcItem, Pos from, Pos to, long at) {
		String tag = "ck_flow_it_" + (itemSerial++);
		double hop = 3.0 + Math.min(2.0, Math.hypot(to.x - from.x, to.z - from.z) / 10.0);
		later(at, () -> run(server, "summon minecraft:item_display " + from.up(0.6) + " {" + tags(tag, "ck_flow_item")
			+ ",item:{id:\"" + mcItem + "\",count:1},billboard:\"vertical\",teleport_duration:5"
			+ ",brightness:{sky:15,block:15},transformation:" + scaleOnly(0.45) + "}"));
		for (int i = 1; i <= 4; i++) {
			double s = i / 4.0;
			double arc = 4 * hop * s * (1 - s);
			Pos p = new Pos(from.x + (to.x - from.x) * s, from.y + 0.6 + (to.y + 0.22 - from.y - 0.6) * s + arc, from.z + (to.z - from.z) * s);
			later(at + 1 + (i - 1) * 5L, () -> tp(server, tag, p));
		}
		later(at + 21, () -> sound(server, "entity.item_frame.add_item", to, 0.8, 1.0));
	}

	private Pos trayPos(MinecraftServer server, List<Integer> seats, long at) {
		double x = 0, z = 0;
		for (int s : seats) {
			Pos p = platePos(s);
			x += p.x;
			z += p.z;
		}
		x /= seats.size();
		z /= seats.size();
		Pos tray = new Pos(x, CrewkitAnchors.origin.getY() + PLATE_Y, z);
		String tag = "ck_flow_tray_" + (itemSerial++);
		double w = seats.size() == 2 ? 0.8 : 1.0;
		later(Math.max(0, at - 4), () -> {
			run(server, "summon minecraft:block_display " + tray + " {" + tags(tag)
				+ ",block_state:{Name:\"minecraft:spruce_planks\"},brightness:{sky:15,block:15}"
				+ ",transformation:" + box(0.01, 0.01, 0.01) + "}");
			later(1, () -> setTransform(server, tag, box(w, 0.06, 0.5), 4));
		});
		return tray.up(0.06);
	}

	private List<DeliveryItem> collectDelivery() {
		DeliverySource src = deliverySource;
		if (src != null) {
			try {
				List<DeliveryItem> external = src.consume();
				if (external != null && !external.isEmpty()) return external;
			} catch (RuntimeException e) {
				LOGGER.warn("CrewKit delivery source failed, using flow ledger", e);
			}
		}
		List<DeliveryItem> out = new ArrayList<>();
		for (Line line : ledger.values()) {
			List<String> seats = line.seats;
			// Per-person lines without explicit seats: one each for the first qty guests.
			if (seats.isEmpty() && line.qty >= guests.size() && !guests.isEmpty()) {
				for (String g : guests) out.add(new DeliveryItem(line.mcItem, line.realName, List.of(g)));
				continue;
			}
			if (seats.size() > 2 && seats.size() == line.qty) {
				for (String g : seats) out.add(new DeliveryItem(line.mcItem, line.realName, List.of(g)));
				continue;
			}
			out.add(new DeliveryItem(line.mcItem, line.realName, List.copyOf(seats)));
		}
		return out;
	}

	/** One focal motion: either copies of a product to individual plates, or one shared item to a tray. */
	private record Wave(String mcItem, boolean perPlate, List<Integer> seats) {}

	private List<Wave> buildWaves(List<DeliveryItem> items) {
		Map<String, List<Integer>> perPlate = new LinkedHashMap<>();
		List<Wave> shared = new ArrayList<>();
		for (DeliveryItem it : items) {
			List<Integer> seats = seatIndexes(it.seats());
			if (seats.size() == 1) {
				List<Integer> list = perPlate.computeIfAbsent(it.mcItem(), k -> new ArrayList<>());
				if (!list.contains(seats.get(0))) list.add(seats.get(0));
			} else {
				shared.add(new Wave(it.mcItem(), false, seats.isEmpty() ? allSeats() : seats));
			}
		}
		List<Wave> waves = new ArrayList<>();
		perPlate.forEach((item, seats) -> waves.add(new Wave(item, true, seats)));
		waves.addAll(shared);
		return waves;
	}

	private List<Integer> seatIndexes(List<String> names) {
		List<Integer> out = new ArrayList<>();
		for (String n : names) {
			int i = guests.indexOf(n);
			if (i < 0) {
				for (int k = 0; k < guests.size(); k++) if (guests.get(k).equalsIgnoreCase(n)) i = k;
			}
			if (i >= 0 && i < CrewkitAnchors.SEATS.length && !out.contains(i)) out.add(i);
		}
		return out;
	}

	private List<Integer> allSeats() {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < Math.min(guests.size(), CrewkitAnchors.SEATS.length); i++) out.add(i);
		return out;
	}

	private Pos platePos(int seat) {
		double[] s = CrewkitAnchors.SEATS[seat];
		double dz = s[2] == 0 ? 0.85 : -0.85;
		return rel(s[0], PLATE_Y, s[1] + dz);
	}

	private Pos plateSlot(int seat, int k) {
		Pos c = platePos(seat);
		double[][] slots = {{-0.13, 0.0}, {0.13, 0.0}, {0.0, -0.13}, {0.0, 0.13}};
		double[] o = slots[k % slots.length];
		return new Pos(c.x + o[0], c.y + 0.05 + 0.12 * (k / slots.length), c.z + o[1]);
	}

	// ---------------------------------------------------------------- failed / expired

	private void onFailed(MinecraftServer server, String event, JsonObject data) {
		qrUrl = null;
		clearQr(server, true);
		String reason = str(data, "reason", str(data, "status", event));
		setTicket(server, PAPER, ("expired".equals(event) ? "EXPIRED" : "FAILED") + (reason.isEmpty() ? "" : ": " + reason), "#B91C1C");
		tp(server, "ck_flow_ticket", rel(RAIL_X, TICKET_Y, PASS_Z));
		sound(server, "block.beacon.deactivate", rel(QR_X + QR_TILES / 2.0, QR_Y + 1.0, PASS_Z), 0.8, 1.0);
	}

	// ---------------------------------------------------------------- ticket

	private void setTicket(MinecraftServer server, int bg, String newStatus, String color) {
		ticketBg = bg;
		status = newStatus;
		statusColor = color;
		if (!ticketSpawned) return;
		run(server, "data merge entity @e[tag=ck_flow_ticket,limit=1] {background:" + bg + ",text:" + ticketText() + "}");
	}

	private String ticketText() {
		boolean dark = ticketBg != PAPER;
		String ink = dark ? "#FFFFFF" : "#1E1E1E";
		String soft = dark ? "#F4F4F4" : "#5A4A2A";
		StringBuilder sb = new StringBuilder("{text:" + q("ORDER TICKET\n") + ",color:\"" + soft + "\",bold:true,extra:[");
		sb.append("{text:").append(q(clip(title, 28) + "\n")).append(",color:\"").append(ink).append("\",bold:true}");
		String meta = guests.size() + " guests" + (budget.isEmpty() ? "" : "  ·  budget " + budget);
		sb.append(",{text:").append(q(meta + "\n")).append(",color:\"").append(ink).append("\",bold:false}");
		int shown = 0;
		for (String need : needs) {
			if (shown == 5) {
				sb.append(",{text:").append(q("  + " + (needs.size() - 5) + " more\n")).append(",color:\"").append(soft).append("\",bold:false}");
				break;
			}
			sb.append(",{text:").append(q("• " + clip(need, 30) + "\n")).append(",color:\"").append(ink).append("\",bold:false}");
			shown++;
		}
		if (!status.isEmpty()) {
			sb.append(",{text:").append(q("\n" + status)).append(",color:\"").append(dark ? "#FFFFFF" : statusColor).append("\",bold:true}");
		}
		sb.append("]}");
		return sb.toString();
	}

	private static String gateSign(String over) {
		return "{text:\"CREWKIT GATE\",color:\"#FFFFFF\",bold:true,extra:[{text:" + q(over.isEmpty() ? "\ncheckout blocked" : "\nover by " + over)
			+ ",bold:false}]}";
	}

	private void pop(MinecraftServer server, String tag) {
		setTransform(server, tag, scaleOnly(1.12), 3);
		later(3, () -> setTransform(server, tag, scaleOnly(1.0), 4));
	}

	// ---------------------------------------------------------------- command helpers

	private void later(long ticks, Runnable r) {
		tasks.add(new Task(now + Math.max(1, ticks), r));
	}

	private static void tp(MinecraftServer server, String tag, Pos p) {
		run(server, "tp @e[tag=" + tag + "] " + p);
	}

	private static void setTransform(MinecraftServer server, String selectorTags, String transform, int duration) {
		run(server, "data merge entity @e[tag=" + selectorTags + (selectorTags.contains("limit=") ? "" : ",limit=1") + "] {transformation:"
			+ transform + ",start_interpolation:0,interpolation_duration:" + duration + "}");
	}

	private static void sound(MinecraftServer server, String id, Pos p, double vol, double pitch) {
		run(server, "playsound minecraft:" + id + " master @a " + p + " " + f(vol) + " " + f(pitch) + " " + f(vol * 0.6));
	}

	private static void run(MinecraftServer server, String command) {
		CommandSourceStack source = server.createCommandSourceStack().withSource(FAILURE_LOGGER);
		server.getCommands().performPrefixedCommand(source, command);
	}

	private static final CommandSource FAILURE_LOGGER = new CommandSource() {
		@Override public void sendSystemMessage(Component message) { LOGGER.warn("CrewKit flow command: {}", message.getString()); }
		@Override public boolean acceptsSuccess() { return false; }
		@Override public boolean acceptsFailure() { return true; }
		@Override public boolean shouldInformAdmins() { return false; }
	};

	private static String tags(String... own) {
		StringBuilder sb = new StringBuilder("Tags:[\"crewkit\",\"" + TAG + "\"");
		for (String t : own) sb.append(",\"").append(t).append('"');
		return sb.append(']').toString();
	}

	/** Box with its min corner at the entity, sized w x h x d. */
	private static String box(double w, double h, double d) {
		return "{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[" + f(-w / 2) + "f,0f," + f(-d / 2)
			+ "f],scale:[" + f(w) + "f," + f(h) + "f," + f(d) + "f]}";
	}

	/** Box centred on the entity in all three axes. */
	private static String centeredBox(double w, double h, double d) {
		return "{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[" + f(-w / 2) + "f," + f(-h / 2) + "f,"
			+ f(-d / 2) + "f],scale:[" + f(w) + "f," + f(h) + "f," + f(d) + "f]}";
	}

	private static String scaleOnly(double s) {
		return "{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[" + f(s) + "f," + f(s) + "f," + f(s) + "f]}";
	}

	private record Pos(double x, double y, double z) {
		Pos up(double dy) { return new Pos(x, y + dy, z); }
		@Override public String toString() { return f(x) + " " + f(y) + " " + f(z); }
	}

	private static Pos rel(double x, double y, double z) {
		return new Pos(CrewkitAnchors.origin.getX() + x, CrewkitAnchors.origin.getY() + y, CrewkitAnchors.origin.getZ() + z);
	}

	private static String f(double v) {
		return String.format(Locale.ROOT, "%.3f", v);
	}

	private static int argb(long v) {
		return (int) v;
	}

	/** Quoted SNBT string. Newlines stay literal; quotes and backslashes are escaped. */
	private static String q(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') sb.append('\\').append(c);
			else if (c == '\n' || c >= 0x20) sb.append(c);
		}
		return sb.append('"').toString();
	}

	private static String clip(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max - 1) + "…";
	}

	private static String shortId(String id) {
		return id.length() <= 10 ? id : id.substring(id.length() - 8);
	}

	private static String safeItem(String id) {
		String s = id.contains(":") ? id : "minecraft:" + id;
		return s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? s : "minecraft:paper";
	}

	private static String str(JsonObject o, String key, String fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}

	private static int intOf(JsonObject o, String key, int fallback) {
		try {
			JsonElement e = o.get(key);
			return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
		} catch (RuntimeException ex) {
			return fallback;
		}
	}

	private static JsonArray arr(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	/** {amount, currency} in major units -> "S$150.00". */
	static String money(JsonElement e) {
		if (e == null || !e.isJsonObject()) return "";
		JsonObject o = e.getAsJsonObject();
		JsonElement a = o.get("amount");
		if (a == null || !a.isJsonPrimitive()) return "";
		double amount;
		try {
			amount = Double.parseDouble(a.getAsString());
		} catch (NumberFormatException ex) {
			return a.getAsString();
		}
		String cur = str(o, "currency", "SGD").toUpperCase(Locale.ROOT);
		String sym = switch (cur) {
			case "SGD" -> "S$";
			case "USD" -> "US$";
			default -> cur + " ";
		};
		return (amount < 0 ? "-" : "") + sym + String.format(Locale.ROOT, "%.2f", Math.abs(amount));
	}
}
