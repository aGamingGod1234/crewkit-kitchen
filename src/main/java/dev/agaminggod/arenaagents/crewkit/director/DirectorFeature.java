package dev.agaminggod.arenaagents.crewkit.director;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import java.util.ArrayDeque;
import java.util.Deque;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Camera director: maps contract events to camera marks and sends one shot at a time.
 * A shot holds for its dwell before the next queued shot starts, so each beat lands before the
 * next begins. When nothing shot-worthy happens for 5 s it visits the guests (table A, table B,
 * then the wide), and any real event cuts that rotation off at once.
 * Clients only follow when the operator ran /ckcam on.
 * State is static because CrewkitFeatures.all() may build new instances.
 */
public final class DirectorFeature implements CrewkitFeature {
	static { CrewkitCameraPayload.register(); }

	/** Default glide time between marks for manual stepping, in ticks. */
	public static final int MOVE_TICKS = 30;
	private static final int MAX_QUEUE = 4;
	/** Quiet time before the guest rotation starts. */
	private static final int IDLE_TICKS = 100;
	/** The quotes shot is re-cut at most once per this many ticks (quotes arrive in bursts). */
	private static final int QUOTES_GAP = 80;

	record Shot(String mark, int dwellTicks, boolean idle) {
		Shot(String mark, int dwellTicks) { this(mark, dwellTicks, false); }
	}

	private static final Deque<Shot> QUEUE = new ArrayDeque<>();
	private static String current;
	private static boolean currentIdle;
	private static long now;
	private static long nextAllowed;
	private static long lastCut;
	private static long lastQuotes = -QUOTES_GAP;
	private static boolean urgent;

	/** Event to shot, or null to leave the camera where it is. Dwell = how long the shot holds after arriving. */
	static Shot shotFor(String event, JsonObject data) {
		return switch (event == null ? "" : event) {
			case "brief" -> new Shot("brief", 90);
			case "candidates", "item_added" -> new Shot("line", 60);
			case "item_removed" -> new Shot("line", 50);
			case "quote" -> new Shot("quotes", 60);
			case "activity" -> activityShot(data);
			case "gate_blocked" -> new Shot("gate", 60);
			case "gate_passed" -> new Shot("gate", 50);
			// The stablecoin skit cues its own shots and then the QR.
			case "checkout" -> dev.agaminggod.arenaagents.crewkit.fun.skit.StablecoinSkit.active() ? null : new Shot("qr", 100);
			case "completed" -> new Shot("door", 60);
			case "record" -> new Shot("bill", 120);
			case "failed", "expired" -> new Shot("budget", 60);
			case "reset" -> new Shot("wide", 20);
			default -> null; // "calls" and friends: the counter ticks in whatever shot is live
		};
	}

	/** Quote results and the busy signal go to the money shot; pending and think lines are too frequent to cut on. */
	private static Shot activityShot(JsonObject data) {
		String kind = str(data, "kind"), result = str(data, "result");
		if ("think".equals(kind) || "pending".equals(result) || result.isEmpty() && !"busy".equals(kind)) return null;
		if ("quote".equals(kind) || "busy".equals(kind) || "busy".equals(result)) return new Shot("quotes", 60);
		return null;
	}

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		Shot shot = shotFor(event, data);
		if (shot == null) return;
		synchronized (QUEUE) {
			// A real beat ends the guest rotation right away.
			QUEUE.removeIf(Shot::idle);
			if (currentIdle) { nextAllowed = now; currentIdle = false; }
			if ("reset".equals(event)) { QUEUE.clear(); current = null; nextAllowed = now; }
			// The bag drops right away, so the door shot cuts in now with a short move instead of waiting its turn.
			if ("completed".equals(event)) { QUEUE.clear(); nextAllowed = now; urgent = true; current = null; }
			Shot last = QUEUE.peekLast();
			String tail = last != null ? last.mark() : current;
			if (shot.mark().equals("quotes") && now - lastQuotes < QUOTES_GAP && !"quotes".equals(tail)) return;
			if (!shot.mark().equals(tail)) QUEUE.addLast(shot);
			// Blocked at the gate: show why, on the budget board.
			if ("gate_blocked".equals(event)) QUEUE.addLast(new Shot("budget", 60));
			// After the door: the chef serves each guest, then the party.
			if ("completed".equals(event)) { QUEUE.addLast(new Shot("plating", 120)); QUEUE.addLast(new Shot("celebrate", 100)); }
			while (QUEUE.size() > MAX_QUEUE) QUEUE.pollFirst(); // behind schedule: drop the oldest beat
		}
	}

	/**
	 * A guest started talking at an absolute position. If the camera is idle, cut to that guest's table.
	 * Called by GuestChatterFeature.
	 */
	public static void guestSpoke(Vec3 at) {
		var o = CrewkitAnchors.origin;
		double x = at.x - o.getX();
		String mark = x < 10 ? "guests_a" : x < 18 ? "guests_b" : "guests_wide";
		synchronized (QUEUE) {
			boolean idle = QUEUE.isEmpty() && (currentIdle || now - lastCut >= IDLE_TICKS && now >= nextAllowed);
			if (!idle || mark.equals(current) || now - lastCut < 60) return;
			QUEUE.clear();
			QUEUE.addLast(new Shot(mark, 100, true));
			nextAllowed = now;
		}
	}

	/** Cuts to {@code mark} next, dropping queued beats (used by skits that drive their own beats). */
	public static void cue(String mark, int dwellTicks) {
		synchronized (QUEUE) {
			QUEUE.clear();
			QUEUE.addLast(new Shot(mark, dwellTicks));
			nextAllowed = now;
			currentIdle = false;
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		Shot shot;
		int move;
		String from;
		synchronized (QUEUE) {
			now++;
			if (QUEUE.isEmpty() && now >= nextAllowed && now - lastCut >= IDLE_TICKS && current != null) {
				// Downtime: visit the guests, then settle on the wide. Each shot drifts slowly while it holds.
				QUEUE.addLast(new Shot("guests_a", 100, true));
				QUEUE.addLast(new Shot("guests_b", 100, true));
				QUEUE.addLast(new Shot("wide", 120, true));
			}
			if (now < nextAllowed || QUEUE.isEmpty()) return;
			shot = QUEUE.pollFirst();
			from = current;
			current = shot.mark();
			currentIdle = shot.idle();
			move = urgent ? 14 : CrewkitMarks.moveTicks(from, shot.mark());
			urgent = false;
			lastCut = now;
			if ("quotes".equals(shot.mark())) lastQuotes = now;
			// Idle shots may be cut short by a real beat, so they only block the queue for their glide.
			nextAllowed = now + move + shot.dwellTicks();
		}
		send(server, shot.mark(), move, shot.dwellTicks());
	}

	@Override
	public void reset(MinecraftServer server) {
		synchronized (QUEUE) { QUEUE.clear(); current = null; currentIdle = false; nextAllowed = now; urgent = false; }
	}

	/** Sends a mark to every client that has the CrewKit camera. */
	public static void send(MinecraftServer server, String mark, int moveTicks, int holdTicks) {
		var o = CrewkitAnchors.origin;
		var payload = new CrewkitCameraPayload(mark, moveTicks, holdTicks, o.getX(), o.getY(), o.getZ());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, CrewkitCameraPayload.TYPE)) ServerPlayNetworking.send(player, payload);
		}
	}

	private static String str(JsonObject data, String key) {
		if (data == null) return "";
		JsonElement e = data.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}
}
