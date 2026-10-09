package dev.agaminggod.arenaagents.crewkit.items;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Display-entity helpers. Display setters are private in vanilla, so spawning and data changes go through
 * summon / data merge (SNBT); positions move through Entity#setPos so teleport_duration interpolates them.
 */
final class Fx {
	private Fx() {}

	static final String TAG_ALL = "crewkit";
	static final String TAG = "ck_items";
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	static void cmd(MinecraftServer server, String command) {
		CommandSourceStack source = server.createCommandSourceStack()
				.withSuppressedOutput()
				.withPermission(LevelBasedPermissionSet.OWNER);
		server.getCommands().performPrefixedCommand(source, command);
	}

	/** Summons an entity with a fixed UUID and the crewkit tags; body is the inside of the SNBT compound. */
	static UUID summon(MinecraftServer server, String type, Vec3 p, String body) {
		UUID id = UUID.randomUUID();
		long m = id.getMostSignificantBits();
		long l = id.getLeastSignificantBits();
		String uuidTag = "[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l + "]";
		cmd(server, "summon " + type + " " + num(p.x) + " " + num(p.y) + " " + num(p.z)
				+ " {UUID:" + uuidTag + ",Tags:[\"" + TAG_ALL + "\",\"" + TAG + "\"]" + (body.isEmpty() ? "" : "," + body) + "}");
		return id;
	}

	static void merge(MinecraftServer server, UUID id, String body) {
		if (id == null) return;
		cmd(server, "data merge entity " + id + " {" + body + "}");
	}

	static Entity get(ServerLevel level, UUID id) {
		return id == null ? null : level.getEntity(id);
	}

	static void move(ServerLevel level, UUID id, Vec3 p) {
		Entity e = get(level, id);
		if (e != null && e.position().distanceToSqr(p) > 1.0e-6) e.setPos(p.x, p.y, p.z);
	}

	static void discard(ServerLevel level, UUID id) {
		Entity e = get(level, id);
		if (e != null) e.discard();
	}

	static String num(double v) {
		return String.format(Locale.ROOT, "%.4f", v);
	}

	static String f(double v) {
		return String.format(Locale.ROOT, "%.4ff", v);
	}

	/** Transformation compound with a rotation (applied as left_rotation) and non-uniform scale. */
	static String tf(double tx, double ty, double tz, Quaternionf rot, double sx, double sy, double sz) {
		return "transformation:{left_rotation:[" + f(rot.x) + "," + f(rot.y) + "," + f(rot.z) + "," + f(rot.w) + "]"
				+ ",right_rotation:[0f,0f,0f,1f]"
				+ ",translation:[" + f(tx) + "," + f(ty) + "," + f(tz) + "]"
				+ ",scale:[" + f(sx) + "," + f(sy) + "," + f(sz) + "]}";
	}

	static String tf(double scale) {
		return tf(0, 0, 0, new Quaternionf(), scale, scale, scale);
	}

	/** Starts an interpolated transition next client tick. */
	static String interp(int ticks) {
		return "start_interpolation:0,interpolation_duration:" + ticks;
	}

	static String quote(String s) {
		return GSON.toJson(s == null ? "" : s);
	}

	static JsonObject text(String s, String color, boolean bold) {
		JsonObject o = new JsonObject();
		o.addProperty("text", s);
		if (color != null) o.addProperty("color", color);
		if (bold) o.addProperty("bold", true);
		o.addProperty("italic", false);
		return o;
	}

	/** Text component as SNBT (SNBT accepts JSON-style quoted keys, booleans and \n / \\u escapes). */
	static String component(JsonObject... parts) {
		if (parts.length == 1) return GSON.toJson(parts[0]);
		JsonArray a = new JsonArray();
		JsonObject root = text("", null, false);
		for (JsonObject p : parts) a.add(p);
		root.add("extra", a);
		return GSON.toJson(root);
	}

	static void sound(ServerLevel level, Vec3 p, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, p.x, p.y, p.z, sound, SoundSource.MASTER, volume, pitch);
	}

	static double easeInOut(double t) {
		t = Math.max(0, Math.min(1, t));
		return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
	}

	static String truncate(String s, int max) {
		if (s == null) return "";
		s = s.strip();
		return s.length() <= max ? s : s.substring(0, max - 1).stripTrailing() + "…";
	}
}
