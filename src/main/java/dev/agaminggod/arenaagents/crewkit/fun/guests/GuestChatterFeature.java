package dev.agaminggod.arenaagents.crewkit.fun.guests;

import com.google.gson.JsonObject;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Seated guests chat during the long middle of a run: a small speech bubble over one random guest every
 * 5-8 s, with lines that react to what the kitchen is doing. Guests are found by CastFeature's ck_guest tag,
 * so this feature never touches the cast's state. At most two bubbles show at once; each pops out after 3 s.
 */
public final class GuestChatterFeature implements CrewkitFeature {
	private static final String TAG = "ck_guest_chat";
	private static final int BUBBLE_TICKS = 60;
	private static final int MAX_BUBBLES = 1;
	private static final int MIN_GAP_TICKS = 220;
	private static final int GAP_SPREAD_TICKS = 120;
	/** Reactive lines can jump the idle queue, but not more often than this, so an activity burst stays readable. */
	private static final int REACT_COOLDOWN_TICKS = 120;
	private static final float BUBBLE_SCALE = 0.8f;
	private static final double BUBBLE_HEIGHT = 2.45;

	private static final String[] GENERIC = {
		"Is the food here yet?", "I hope I get the blue pen", "Chef looks stressed",
		"Budget? What budget?", "I'll take ANY notebook", "Smells like... stationery?",
		"Do pens count as dinner?",
	};
	private static final String[] SOLD_OUT = {"Noooo, not the badges!", "Sold out?! Again?"};
	private static final String[] OVER = {"S$196?! For pens??", "That's over budget!"};
	private static final String[] BUSY = {"Reap's on a coffee break", "Hold on, the shop is busy"};
	private static final String[] PASSED = {"Ooh, under budget!", "Nice, it fits!"};
	private static final String[] CHEERS = {"YAY!", "Finally!", "Thanks Chef!"};

	private final Random random = new Random();
	private final Deque<Bubble> bubbles = new ArrayDeque<>();
	private boolean active;
	private long clock;
	private long nextChatAt;
	private long lastReactAt = -1000;
	private int lastGuest = -1;

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		try {
			switch (event) {
				case "brief" -> {
					active = true;
					// Guests walk in first; give them time to sit before anyone talks.
					nextChatAt = clock + 200;
				}
				case "gate_blocked" -> react(server, OVER, "angry_villager");
				case "gate_passed" -> react(server, PASSED, "happy_villager");
				case "activity" -> {
					switch (str(data, "result")) {
						case "sold_out" -> react(server, SOLD_OUT, "angry_villager");
						case "busy" -> react(server, BUSY, "note");
						case "over" -> react(server, OVER, "angry_villager");
						default -> { }
					}
				}
				case "completed" -> {
					active = false;
					celebrate(server);
				}
				case "failed", "expired" -> active = false;
				case "reset" -> reset(server);
				default -> { }
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.debug("CrewKit guest chatter skipped {}: {}", event, e.toString());
		}
	}

	@Override
	public void tick(MinecraftServer server) {
		clock++;
		try {
			while (!bubbles.isEmpty() && clock >= bubbles.peekFirst().expiresAt()) pop(bubbles.pollFirst());
			if (active && clock >= nextChatAt) {
				nextChatAt = clock + MIN_GAP_TICKS + random.nextInt(GAP_SPREAD_TICKS);
				say(server, pick(GENERIC), "note");
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.debug("CrewKit guest chatter tick failed: {}", e.toString());
		}
	}

	@Override
	public void reset(MinecraftServer server) {
		active = false;
		bubbles.clear();
		lastGuest = -1;
		try {
			CrewkitDisplays.killTag(server, TAG);
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.debug("CrewKit guest chatter reset failed: {}", e.toString());
		}
	}

	// ---------------------------------------------------------------- lines

	private void react(MinecraftServer server, String[] lines, String particle) {
		if (!active || clock - lastReactAt < REACT_COOLDOWN_TICKS) return;
		lastReactAt = clock;
		// Push the next idle line back so a reaction is not followed straight away by small talk.
		nextChatAt = Math.max(nextChatAt, clock + MIN_GAP_TICKS);
		say(server, pick(lines), particle);
	}

	private void celebrate(MinecraftServer server) {
		List<Entity> guests = guests(server);
		// Everyone cheers at once, so the two-bubble cap is lifted for this one moment.
		while (!bubbles.isEmpty()) pop(bubbles.pollFirst());
		for (int i = 0; i < guests.size(); i++) {
			Entity guest = guests.get(i);
			String line = CHEERS[i % CHEERS.length];
			float pitch = 0.9f + random.nextFloat() * 0.4f;
			CrewkitSchedule.after(6 + i * 4, () -> {
				try {
					if (guest.isAlive()) spawnBubble(server, guest, line, "heart", BUBBLE_TICKS + 20, pitch);
				} catch (RuntimeException e) {
					CrewkitDispatcher.LOGGER.debug("CrewKit guest cheer failed: {}", e.toString());
				}
			});
		}
	}

	private void say(MinecraftServer server, String line, String particle) {
		List<Entity> guests = guests(server);
		if (guests.isEmpty()) return;
		int index = random.nextInt(guests.size());
		if (guests.size() > 1 && index == lastGuest) index = (index + 1) % guests.size();
		lastGuest = index;
		Entity guest = guests.get(index);
		int owner = guest.getId();
		// Never two bubbles over one head, and never more than two on screen.
		bubbles.removeIf(b -> {
			if (b.owner() != owner) return false;
			pop(b);
			return true;
		});
		while (bubbles.size() >= MAX_BUBBLES) pop(bubbles.pollFirst());
		spawnBubble(server, guest, line, particle, BUBBLE_TICKS, 1.1f + random.nextFloat() * 0.5f);
		dev.agaminggod.arenaagents.crewkit.director.DirectorFeature.guestSpoke(guest.position());
	}

	private void spawnBubble(MinecraftServer server, Entity guest, String line, String particle, int life, float pitch) {
		Vec3 head = guest.position().add(0, BUBBLE_HEIGHT, 0);
		CrewkitDisplay display = CrewkitDisplays.text(server, head, TAG, CrewkitText.of(line, 0x202124, true), 0.05f, "");
		// White speech bubble with dark text; text() fixes background:0 and shadow, so override after spawn.
		display.merge("{background:" + CrewkitText.argb(0xF0, 0xFFFFFF) + ",shadow:0b,line_width:160}");
		// Pop in from tiny (the client must see the spawn state first).
		CrewkitSchedule.after(2, () -> {
			if (display.alive()) display.transform(BUBBLE_SCALE, 0f, 0f, 0f, 4);
		});
		bubbles.addLast(new Bubble(display, guest.getId(), clock + life));
		CrewkitSounds.play(server, head, "minecraft:block.note_block.chime", 0.35f, pitch);
		particles(server, particle, head.add(0, -0.3, 0));
	}

	private static void pop(Bubble bubble) {
		if (bubble == null) return;
		CrewkitDisplay display = bubble.display();
		if (!display.alive()) return;
		display.transform(0.05f, 0f, 0f, 0f, 4);
		CrewkitSchedule.after(5, display::kill);
	}

	private static void particles(MinecraftServer server, String particle, Vec3 at) {
		if (particle == null) return;
		int count = "heart".equals(particle) ? 6 : 4;
		CrewkitDisplays.run(server, String.format(Locale.ROOT,
				"particle minecraft:%s %.3f %.3f %.3f 0.35 0.2 0.35 0.02 %d force", particle, at.x, at.y, at.z, count));
	}

	// ---------------------------------------------------------------- helpers

	/** Guests spawned by CastFeature, in a stable order. */
	private static List<Entity> guests(MinecraftServer server) {
		List<Entity> out = new ArrayList<>();
		for (Entity entity : server.overworld().getAllEntities()) {
			if (entity != null && entity.isAlive() && entity.entityTags().contains("ck_guest")) out.add(entity);
		}
		out.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
		return out;
	}

	private String pick(String[] lines) {
		return lines[random.nextInt(lines.length)];
	}

	private static String str(JsonObject data, String key) {
		if (data == null || !data.has(key) || !data.get(key).isJsonPrimitive()) return "";
		return data.get(key).getAsString();
	}

	private record Bubble(CrewkitDisplay display, int owner, long expiresAt) {}
}
