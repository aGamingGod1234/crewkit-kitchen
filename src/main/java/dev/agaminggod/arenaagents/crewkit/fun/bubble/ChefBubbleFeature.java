package dev.agaminggod.arenaagents.crewkit.fun.bubble;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.crewkit.CrewkitFeature;
import dev.agaminggod.arenaagents.crewkit.cast.CastFeature;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDispatcher;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplay;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitDisplays;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSchedule;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitSounds;
import dev.agaminggod.arenaagents.crewkit.core.CrewkitText;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Comic speech bubble over the chef's head: types out each activity.text, shows "..." while the
 * Reap call is pending, then flips to a result stamp. Two entities only: the white bubble
 * (text_display background) and a small tail under it.
 */
public final class ChefBubbleFeature implements CrewkitFeature {
	private static final String FEATURE_TAG = "ck_bubble";
	private static final float SCALE = 1.1f;
	private static final double HEAD_Y = 2.25;
	private static final double TAIL_DY = -0.24;
	private static final int CHARS_PER_TICK = 2;
	private static final int LINGER_TICKS = 50;
	private static final int PENDING_MAX_TICKS = 240;
	private static final int FADE_TICKS = 5;

	private static final int INK = 0x23232B;
	private static final int THINK = 0x5A5F6B;
	private static final int OK = 0x18A04A;
	private static final int BAD = 0xE0302A;
	private static final int ORANGE = 0xF07800;
	private static final int GREY = 0x8A8F99;
	private static final int BG = CrewkitText.argb(0xF2, 0xFFFFFF);

	private CrewkitDisplay bubble;
	private CrewkitDisplay tail;
	private String fullText = "";
	private int shown;
	private boolean italic;
	private int inkColor = INK;
	/** null while pending; otherwise the result (may be "" for lines without a result phase). */
	private String result;
	private String suffix = "";
	private int suffixColor = INK;
	private int bornTick;
	private int resolvedTick;
	private int fadeStartTick = -1;
	private int lastWobble;
	private boolean wobbleLeft;
	private Vec3 lastAnchor;
	private UUID chefNpc;
	private int lastNpcScan = -1000;
	private String lastRendered = "";

	@Override
	public void onEvent(MinecraftServer server, String event, JsonObject data, long seq) {
		try {
			switch (event) {
				case "activity" -> onActivity(server, data);
				case "gate_blocked" -> say(server, "Uh oh. Over budget.", false, BAD, "over", "S$ !!", ORANGE);
				case "gate_passed" -> say(server, "Within budget!", false, OK, "ok", "✔", OK);
				case "completed" -> say(server, "Order up!", false, INK, "ok", "✔", OK);
				default -> {}
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit bubble: event {} failed", event, e);
		}
	}

	/** Says a line in the chef's bubble from outside the feature (skits). colorHint is the ink colour, 0xRRGGBB. */
	public static void say(MinecraftServer server, String text, int colorHint) {
		try {
			for (CrewkitFeature f : dev.agaminggod.arenaagents.crewkit.CrewkitFeatures.all()) {
				if (f instanceof ChefBubbleFeature bubble) {
					bubble.say(server, text, false, colorHint, "", "", INK);
					return;
				}
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit bubble: say failed", e);
		}
	}

	private void onActivity(MinecraftServer server, JsonObject data) {
		if (data == null) return;
		String kind = str(data, "kind", "");
		String text = str(data, "text", "").trim();
		String res = str(data, "result", "pending");
		if (text.length() > 44) text = text.substring(0, 43) + "…";
		boolean think = "think".equals(kind);

		boolean pendingOnScreen = bubble != null && fadeStartTick < 0 && result == null;
		if (!"pending".equals(res) && !think && (pendingOnScreen || text.isEmpty())) {
			// Result for the bubble already on screen: flip the stamp in place.
			if (bubble == null) return;
			if (!text.isEmpty() && !text.equals(fullText)) {
				fullText = text;
				shown = Math.min(shown, text.length());
			}
			resolve(server, res, data);
			return;
		}
		if (text.isEmpty()) return;
		if (think) {
			say(server, text, true, THINK, "", "", INK);
		} else {
			say(server, text, false, INK, null, "", INK);
			if (!"pending".equals(res)) resolve(server, res, data);
		}
	}

	private void resolve(MinecraftServer server, String res, JsonObject data) {
		String money = moneyOf(data);
		switch (res) {
			case "ok" -> { suffix = money.isEmpty() ? "✔" : "✔ " + money; suffixColor = OK; }
			case "sold_out" -> { suffix = "✖ SOLD OUT"; suffixColor = BAD; }
			case "over" -> { suffix = money.isEmpty() ? "S$ !!" : money + " !!"; suffixColor = ORANGE; }
			case "busy" -> { suffix = "zzz"; suffixColor = GREY; }
			case "error" -> { suffix = "?!"; suffixColor = BAD; }
			default -> { suffix = ""; suffixColor = INK; }
		}
		result = res;
		int now = server.getTickCount();
		resolvedTick = now;
		if (bubble != null) {
			// Squash-and-stretch so the result lands as a beat.
			bubble.transform(SCALE * 1.18f, SCALE * 0.88f, SCALE, 0, 0, 0, 0, 2);
			final CrewkitDisplay b = bubble;
			CrewkitSchedule.after(3, () -> {
				if (b == bubble && fadeStartTick < 0) b.transform(SCALE, SCALE, SCALE, 0, 0, 0, 0, 4);
			});
			float pitch = switch (res) {
				case "ok" -> 1.9f;
				case "sold_out", "error" -> 0.6f;
				case "over" -> 1.0f;
				default -> 1.3f;
			};
			CrewkitSounds.play(server, bubble.pos(), "minecraft:block.note_block.pling", 0.35f, pitch);
		}
		render(now);
	}

	/** Show a new line. res null = pending (dots); otherwise a pre-set stamp shown once typed. */
	private void say(MinecraftServer server, String text, boolean italics, int ink, String res, String stamp, int stampColor) {
		int now = server.getTickCount();
		fullText = text;
		shown = 0;
		italic = italics;
		inkColor = ink;
		result = res;
		suffix = stamp;
		suffixColor = stampColor;
		bornTick = now;
		resolvedTick = now;
		fadeStartTick = -1;
		lastRendered = "";
		Vec3 anchor = anchor(server);
		lastAnchor = anchor;
		if (bubble == null || !bubble.alive()) {
			killAll(server);
			bubble = CrewkitDisplays.text(server, anchor, FEATURE_TAG, CrewkitText.of(" ", ink, false), 0.01f,
					"background:" + BG + ",shadow:0b,line_width:170,teleport_duration:3");
			tail = CrewkitDisplays.text(server, anchor.add(0, TAIL_DY, 0), FEATURE_TAG, CrewkitText.of("▼", 0xFFFFFF, false), 0.01f,
					"shadow:0b,teleport_duration:3");
		} else {
			bubble.transform(SCALE * 0.6f, 0, 0, 0, 2);
			if (tail != null) tail.transform(SCALE * 0.6f, 0, 0, 0, 2);
		}
		final CrewkitDisplay b = bubble;
		final CrewkitDisplay t = tail;
		CrewkitSchedule.after(2, () -> {
			if (b != bubble || fadeStartTick >= 0) return;
			b.transform(SCALE * 1.3f, 0, 0, 0, 4);
			if (t != null) t.transform(SCALE * 1.3f, 0, 0, 0, 4);
		});
		CrewkitSchedule.after(6, () -> {
			if (b != bubble || fadeStartTick >= 0) return;
			b.transform(SCALE, 0, 0, 0, 5);
			if (t != null) t.transform(SCALE, 0, 0, 0, 5);
		});
		lastWobble = now + 8;
		CrewkitSounds.play(server, anchor, "minecraft:entity.chicken.egg", 0.45f, italics ? 1.3f : 1.8f);
	}

	@Override
	public void tick(MinecraftServer server) {
		if (bubble == null) return;
		try {
			int now = server.getTickCount();
			if (fadeStartTick >= 0) {
				if (now - fadeStartTick >= FADE_TICKS + 1) killAll(server);
				return;
			}
			if (shown < fullText.length()) {
				shown = Math.min(fullText.length(), shown + CHARS_PER_TICK);
				if (now % 3 == 0) CrewkitSounds.play(server, bubble.pos(), "minecraft:ui.button.click", 0.08f, 2.0f);
			}
			render(now);
			follow(server, now);
			wobble(now);

			boolean typed = shown >= fullText.length();
			boolean pending = result == null;
			int typedAt = bornTick + (fullText.length() + CHARS_PER_TICK - 1) / CHARS_PER_TICK;
			int sinceSettled = now - Math.max(resolvedTick, typedAt);
			if ((!pending && typed && sinceSettled > LINGER_TICKS) || (pending && now - bornTick > PENDING_MAX_TICKS)) {
				fadeStartTick = now;
				bubble.transform(SCALE * 1.15f, SCALE * 0.05f, SCALE, 0, 0, 0, 0, FADE_TICKS);
				if (tail != null) tail.transform(0.01f, 0, 0, 0, FADE_TICKS);
			}
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit bubble: tick failed", e);
			try { killAll(server); } catch (RuntimeException ignored) {}
		}
	}

	private void render(int now) {
		if (bubble == null) return;
		boolean typing = shown < fullText.length();
		String body = fullText.substring(0, Math.min(shown, fullText.length()));
		String main = "{text:\"" + CrewkitText.escape(" " + body + (typing ? "▌" : "") + " ")
				+ "\",color:\"" + CrewkitText.hex(inkColor) + "\"" + (italic ? ",italic:1b" : "") + "}";
		String stamp = "";
		if (!typing) {
			if (result == null) {
				int dots = 1 + (now / 5) % 3;
				stamp = "," + CrewkitText.of(".".repeat(dots) + " ".repeat(4 - dots), GREY, true);
			} else if (!suffix.isEmpty()) {
				stamp = "," + CrewkitText.of(suffix + " ", suffixColor, true);
			}
		}
		String component = "[\"\"," + main + stamp + "]";
		if (!component.equals(lastRendered)) {
			lastRendered = component;
			bubble.text(component);
		}
	}

	private void follow(MinecraftServer server, int now) {
		if (now % 2 != 0) return;
		Vec3 anchor = anchor(server);
		if (lastAnchor == null || anchor.distanceToSqr(lastAnchor) > 0.0025) {
			lastAnchor = anchor;
			bubble.moveTo(anchor, 4);
			if (tail != null) tail.moveTo(anchor.add(0, TAIL_DY, 0), 4);
		}
	}

	private void wobble(int now) {
		if (now - lastWobble < 12) return;
		lastWobble = now;
		wobbleLeft = !wobbleLeft;
		float roll = wobbleLeft ? 0.045f : -0.045f;
		bubble.transform(SCALE, SCALE, SCALE, 0, 0, 0, roll, 12);
	}

	private Vec3 anchor(MinecraftServer server) {
		Entity chef = findChef(server);
		Vec3 base = chef != null ? chef.position() : CrewkitAnchors.at(CrewkitAnchors.AGENT);
		return base.add(0.15, HEAD_Y, 0.25);
	}

	private Entity findChef(MinecraftServer server) {
		String name = CastFeature.chefName(server);
		if (name != null) {
			ServerPlayer player = server.getPlayerList().getPlayerByName(name);
			if (player != null) return player;
		}
		if (chefNpc != null) {
			Entity npc = server.overworld().getEntity(chefNpc);
			if (npc != null && npc.isAlive()) return npc;
			chefNpc = null;
		}
		int now = server.getTickCount();
		if (now - lastNpcScan < 40) return null;
		lastNpcScan = now;
		for (Entity entity : server.overworld().getAllEntities()) {
			if (entity.entityTags().contains("ck_chef") && entity.isAlive()) {
				chefNpc = entity.getUUID();
				return entity;
			}
		}
		return null;
	}

	@Override
	public void reset(MinecraftServer server) {
		try {
			killAll(server);
			CrewkitDisplays.killTag(server, FEATURE_TAG);
		} catch (RuntimeException e) {
			CrewkitDispatcher.LOGGER.warn("CrewKit bubble: reset failed", e);
		}
		chefNpc = null;
		result = "";
	}

	private void killAll(MinecraftServer server) {
		if (bubble != null) bubble.kill();
		if (tail != null) tail.kill();
		bubble = null;
		tail = null;
		fadeStartTick = -1;
		lastRendered = "";
	}

	private static String moneyOf(JsonObject data) {
		JsonElement e = data == null ? null : data.get("amount");
		if (e == null || !e.isJsonObject()) return "";
		JsonObject a = e.getAsJsonObject();
		try {
			return CrewkitText.money(a.get("amount").getAsDouble(), str(a, "currency", "SGD"));
		} catch (RuntimeException ex) {
			return "";
		}
	}

	private static String str(JsonObject o, String key, String fallback) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
	}
}
