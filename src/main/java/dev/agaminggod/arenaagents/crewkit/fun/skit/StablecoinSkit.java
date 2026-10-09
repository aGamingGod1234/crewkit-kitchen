package dev.agaminggod.arenaagents.crewkit.fun.skit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.crewkit.cast.CastFeature;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDispatcher;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplay;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplays;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSounds;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitText;
import dev.agaminggod.arenaagents.crewkit.director.DirectorFeature;
import dev.agaminggod.arenaagents.crewkit.fun.bubble.ChefBubbleFeature;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Stablecoin payment skit, played on checkout before the QR appears: the first seated guest walks to the
 * pass with cash, the chef only takes stablecoin, the guest swaps cash for USDC at a dusty broker nook in the
 * south-west dining corner, pays the chef, and only then does FlowFeature draw the QR.
 *
 * Everything it spawns is a display entity or mannequin tagged ck_skit (no block changes). The broker nook sits in
 * x 2..5, z 17..19.8: south of the walk paths (back aisle z 15.25), east of the west sideboard (x=1, z 18..20).
 * State is static because CrewkitFeatures.all() may build new instances.
 */
public final class StablecoinSkit implements CrewkitFeature {
	private static final String TAG = "ck_skit";
	private static final String NOOK_TAG = "ck_skit_nook";
	private static final double SPEED = 0.17;
	private static final int TIMEOUT_TICKS = 700;

	// Relative positions (docs/crewkit/SET-ZONES.md).
	private static final double[] PASS_SPOT = {9.5, 10.05};
	private static final double[] BROKER = {2.7, 18.8};
	private static final double[] BROKER_CUSTOMER = {4.95, 18.8};
	private static final double[][] TO_BROKER = {{9.5, 10.2}, {9.5, 15.2}, {5.6, 15.2}, {5.6, 18.8}, BROKER_CUSTOMER};
	private static final double[][] TO_PASS = {{5.6, 18.8}, {5.6, 15.2}, {9.5, 15.2}, {9.5, 10.2}, PASS_SPOT};

	private static final String USDC = "0xFabab97dCE620294D2B0b0e46C68964e326300Ac";
	private static final String RPC = "https://rpc-gel-sepolia.inkonchain.com";

	private static boolean enabled = true;
	private static boolean running;
	private static Runnable onDone;
	private static long t;
	private static int beat;

	private static UUID guestId;
	private static boolean tempGuest;
	private static UUID seatId;
	private static float seatYaw;
	private static Vec3 seatStand;
	private static UUID brokerId;
	private static final Deque<Vec3> PATH = new ArrayDeque<>();
	private static float endYaw = Float.NaN;
	private static Vec3 legFrom;
	private static int legTicks;
	private static int legElapsed;

	private static CrewkitDisplay cash;
	private static CrewkitDisplay coin;
	private static CrewkitDisplay sign;
	private static Arc arc;
	private static String holding; // "guest", "broker", "chef" or null
	private static final List<Bubble> BUBBLES = new ArrayList<>();
	private static volatile String treasuryLine = "";

	private record Arc(CrewkitDisplay item, Vec3 from, Vec3 to, int ticks, long start) {}

	private static final class Bubble {
		final CrewkitDisplay display;
		final UUID follow;
		final long until;
		Bubble(CrewkitDisplay display, UUID follow, long until) { this.display = display; this.follow = follow; this.until = until; }
	}

	// ---------------------------------------------------------------- public API

	public static boolean enabled() { return enabled; }
	public static void setEnabled(boolean on) { enabled = on; }
	public static boolean active() { return running; }

	/**
	 * Starts the skit; {@code done} runs on the server thread when it ends (or fails). Returns false when the skit
	 * is off or could not start, in which case the caller should show the QR right away.
	 */
	public static boolean play(MinecraftServer server, Runnable done) {
		if (!enabled) return false;
		if (running) { onDone = done; return true; }
		try {
			start(server);
			onDone = done;
			return true;
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit skit: could not start", e);
			abort(server);
			return false;
		}
	}

	// ---------------------------------------------------------------- feature

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		if ("reset".equals(event)) reset(server);
	}

	@Override
	public void tick(MinecraftServer server) {
		if (!running) return;
		try {
			t++;
			step(server);
			if (t > TIMEOUT_TICKS) finish(server);
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit skit: tick failed, showing the QR", e);
			abort(server);
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		try {
			onDone = null;
			if (running) cleanupActors(server, true);
			running = false;
			CrewkitDisplays.killTag(server, TAG);
			brokerId = null;
			sign = null;
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit skit: reset failed", e);
		}
	}

	// ---------------------------------------------------------------- timeline

	private static void start(MinecraftServer server) {
		ServerLevel level = server.overworld();
		t = 0;
		beat = 0;
		holding = null;
		arc = null;
		PATH.clear();
		legFrom = null;
		BUBBLES.clear();
		buildNook(server);
		fetchTreasury(server);

		// Pick the guest at the first seat (James in the demo brief); else any seated guest; else a stand-in.
		Entity guest = null;
		Vec3 seat0 = rel(CrewkitAnchors.SEATS[0][0], CastFeature.SEAT_Y, CrewkitAnchors.SEATS[0][1]);
		double best = Double.MAX_VALUE;
		for (Entity e : level.getAllEntities()) {
			if (!e.entityTags().contains("ck_guest") || e.getVehicle() == null) continue;
			double d = e.getVehicle().position().distanceToSqr(seat0);
			if (d < best) { best = d; guest = e; }
		}
		tempGuest = guest == null;
		if (guest != null) {
			Entity seat = guest.getVehicle();
			seatId = seat.getUUID();
			seatYaw = guest.getYRot();
			double dz = Math.abs(Mth.wrapDegrees(seatYaw)) < 90 ? -1.0 : 1.0; // stand up on the aisle side
			seatStand = new Vec3(seat.getX(), CrewkitAnchors.origin.getY() + CastFeature.FLOOR_Y, seat.getZ() + dz);
			guest.stopRiding();
			place(guest, seatStand, seatYaw);
		} else {
			seatId = null;
			seatStand = rel(4.5, CastFeature.FLOOR_Y, 10.4);
			seatYaw = 180f;
			guestId = summonMannequin(server, seatStand, 180f, "James", "steve", "wide");
			guest = guestId == null ? null : level.getEntity(guestId);
			if (guest == null) throw new IllegalStateException("no guest");
		}
		guestId = guest.getUUID();
		running = true;

		cash = CrewkitDisplays.item(server, hand(guest), TAG, "minecraft:paper", 0.35f, "fixed",
				"teleport_duration:2,billboard:\"vertical\"");
		holding = "guest";
		CrewkitSounds.play(server, guest.position(), "minecraft:block.wool.break", 0.6f, 1.2f);

		CastFeature.chefWalkTo(server, CastFeature.passPos(), 25);
		walk(new double[][] {{seatStand.x - CrewkitAnchors.origin.getX(), 10.2}, PASS_SPOT}, 180f);
		DirectorFeature.cue("pass_pay", 140);
	}

	private static void step(MinecraftServer server) {
		ServerLevel level = server.overworld();
		Entity guest = guestId == null ? null : level.getEntity(guestId);
		if (guest == null) { finish(server); return; }
		boolean walking = stepWalk(guest);
		Entity chef = chef(server);
		Vec3 chefHand = chef != null ? hand(chef) : CastFeature.passPos().add(0, 1.1, 0.4);
		Entity broker = brokerId == null ? null : level.getEntity(brokerId);
		Vec3 brokerHand = broker != null ? hand(broker) : rel(BROKER[0] + 0.5, 1.1, BROKER[1]);

		// Held props follow their holder; arcs fly between hands.
		if (arc != null) {
			double k = Math.min(1.0, (t - arc.start) / (double) arc.ticks);
			Vec3 p = arc.from.lerp(arc.to, k).add(0, Math.sin(k * Math.PI) * 0.9, 0);
			tp(server, arc.item, p);
			if (k >= 1.0) arc = null;
		} else if (holding != null) {
			CrewkitDisplay held = "guest".equals(holding) && cash != null ? cash : coin;
			if ("guest".equals(holding) && cash == null) held = coin;
			if (held != null) {
				Vec3 at = switch (holding) { case "broker" -> brokerHand; case "chef" -> chefHand; default -> hand(guest); };
				tp(server, held, at);
			}
		}
		if (coin != null && t % 3 == 0) {
			Vec3 c = coinPos != null ? coinPos : coin.pos();
			CrewkitDisplays.run(server, String.format(Locale.ROOT, "particle minecraft:end_rod %.3f %.3f %.3f 0.12 0.12 0.12 0.01 1", c.x, c.y, c.z));
		}
		if (t % 6 == 0) {
			Vec3 dust = rel(3.6, 2.6, 18.6);
			CrewkitDisplays.run(server, String.format(Locale.ROOT, "particle minecraft:white_ash %.3f %.3f %.3f 1.2 1.0 1.0 0.01 6", dust.x, dust.y, dust.z));
		}
		tickBubbles(server, level);

		switch (beat) {
			case 0 -> { if (!walking) { beat++; t0 = t; } }
			case 1 -> { if (t - t0 >= 6) {
				ChefBubbleFeature.say(server, "Sorry, we only accept stablecoin!", 0xE0302A);
				CrewkitSounds.play(server, CastFeature.passPos(), "minecraft:entity.villager.no", 0.9f, 1.0f);
				beat++; t0 = t; } }
			case 2 -> { if (t - t0 >= 32) {
				bubble(server, guest, "Seriously?!", 0xE0302A, 40);
				CrewkitSounds.play(server, guest.position(), "minecraft:entity.villager.ambient", 0.7f, 1.4f);
				beat++; t0 = t; } }
			case 3 -> { if (t - t0 >= 30) {
				DirectorFeature.cue("broker", 220);
				walk(TO_BROKER, 90f);
				beat++; } }
			case 4 -> { if (!walking) { beat++; t0 = t; } }
			case 5 -> { if (t - t0 >= 6) {
				arc = new Arc(cash, hand(guest), brokerHand, 14, t);
				holding = "broker";
				CrewkitSounds.play(server, guest.position(), "minecraft:item.book.page_turn", 0.9f, 1.1f);
				beat++; t0 = t; } }
			case 6 -> { if (t - t0 >= 16) {
				if (cash != null) { cash.kill(); cash = null; }
				bubble(server, broker != null ? broker : guest, "Cash for USDC? Easy.", 0x18A04A, 50);
				CrewkitSounds.play(server, brokerHand, "minecraft:entity.villager.trade", 0.9f, 1.0f);
				beat++; t0 = t; } }
			case 7 -> { if (t - t0 >= 34) {
				coin = CrewkitDisplays.item(server, brokerHand, TAG, "minecraft:heart_of_the_sea", 0.4f, "fixed",
						"teleport_duration:2,billboard:\"vertical\",Glowing:1b,glow_color_override:5636095");
				coinPos = brokerHand;
				arc = new Arc(coin, brokerHand, hand(guest), 14, t);
				holding = "guest";
				CrewkitSounds.play(server, brokerHand, "minecraft:block.amethyst_block.chime", 1.0f, 1.2f);
				CrewkitSounds.play(server, brokerHand, "minecraft:block.amethyst_cluster.break", 0.6f, 1.6f);
				beat++; t0 = t; } }
			case 8 -> { if (t - t0 >= 24) {
				DirectorFeature.cue("pass_pay", 120);
				walk(TO_PASS, 180f);
				beat++; } }
			case 9 -> { if (!walking) { beat++; t0 = t; } }
			case 10 -> { if (t - t0 >= 6) {
				arc = new Arc(coin, hand(guest), chefHand, 14, t);
				holding = "chef";
				CrewkitSounds.play(server, guest.position(), "minecraft:block.amethyst_block.chime", 0.8f, 1.6f);
				beat++; t0 = t; } }
			case 11 -> { if (t - t0 >= 16) {
				ChefBubbleFeature.say(server, "Stablecoin received! Scan to approve.", 0x18A04A);
				CrewkitSounds.play(server, chefHand, "minecraft:entity.experience_orb.pickup", 1.0f, 1.0f);
				CrewkitSounds.play(server, chefHand, "minecraft:block.note_block.bell", 1.0f, 1.5f);
				beat++; t0 = t; } }
			case 12 -> { if (t - t0 >= 30) {
				if (coin != null) { coin.kill(); coin = null; }
				holding = null;
				fireDone();
				DirectorFeature.cue("qr", 100);
				walk(new double[][] {{9.5, 10.2}, {seatStand.x - CrewkitAnchors.origin.getX(), 10.2},
						{seatStand.x - CrewkitAnchors.origin.getX(), seatStand.z - CrewkitAnchors.origin.getZ()}}, seatYaw);
				beat++; } }
			case 13 -> { if (!walking) finish(server); }
			default -> finish(server);
		}
	}

	private static long t0;
	private static Vec3 coinPos;

	private static void fireDone() {
		Runnable done = onDone;
		onDone = null;
		if (done != null) {
			try { done.run(); } catch (RuntimeException e) { CrewkitDispatcher.LOGGER.warn("CrewKit skit: QR callback failed", e); }
		}
	}

	/** Normal end: QR (if not already shown), guest back on the seat, props gone. The nook stays until reset. */
	private static void finish(MinecraftServer server) {
		fireDone();
		cleanupActors(server, true);
		running = false;
	}

	private static void abort(MinecraftServer server) {
		try { fireDone(); } finally {
			try { cleanupActors(server, true); } catch (RuntimeException ignored) {}
			running = false;
		}
	}

	private static void cleanupActors(MinecraftServer server, boolean reseat) {
		ServerLevel level = server.overworld();
		if (cash != null) cash.kill();
		if (coin != null) coin.kill();
		cash = null;
		coin = null;
		arc = null;
		holding = null;
		for (Bubble b : BUBBLES) b.display.kill();
		BUBBLES.clear();
		PATH.clear();
		legFrom = null;
		Entity guest = guestId == null ? null : level.getEntity(guestId);
		if (guest != null) {
			if (tempGuest) guest.discard();
			else if (reseat) {
				Entity seat = seatId == null ? null : level.getEntity(seatId);
				if (seat != null && guest.getVehicle() == null) {
					place(guest, seat.position(), seatYaw);
					guest.startRiding(seat, true, true);
				}
				face(guest, seatYaw);
			}
		}
		guestId = null;
		seatId = null;
	}

	// ---------------------------------------------------------------- walking

	private static void walk(double[][] points, float yaw) {
		PATH.clear();
		for (double[] p : points) PATH.add(rel(p[0], CastFeature.FLOOR_Y, p[1]));
		endYaw = yaw;
		legFrom = null;
	}

	/** Steps the guest along PATH; returns true while still walking (or turning). */
	private static boolean stepWalk(Entity guest) {
		if (legFrom == null) {
			Vec3 next = PATH.peek();
			if (next == null) {
				if (Float.isNaN(endYaw)) return false;
				float yaw = Mth.approachDegrees(guest.getYRot(), endYaw, 24f);
				face(guest, yaw);
				if (Math.abs(Mth.wrapDegrees(yaw - endYaw)) < 0.5f) { endYaw = Float.NaN; return false; }
				return true;
			}
			legFrom = guest.position();
			legTicks = Math.max(1, (int) Math.ceil(legFrom.distanceTo(next) / SPEED));
			legElapsed = 0;
		}
		Vec3 to = PATH.peek();
		legElapsed++;
		double k = Math.min(1.0, legElapsed / (double) legTicks);
		Vec3 delta = to.subtract(legFrom);
		float yaw = guest.getYRot();
		if (delta.horizontalDistanceSqr() > 1.0E-4) {
			yaw = Mth.approachDegrees(yaw, (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90f, 24f);
		}
		place(guest, legFrom.lerp(to, k), yaw);
		if (k >= 1.0) { PATH.poll(); legFrom = null; }
		return true;
	}

	private static void place(Entity e, Vec3 pos, float yaw) {
		e.snapTo(pos.x, pos.y, pos.z, yaw, 0f);
		e.setDeltaMovement(Vec3.ZERO);
		face(e, yaw);
	}

	private static void face(Entity e, float yaw) {
		e.setYRot(yaw);
		e.setYHeadRot(yaw);
		if (e instanceof LivingEntity living) living.setYBodyRot(yaw);
	}

	/** In front of and just above the right hand. */
	private static Vec3 hand(Entity e) {
		double r = Math.toRadians(e.getYRot());
		double fx = -Math.sin(r), fz = Math.cos(r);
		return e.position().add(fx * 0.45 - fz * 0.25, 1.15, fz * 0.45 + fx * 0.25);
	}

	// ---------------------------------------------------------------- bubbles

	private static void bubble(MinecraftServer server, Entity speaker, String text, int color, int ticks) {
		Vec3 at = speaker.position().add(0, 2.35, 0);
		CrewkitDisplay d = CrewkitDisplays.text(server, at, TAG, CrewkitText.of(" " + text + " ", color, true), 0.01f,
				"billboard:\"center\",background:" + CrewkitText.argb(0xF2, 0xFFFFFF) + ",shadow:0b,line_width:170,teleport_duration:3");
		d.transform(1.2f, 0, 0, 0, 4);
		CrewkitSounds.play(server, at, "minecraft:entity.chicken.egg", 0.45f, 1.6f);
		BUBBLES.add(new Bubble(d, speaker.getUUID(), t + ticks));
	}

	private static void tickBubbles(MinecraftServer server, ServerLevel level) {
		for (var it = BUBBLES.iterator(); it.hasNext(); ) {
			Bubble b = it.next();
			if (t >= b.until) {
				b.display.transform(0.01f, 0, 0, 0, 4);
				CrewkitDisplay d = b.display;
				dev.agaminggod.arenaagents.crewkit.core.CrewkitSchedule.after(5, d::kill);
				it.remove();
				continue;
			}
			Entity e = level.getEntity(b.follow);
			if (e != null && t % 2 == 0) tp(server, b.display, e.position().add(0, 2.35, 0));
		}
	}

	// ---------------------------------------------------------------- broker nook

	/** Builds the broker nook from display entities, once; safe to call again. */
	public static void buildNook(MinecraftServer server) {
		ServerLevel level = server.overworld();
		if (brokerId != null && level.getEntity(brokerId) != null) return;
		CrewkitDisplays.killTag(server, NOOK_TAG);
		String tag = NOOK_TAG;
		// Desk: dark oak top on spruce legs, between the broker (west) and the customer (east).
		CrewkitDisplays.block(server, rel(3.3, 1.0, 18.15), tag, "minecraft:spruce_planks", 0.12f, 0.72f, 0.12f, extra());
		CrewkitDisplays.block(server, rel(3.3, 1.0, 19.3), tag, "minecraft:spruce_planks", 0.12f, 0.72f, 0.12f, extra());
		CrewkitDisplays.block(server, rel(4.2, 1.0, 18.15), tag, "minecraft:spruce_planks", 0.12f, 0.72f, 0.12f, extra());
		CrewkitDisplays.block(server, rel(4.2, 1.0, 19.3), tag, "minecraft:spruce_planks", 0.12f, 0.72f, 0.12f, extra());
		CrewkitDisplays.block(server, rel(3.2, 1.72, 18.05), tag, "minecraft:dark_oak_planks", 1.2f, 0.12f, 1.5f, extra());
		// Ledger and a little coin stack on the desk.
		CrewkitDisplays.block(server, rel(3.5, 1.84, 18.5), tag, "minecraft:white_wool", 0.35f, 0.04f, 0.28f, extra());
		CrewkitDisplays.block(server, rel(4.0, 1.84, 19.0), tag, "minecraft:gold_block", 0.14f, 0.12f, 0.14f, extra());
		// Stool behind the desk.
		CrewkitDisplays.block(server, rel(2.35, 1.0, 19.25), tag, "minecraft:spruce_log", 0.35f, 0.5f, 0.35f, extra());
		// Cobwebs in the corner and along the top of the wall.
		CrewkitDisplays.block(server, rel(2.0, 1.0, 19.6), tag, "minecraft:cobweb", 0.8f, 0.8f, 0.35f, extra());
		CrewkitDisplays.block(server, rel(2.0, 4.6, 17.1), tag, "minecraft:cobweb", 1.0f, 1.0f, 0.6f, extra());
		CrewkitDisplays.block(server, rel(2.0, 4.8, 19.3), tag, "minecraft:cobweb", 0.9f, 0.9f, 0.6f, extra());
		// Hanging lantern on a chain above the desk.
		CrewkitDisplays.block(server, rel(3.65, 4.0, 18.55), tag, "minecraft:iron_chain", 0.3f, 3.0f, 0.3f, extra());
		CrewkitDisplays.block(server, rel(3.5, 3.4, 18.4), tag, "minecraft:lantern", 0.6f, 0.6f, 0.6f, extra());

		brokerId = summonMannequin(server, rel(BROKER[0], CastFeature.FLOOR_Y, BROKER[1]), -90f, "Money Broker", "zuri", "wide");

		sign = CrewkitDisplays.text(server, rel(3.8, 2.95, 18.8), tag, signText(), 0.55f,
				"billboard:\"vertical\",background:" + CrewkitText.argb(0xE0, 0x0E3B2E) + ",line_width:260");
		CrewkitDisplays.text(server, rel(3.8, 2.0, 19.7), tag,
				CrewkitText.of("skit: payment runs on Reap sandbox", 0xFFFFFF, false), 0.32f,
				"billboard:\"vertical\",background:" + CrewkitText.argb(0xA0, 0x000000));
		CrewkitSounds.play(server, rel(3.5, 2, 18.8), "minecraft:block.wood.place", 0.6f, 0.8f);
	}

	private static String extra() {
		return "brightness:{sky:12,block:12},view_range:2f";
	}

	private static String signText() {
		String line = treasuryLine;
		String head = "{text:\"CASH ⇄ STABLECOIN\",color:\"#7CFFB2\",bold:true}";
		if (line == null || line.isEmpty()) return head;
		return "[\"\"," + head + ",{text:\"\\n" + CrewkitText.escape(line) + "\",color:\"#BFE9FF\",bold:false}]";
	}

	// ---------------------------------------------------------------- live treasury balance (optional)

	private static void fetchTreasury(MinecraftServer server) {
		String wallet = System.getenv("CREWKIT_USDC_WALLET");
		if (wallet == null || !wallet.matches("0x[0-9a-fA-F]{40}")) return;
		CompletableFuture.runAsync(() -> {
			try {
				HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
				String data = "0x70a08231" + "0".repeat(24) + wallet.substring(2).toLowerCase(Locale.ROOT);
				String bal = rpc(http, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_call\",\"params\":[{\"to\":\"" + USDC
						+ "\",\"data\":\"" + data + "\"},\"latest\"]}");
				String block = rpc(http, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"eth_blockNumber\",\"params\":[]}");
				BigDecimal usdc = new BigDecimal(new BigInteger(bal.substring(2).isEmpty() ? "0" : bal.substring(2), 16), 6);
				long height = Long.parseLong(block.substring(2), 16);
				String line = String.format(Locale.ROOT, "Treasury: %s USDC · Ink Sepolia · block %d",
						usdc.setScale(2, java.math.RoundingMode.DOWN).toPlainString(), height);
				CrewkitDispatcher.LOGGER.info("CrewKit skit: {}", line);
				server.execute(() -> {
					treasuryLine = line;
					try { if (sign != null) sign.text(signText()); } catch (RuntimeException ignored) {}
				});
			} catch (Exception e) {
				CrewkitDispatcher.LOGGER.info("CrewKit skit: treasury balance unavailable ({})", e.toString());
			}
		});
	}

	private static String rpc(HttpClient http, String body) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(RPC)).timeout(Duration.ofSeconds(4))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
		HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
		JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
		if (!json.has("result")) throw new IllegalStateException("rpc error: " + json.get("error"));
		return json.get("result").getAsString();
	}

	// ---------------------------------------------------------------- helpers

	private static Entity chef(MinecraftServer server) {
		String name = CastFeature.chefName(server);
		if (name != null) {
			ServerPlayer p = server.getPlayerList().getPlayerByName(name);
			if (p != null) return p;
		}
		for (Entity e : server.overworld().getAllEntities()) if (e.entityTags().contains("ck_chef") && e.isAlive()) return e;
		return null;
	}

	private static UUID summonMannequin(MinecraftServer server, Vec3 pos, float yaw, String name, String skin, String model) {
		UUID uuid = UUID.randomUUID();
		long most = uuid.getMostSignificantBits(), least = uuid.getLeastSignificantBits();
		String uuidNbt = "[I;" + (int) (most >> 32) + "," + (int) most + "," + (int) (least >> 32) + "," + (int) least + "]";
		CrewkitDisplays.run(server, String.format(Locale.ROOT,
				"summon minecraft:mannequin %.4f %.4f %.4f {UUID:%s,profile:{texture:\"minecraft:entity/player/%s/%s\",model:\"%s\"}"
						+ ",CustomName:\"%s\",CustomNameVisible:1b,hide_description:1b,immovable:1b,Invulnerable:1b,NoGravity:1b,Silent:1b"
						+ ",Tags:[\"crewkit\",\"%s\",\"%s\"],Rotation:[%sf,0f]}",
				pos.x, pos.y, pos.z, uuidNbt, model, skin, model, name, TAG, NOOK_TAG, Float.toString(yaw)));
		return server.overworld().getEntity(uuid) != null ? uuid : null;
	}

	private static void tp(MinecraftServer server, CrewkitDisplay d, Vec3 p) {
		if (d == null) return;
		CrewkitDisplays.run(server, String.format(Locale.ROOT, "tp %s %.4f %.4f %.4f", d.uuid(), p.x, p.y, p.z));
		if (d == coin) coinPos = p;
	}

	private static Vec3 rel(double x, double y, double z) {
		var o = CrewkitAnchors.origin;
		return new Vec3(o.getX() + x, o.getY() + y, o.getZ() + z);
	}
}
