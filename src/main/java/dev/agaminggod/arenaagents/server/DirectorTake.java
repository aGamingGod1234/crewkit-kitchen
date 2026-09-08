package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.*;

/** A reusable cast assignment. Starting marks are captured when tracks are saved. */
public record DirectorTake(String name, List<Track> tracks, String cameraPath, int cameraTicks) {
	public record Track(AgentId actor, String motion, String voice, SkitPlacement start) {
		public Track { Objects.requireNonNull(actor); Objects.requireNonNull(start); motion = reference(motion); voice = reference(voice); if (motion.isEmpty() && voice.isEmpty()) throw new IllegalArgumentException("Assign an action or voice script"); }
	}
	public DirectorTake {
		name = reference(name); cameraPath = reference(cameraPath); tracks = List.copyOf(tracks);
		if (name.isEmpty() || tracks.size() > 32 || tracks.stream().map(Track::actor).distinct().count() != tracks.size()) throw new IllegalArgumentException("Invalid take name or duplicate cast track");
		if (cameraTicks < 0 || cameraTicks > 72000 || cameraPath.isEmpty() != (cameraTicks == 0)) throw new IllegalArgumentException("Select a camera path with a positive duration");
	}
	private static String reference(String value) { value = Objects.requireNonNull(value).strip(); if (value.length() > 64 || value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException("Names must fit on one line, up to 64 characters"); return value; }
	public DirectorTake assign(Track track) {
		List<Track> next = new ArrayList<>(tracks); next.removeIf(old -> old.actor().equals(track.actor())); next.add(track); return new DirectorTake(name, next, cameraPath, cameraTicks);
	}
	public DirectorTake remove(int index) { List<Track> next = new ArrayList<>(tracks); next.remove(index); return new DirectorTake(name, next, cameraPath, cameraTicks); }
	public DirectorTake camera(String path, int ticks) { return new DirectorTake(name, tracks, path, ticks); }
	private static final Codec<Track> TRACK_CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.xmap(AgentId::parse, Object::toString).fieldOf("actor").forGetter(Track::actor),
		Codec.STRING.fieldOf("motion").forGetter(Track::motion), Codec.STRING.fieldOf("voice").forGetter(Track::voice),
		SkitPlacement.CODEC.fieldOf("start").forGetter(Track::start)).apply(i, Track::new));
	public static final Codec<DirectorTake> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("name").forGetter(DirectorTake::name), TRACK_CODEC.listOf().fieldOf("tracks").forGetter(DirectorTake::tracks),
		Codec.STRING.optionalFieldOf("camera", "").forGetter(DirectorTake::cameraPath), Codec.INT.optionalFieldOf("camera_ticks", 0).forGetter(DirectorTake::cameraTicks)).apply(i, DirectorTake::new));
}
