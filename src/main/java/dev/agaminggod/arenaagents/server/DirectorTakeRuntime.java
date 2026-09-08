package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.control.DirectorTakePlaybackPayload;
import dev.agaminggod.arenaagents.server.voice.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** One take per operator, with disjoint cast reservations and a shared start tick. */
public final class DirectorTakeRuntime {
	record Track(DirectorTake.Track assignment, SkitScript motion, VoiceScript voice) { }
	static final class Run {
		final UUID owner;
		final DirectorTake take;
		final List<Track> tracks;
		final long start;
		final String dimension;
		boolean started, starting;
		Run(UUID owner, DirectorTake take, List<Track> tracks, long start, String dimension) {
			this.owner = owner; this.take = take; this.tracks = List.copyOf(tracks); this.start = start; this.dimension = dimension;
		}
	}
	private static final Map<MinecraftServer, Map<UUID, Run>> RUNS = new HashMap<>();
	private DirectorTakeRuntime() { }
	public static void play(ServerPlayer owner, String name) {
		MinecraftServer server = owner.level().getServer(); SkitModeRuntime.requireEnabled(server);
		DirectorTake take = SkitModeSavedData.get(server).take(name);
		if (take == null || take.tracks().isEmpty()) throw new IllegalArgumentException("Save at least one actor track in this take first");
		if (!ServerPlayNetworking.canSend(owner, DirectorTakePlaybackPayload.TYPE)) throw new IllegalArgumentException("Update the Director client to play a take");
		if (RUNS.getOrDefault(server, Map.of()).containsKey(owner.getUUID())) throw new IllegalArgumentException("Stop your current take before replaying");
		List<Track> tracks = new ArrayList<>();
		for (var assignment : take.tracks()) {
			requireUnreserved(server, assignment.actor());
			ServerPlayer actor = SkitActors.find(server, assignment.actor()).filter(ServerPlayer::isAlive).orElseThrow(() -> new IllegalArgumentException("Respawn every actor before playing this take"));
			if (SkitModeRuntime.isPlaying(server, assignment.actor()) || VoiceDirector.isPlaying(server, assignment.actor())) throw new IllegalArgumentException("Stop individual actor playback before starting a take");
			if (SkitModeRuntime.findLevel(server, assignment.start().dimension()).isEmpty()) throw new IllegalArgumentException("An actor's saved starting dimension is unavailable");
			SkitScript motion = assignment.motion().isEmpty() ? null : SkitModeSavedData.get(server).script(assignment.motion());
			VoiceScript voice = assignment.voice().isEmpty() ? null : VoiceDirectorSavedData.get(server).script(assignment.voice());
			if (!assignment.motion().isEmpty() && (motion == null || motion.steps().isEmpty())) throw new IllegalArgumentException("Missing or empty action script: " + assignment.motion());
			if (!assignment.voice().isEmpty() && (voice == null || voice.cues().isEmpty())) throw new IllegalArgumentException("Missing or empty voice script: " + assignment.voice());
			if (motion != null) SkitModeRuntime.validateTimeline(motion, assignment.start().dimension(), dim -> SkitModeRuntime.findLevel(server, dim).isPresent(), item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.Identifier.parse(item)));
			if (voice != null) VoiceDirector.requireAvailable(server);
			tracks.add(new Track(assignment, motion, voice));
		}
		Run run = new Run(owner.getUUID(), take, tracks, server.getTickCount() + 60L, owner.level().dimension().identifier().toString());
		RUNS.computeIfAbsent(server, ignored -> new HashMap<>()).put(owner.getUUID(), run);
		ServerPlayNetworking.send(owner, new DirectorTakePlaybackPayload(name, take.cameraPath(), owner.level().getGameTime() + 60L, "Starting in 3 seconds"));
	}
	public static void requireUnreserved(MinecraftServer server, AgentId actor) {
		for (Run run : RUNS.getOrDefault(server, Map.of()).values())
			if (!run.starting && run.tracks.stream().anyMatch(track -> track.assignment.actor().equals(actor))) throw new dev.agaminggod.arenaagents.agent.AgentDomainException("ACTOR_IN_TAKE", "This actor belongs to an active take. Stop the take first");
	}
	public static void stopActor(MinecraftServer server, AgentId actor) {
		for (Run run : List.copyOf(RUNS.getOrDefault(server, Map.of()).values()))
			if (run.tracks.stream().anyMatch(track -> track.assignment.actor().equals(actor))) stop(server, run.owner, "Take stopped");
	}
	public static void stop(MinecraftServer server, UUID owner, String message) {
		Run run = Optional.ofNullable(RUNS.get(server)).map(runs -> runs.remove(owner)).orElse(null);
		if (run == null) return;
		for (Track track : run.tracks) { SkitModeRuntime.stop(server, track.assignment.actor()); VoiceDirector.stop(server, track.assignment.actor()); }
		ServerPlayer player = server.getPlayerList().getPlayer(owner);
		if (player != null && ServerPlayNetworking.canSend(player, DirectorTakePlaybackPayload.TYPE))
			ServerPlayNetworking.send(player, new DirectorTakePlaybackPayload(run.take.name(), run.take.cameraPath(), -1, message));
	}
	public static void stopAll(MinecraftServer server) {
		for (UUID owner : List.copyOf(RUNS.getOrDefault(server, Map.of()).keySet())) stop(server, owner, "Take stopped");
		RUNS.remove(server);
	}
	public static void tick(MinecraftServer server) {
		if (!SkitModeRuntime.enabled(server)) { stopAll(server); return; }
		for (Run run : List.copyOf(RUNS.getOrDefault(server, Map.of()).values())) {
			try {
				ServerPlayer owner = server.getPlayerList().getPlayer(run.owner);
				if (owner == null || !owner.isAlive() || !owner.level().dimension().identifier().toString().equals(run.dimension)) { stop(server, run.owner, "Take stopped: director left the scene"); continue; }
				if (run.tracks.stream().anyMatch(track -> SkitActors.find(server, track.assignment.actor()).filter(ServerPlayer::isAlive).isEmpty())) { stop(server, run.owner, "Take stopped: an actor needs to respawn"); continue; }
				long tick = server.getTickCount();
				if (tick < run.start) continue;
				if (!run.started) {
					run.starting = true;
					try {
						for (Track track : run.tracks) {
							var a = track.assignment; var p = a.start(); var manager = CodexAgentManager.get(server);
							SkitModeRuntime.place(manager, a.actor().toString(), SkitModeRuntime.findLevel(server,p.dimension()).orElseThrow(),p.x(),p.y(),p.z(),p.yaw(),p.pitch());
							if (track.motion != null) SkitModeRuntime.play(manager, track.motion, a.actor().toString());
							if (track.voice != null) VoiceDirector.play(manager, track.voice, a.actor().toString());
						}
						run.started = true;
					} finally { run.starting = false; }
					continue;
				}
				boolean active = tick < run.start + run.take.cameraTicks();
				for (Track track : run.tracks) {
					AgentId id = track.assignment.actor();
					active |= SkitModeRuntime.isPlaying(server,id) || VoiceDirector.isPlaying(server,id);
					if (track.motion != null && !SkitModeRuntime.isPlaying(server,id) && !SkitModeRuntime.status(server,id).equals("Actions finished")) throw new IllegalArgumentException("Actions did not finish: " + SkitModeRuntime.status(server,id));
					if (track.voice != null && !VoiceDirector.isPlaying(server,id) && !VoiceDirector.status(server,id).equals("Dialogue finished")) throw new IllegalArgumentException("Speech did not finish: " + VoiceDirector.status(server,id));
				}
				if (!active) stop(server,run.owner,"Take finished");
			} catch (RuntimeException error) {
				org.slf4j.LoggerFactory.getLogger(DirectorTakeRuntime.class).warn("Stopped take {}",run.take.name(),error);
				stop(server,run.owner,"Take stopped: " + (error.getMessage()==null?"playback failed":error.getMessage()));
			}
		}
	}
}
