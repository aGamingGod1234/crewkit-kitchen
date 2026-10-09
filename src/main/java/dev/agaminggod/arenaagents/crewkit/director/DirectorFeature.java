package dev.agaminggod.arenaagents.crewkit.director;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import java.util.ArrayDeque;
import java.util.Deque;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Camera director: maps contract events to camera marks and sends one shot at a time.
 * A shot holds for its dwell before the next queued shot starts, so each beat lands before the
 * next begins (one focal motion). Clients only follow when the operator ran /ckcam on.
 * State is static because CrewkitFeatures.all() may build new instances.
 */
public final class DirectorFeature implements CrewkitFeature {
	static { CrewkitCameraPayload.register(); }

	/** Glide time between marks, in ticks. */
	public static final int MOVE_TICKS = 30;
	private static final int MAX_QUEUE = 4;

	record Shot(String mark, int dwellTicks) {}

	private static final Deque<Shot> QUEUE = new ArrayDeque<>();
	private static String current;
	private static long now;
	private static long nextAllowed;

	/** Event to shot, or null to leave the camera where it is. Dwell = how long the shot holds after arriving. */
	static Shot shotFor(String event) {
		return switch (event == null ? "" : event) {
			case "brief" -> new Shot("wide", 100);
			case "item_added" -> new Shot("line", 60);
			case "quote" -> new Shot("budget", 50);
			case "gate_blocked" -> new Shot("gate", 70);
			case "item_removed" -> new Shot("line", 50);
			case "gate_passed" -> new Shot("gate", 50);
			case "checkout" -> new Shot("qr", 80);
			case "completed" -> new Shot("door", 90);
			case "record" -> new Shot("bill", 100);
			case "failed", "expired" -> new Shot("gate", 60);
			case "reset" -> new Shot("wide", 20);
			default -> null; // "calls": the counter ticks in whatever shot is live
		};
	}

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		Shot shot = shotFor(event);
		if (shot == null) return;
		synchronized (QUEUE) {
			if ("reset".equals(event)) { QUEUE.clear(); current = null; nextAllowed = now; }
			Shot last = QUEUE.peekLast();
			String tail = last != null ? last.mark() : current;
			if (shot.mark().equals(tail)) return; // already there or on the way
			QUEUE.addLast(shot);
			// Plating follows the door: the chef unpacks, then serves each guest.
			if ("completed".equals(event)) QUEUE.addLast(new Shot("plating", 140));
			while (QUEUE.size() > MAX_QUEUE) QUEUE.pollFirst(); // behind schedule: drop the oldest beat
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		Shot shot;
		synchronized (QUEUE) {
			now++;
			if (now < nextAllowed || QUEUE.isEmpty()) return;
			shot = QUEUE.pollFirst();
			current = shot.mark();
			nextAllowed = now + MOVE_TICKS + shot.dwellTicks();
		}
		send(server, shot.mark(), MOVE_TICKS);
	}

	@Override
	public void reset(MinecraftServer server) {
		synchronized (QUEUE) { QUEUE.clear(); current = null; nextAllowed = now; }
	}

	/** Sends a mark to every client that has the CrewKit camera. */
	public static void send(MinecraftServer server, String mark, int moveTicks) {
		var o = CrewkitAnchors.origin;
		var payload = new CrewkitCameraPayload(mark, moveTicks, o.getX(), o.getY(), o.getZ());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(player, CrewkitCameraPayload.TYPE)) ServerPlayNetworking.send(player, payload);
		}
	}
}
