package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.SkitModeRuntime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

/** Server-side director for deterministic, world-persisted skit speech cues. */
public final class VoiceDirector {
	private static final Map<MinecraftServer, Map<AgentId, DirectorSpeechPlayback>> PLAYBACK = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, Map<AgentId, String>> STATUS = new ConcurrentHashMap<>();

	private VoiceDirector() {
	}

	public static VoiceProfile profile(MinecraftServer server, AgentId agentId) {
		VoiceProfile saved = VoiceDirectorSavedData.get(server).profile(agentId);
		if (!VoiceProfile.DEFAULT_PROFILE_ID.equals(saved.profileId())) return saved;
		return dev.agaminggod.arenaagents.server.SkitActors.records(server).stream()
				.filter(actor -> actor.agentId().equals(agentId)).findFirst()
				.map(actor -> new VoiceProfile(VoiceCatalog.defaultFor(actor.name(), actor.appearance()),
						saved.tone(), saved.speed(), saved.radius()))
				.orElse(saved);
	}

	public static VoiceProfile setProfile(MinecraftServer server, AgentId agentId, VoiceProfile profile) {
		dev.agaminggod.arenaagents.server.DirectorTakeRuntime.requireUnreserved(server, agentId);
		VoiceDirectorSavedData data = VoiceDirectorSavedData.get(server);
		data.putProfile(agentId, profile);
		return profile;
	}

	public static VoiceScript createScript(MinecraftServer server, String name, String agentSelector) {
		SkitModeRuntime.requireEnabled(server);
		VoiceScript script = new VoiceScript(name, dev.agaminggod.arenaagents.server.SkitActors.resolve(server, agentSelector).agentId().toString(), java.util.List.of());
		VoiceDirectorSavedData.get(server).createScript(script);
		return script;
	}

	public static VoiceScript addCue(MinecraftServer server, String name, VoiceCue cue) {
		SkitModeRuntime.requireEnabled(server);
		VoiceDirectorSavedData data = VoiceDirectorSavedData.get(server);
		VoiceScript current = Optional.ofNullable(data.script(name))
				.orElseThrow(() -> new AgentDomainException("VOICE_SCRIPT_NOT_FOUND", "No voice script named " + name));
		VoiceScript updated = current.append(cue);
		data.putScript(updated);
		return updated;
	}

	public static VoiceScript play(CodexAgentManager manager, String name, String selectorOverride) {
		SkitModeRuntime.requireEnabled(manager.server());
		VoiceDirectorSavedData data = VoiceDirectorSavedData.get(manager.server());
		VoiceScript script = Optional.ofNullable(data.script(name))
				.orElseThrow(() -> new AgentDomainException("VOICE_SCRIPT_NOT_FOUND", "No voice script named " + name));
		return play(manager, script, selectorOverride);
	}

	public static VoiceScript play(CodexAgentManager manager, VoiceScript script, String selectorOverride) {
		SkitModeRuntime.requireEnabled(manager.server());
		if (script.cues().isEmpty()) throw new AgentDomainException("VOICE_SCRIPT_EMPTY", "Voice script has no cues");
		String selector = selectorOverride == null || selectorOverride.isBlank() ? script.agentSelector() : selectorOverride;
		AgentId agentId = dev.agaminggod.arenaagents.server.SkitActors.resolve(manager.server(), selector).agentId();
		dev.agaminggod.arenaagents.server.DirectorTakeRuntime.requireUnreserved(manager.server(), agentId);
		if (dev.agaminggod.arenaagents.server.SkitActors.find(manager.server(), agentId).filter(net.minecraft.server.level.ServerPlayer::isAlive).isEmpty())
			throw new AgentDomainException("ACTOR_NOT_PRESENT", "Respawn the actor before playing a voice script");
		requireAvailable(manager.server());
		stop(manager.server(), agentId);
		PLAYBACK.computeIfAbsent(manager.server(), ignored -> new ConcurrentHashMap<>())
				.put(agentId, new DirectorSpeechPlayback(script.cues(), manager.server().getTickCount()));
		return script;
	}

	/** Schedules one line immediately, using the persisted profile for the agent. */
	public static void say(MinecraftServer server, AgentId agentId, String text) {
		scheduleLine(server, agentId, new VoiceCue(0, text));
	}

	/** Queues one preview with exactly the selected settings, without changing saved settings. */
	public static void say(MinecraftServer server, AgentId agentId, String text, VoiceProfile settings) {
		var actor = dev.agaminggod.arenaagents.server.SkitActors.resolve(server, agentId.toString());
		scheduleLine(server, agentId, lineCue(text, settings, actor.name(), actor.appearance()));
	}

	static VoiceCue lineCue(String text, VoiceProfile settings, String actorName, String appearance) {
		if (!VoiceCatalog.accepts(settings.profileId())) throw new AgentDomainException(
				"VOICE_PROFILE_UNKNOWN", "Unknown voice profile. Use /codex skit voice profiles");
		String id = VoiceProfile.DEFAULT_PROFILE_ID.equals(settings.profileId())
				? VoiceCatalog.defaultFor(actorName, appearance) : settings.profileId();
		return new VoiceCue(0, text, id, settings.tone(), settings.speed(), settings.radius());
	}

	private static void scheduleLine(MinecraftServer server, AgentId agentId, VoiceCue cue) {
		dev.agaminggod.arenaagents.server.DirectorTakeRuntime.requireUnreserved(server, agentId);
		SkitModeRuntime.requireEnabled(server);
		requireAvailable(server);
		if (dev.agaminggod.arenaagents.server.SkitActors.find(server, agentId).filter(net.minecraft.server.level.ServerPlayer::isAlive).isEmpty())
			throw new AgentDomainException("ACTOR_NOT_PRESENT", "Respawn the actor before speaking");
		stop(server, agentId);
		PLAYBACK.computeIfAbsent(server, ignored -> new ConcurrentHashMap<>())
				.put(agentId, new DirectorSpeechPlayback(java.util.List.of(cue), server.getTickCount()));
	}

	public static boolean isPlaying(MinecraftServer server, AgentId id) { return PLAYBACK.getOrDefault(server, Map.of()).containsKey(id); }

	public static String status(MinecraftServer server, AgentId agentId) {
		var run = Optional.ofNullable(PLAYBACK.get(server)).map(runs -> runs.get(agentId));
		return run.map(DirectorSpeechPlayback::status).orElseGet(() ->
				Optional.ofNullable(STATUS.get(server)).map(states -> states.getOrDefault(agentId, "")).orElse(""));
	}

	public static void forget(MinecraftServer server, AgentId agentId) {
		stop(server, agentId);
		Optional.ofNullable(STATUS.get(server)).ifPresent(states -> states.remove(agentId));
		VoiceDirectorSavedData.get(server).removeProfile(agentId);
	}

	public static void requireAvailable(MinecraftServer server) {
		if (!VoiceSubsystemRuntime.available(server)) throw new AgentDomainException("VOICE_UNAVAILABLE",
				"Voice is unavailable. Install the voice add-on and Simple Voice Chat, then check the voice provider");
	}

	public static void stop(MinecraftServer server, AgentId agentId) {
		Optional.ofNullable(PLAYBACK.get(server)).ifPresent(runs -> runs.remove(agentId));
		VoiceSubsystemRuntime.stopSpeaking(server, agentId);
		Optional.ofNullable(STATUS.get(server)).ifPresent(states -> states.remove(agentId));
	}

	public static void stopAll(MinecraftServer server) {
		STATUS.remove(server);
		Map<AgentId, DirectorSpeechPlayback> runs = PLAYBACK.remove(server);
		if (runs != null) runs.keySet().forEach(agentId -> VoiceSubsystemRuntime.stopSpeaking(server, agentId));
	}

	public static void tick(MinecraftServer server) {
		if (!SkitModeRuntime.enabled(server)) { stopAll(server); return; }
		Map<AgentId, DirectorSpeechPlayback> runs = PLAYBACK.get(server);
		if (runs == null) return;
		for (var entry : runs.entrySet()) {
			AgentId id = entry.getKey();
			if (dev.agaminggod.arenaagents.server.SkitActors.find(server, id).filter(net.minecraft.server.level.ServerPlayer::isAlive).isEmpty()) {
				stop(server, id);
				continue;
			}
			var run = entry.getValue();
			if (!run.tick(server.getTickCount(), cue -> speak(server, id, cue.text(), cueProfile(profile(server, id), cue)))) continue;
			if (!runs.remove(id, run)) continue;
			VoiceReceipt result = run.result();
			boolean played = result != null && result.status() == VoiceReceipt.Status.PLAYED;
			if (!played) VoiceSubsystemRuntime.stopSpeaking(server, id);
			STATUS.computeIfAbsent(server, ignored -> new ConcurrentHashMap<>()).put(id,
					played ? "Dialogue finished" : result == null ? "Speech failed" : result.message());
		}
	}

	private static VoiceProfile cueProfile(VoiceProfile profile, VoiceCue cue) {
		return new VoiceProfile(cue.profileId().isEmpty() ? profile.profileId() : cue.profileId(),
				cue.tone().isEmpty() ? profile.tone() : cue.tone(),
				cue.speed() < 0 ? profile.speed() : cue.speed(), cue.radius() == 0 ? profile.radius() : cue.radius());
	}

	public static void release(MinecraftServer server) {
		stopAll(server);
		STATUS.remove(server);
	}

	private static java.util.concurrent.CompletionStage<VoiceReceipt> speak(MinecraftServer server, AgentId agentId, String text, VoiceProfile profile) {
		SkitModeRuntime.requireEnabled(server);
		if (dev.agaminggod.arenaagents.server.SkitActors.find(server, agentId).filter(net.minecraft.server.level.ServerPlayer::isAlive).isEmpty())
			throw new AgentDomainException("ACTOR_NOT_PRESENT", "Respawn the actor before speaking");
		long sequence = VoiceSubsystemRuntime.nextConversationSequence(server, agentId);
		return VoiceSubsystemRuntime.speak(server, new VoiceRequest(
				agentId, text, profile.profileId(), profile.radius(), sequence, profile.speed(), profile.tone()));
	}

}
