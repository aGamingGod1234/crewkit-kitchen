package dev.agaminggod.arenaagents.crewkit.core;

import java.util.Locale;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Handle to one display entity (text_display, item_display, block_display) in the overworld.
 *
 * Everything goes through vanilla commands with SNBT, so it stays independent of mappings and of the
 * private Display setters. Every display is tagged {@code crewkit} plus its feature tag, so
 * {@link CrewkitDisplays#killTag} clears exactly one feature.
 *
 * Motion rules (nothing snaps): change transforms with {@link #transform} (interpolated), move with
 * {@link #moveTo} (teleport_duration). The client needs to see the spawn state before it can animate
 * from it, so spawn, then {@code CrewkitSchedule.after(2, () -> d.transform(...))}.
 */
public final class CrewkitDisplay {
	private final MinecraftServer server;
	private final UUID uuid;
	private final String type;
	private Vec3 pos;

	CrewkitDisplay(MinecraftServer server, UUID uuid, String type, Vec3 pos) {
		this.server = server;
		this.uuid = uuid;
		this.type = type;
		this.pos = pos;
	}

	public UUID uuid() {
		return uuid;
	}

	public String type() {
		return type;
	}

	public Vec3 pos() {
		return pos;
	}

	public boolean alive() {
		Entity entity = server.overworld().getEntity(uuid);
		return entity != null && entity.isAlive();
	}

	/** {@code data merge entity <uuid> <snbt>}; snbt is a compound such as {@code {background:0}}. */
	public CrewkitDisplay merge(String snbt) {
		CrewkitDisplays.run(server, "data merge entity " + uuid + " " + snbt);
		return this;
	}

	/** Replace the text of a text_display. Build the component with {@link CrewkitText}. */
	public CrewkitDisplay text(String componentSnbt) {
		return merge("{text:" + componentSnbt + "}");
	}

	/** Interpolated transform: uniform scale plus translation, over {@code ticks}. */
	public CrewkitDisplay transform(float scale, float tx, float ty, float tz, int ticks) {
		return transform(scale, scale, scale, tx, ty, tz, 0f, ticks);
	}

	/**
	 * Interpolated transform with per-axis scale and a roll around the facing axis (z, radians).
	 * The client eases from the last state it saw to this one over {@code ticks}.
	 */
	public CrewkitDisplay transform(float sx, float sy, float sz, float tx, float ty, float tz, float rollRadians, int ticks) {
		return merge("{start_interpolation:0,interpolation_duration:" + Math.max(0, ticks)
				+ ",transformation:" + CrewkitDisplays.transformation(sx, sy, sz, tx, ty, tz, rollRadians) + "}");
	}

	/** Smooth move: sets teleport_duration, then teleports; the client lerps over {@code ticks} (max 59). */
	public CrewkitDisplay moveTo(Vec3 target, int ticks) {
		this.pos = target;
		merge("{teleport_duration:" + Math.max(0, Math.min(59, ticks)) + "}");
		CrewkitDisplays.run(server, String.format(Locale.ROOT, "tp %s %.4f %.4f %.4f", uuid, target.x, target.y, target.z));
		return this;
	}

	public void kill() {
		CrewkitDisplays.run(server, "kill " + uuid);
	}
}
