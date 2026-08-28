package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletionStage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VoiceSubsystemRuntime {
	public static final String ENTRYPOINT = "arenaagents_voice";
	private static final Logger LOGGER = LoggerFactory.getLogger(VoiceSubsystemRuntime.class);
	private static final Map<MinecraftServer, Holder> INSTANCES = new WeakHashMap<>();

	private VoiceSubsystemRuntime() {
	}

	public static synchronized void start(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		if (INSTANCES.containsKey(server)) return;
		VoiceSubsystem selected = NoVoiceSubsystem.INSTANCE;
		for (VoiceSubsystemProvider provider : FabricLoader.getInstance().getEntrypoints(
				ENTRYPOINT, VoiceSubsystemProvider.class
		)) {
			try {
				VoiceSubsystem candidate = Objects.requireNonNull(provider.create(server), "voice provider returned null");
				selected = candidate;
				break;
			} catch (RuntimeException exception) {
				LOGGER.warn("Voice addon failed to initialize; proximity speech will use text fallback", exception);
			}
		}
		INSTANCES.put(server, new Holder(selected, new VoiceRegistrationTracker(), new LinkedHashMap<>()));
	}

	public static synchronized void tick(MinecraftServer server) {
		Holder holder = INSTANCES.get(server);
		if (holder == null) return;
		CodexAgentManager manager = CodexAgentManager.get(server);
		Map<AgentId, java.util.UUID> current = new LinkedHashMap<>();
		manager.records().forEach(record -> manager.findAgentPlayer(record.agentId())
				.filter(player -> player.isAlive())
				.ifPresent(player -> current.put(record.agentId(), player.getUUID())));
		try {
			holder.tracker().reconcile(current, holder.subsystem());
		} catch (RuntimeException exception) {
			LOGGER.warn("Voice agent registration failed; gameplay remains available", exception);
		}
	}

	public static synchronized boolean available(MinecraftServer server) {
		Holder holder = INSTANCES.get(server);
		return holder != null && holder.subsystem().available();
	}

	public static synchronized CompletionStage<VoiceReceipt> speak(MinecraftServer server, VoiceRequest request) {
		Holder holder = INSTANCES.get(server);
		return (holder == null ? NoVoiceSubsystem.INSTANCE : holder.subsystem()).speak(request);
	}

	public static synchronized long nextConversationSequence(MinecraftServer server, AgentId agentId) {
		Holder holder = INSTANCES.get(server);
		if (holder == null) return 1L;
		return holder.sequences().merge(agentId, 1L, Long::sum);
	}

	public static synchronized void stopSpeaking(MinecraftServer server, AgentId agentId) {
		Holder holder = INSTANCES.get(server);
		if (holder != null) holder.subsystem().stop(agentId);
	}

	public static synchronized void close(MinecraftServer server) {
		Holder holder = INSTANCES.remove(server);
		if (holder == null) return;
		try {
			holder.tracker().clear(holder.subsystem());
		} finally {
			holder.subsystem().close();
		}
	}

	private record Holder(
			VoiceSubsystem subsystem,
			VoiceRegistrationTracker tracker,
			Map<AgentId, Long> sequences
	) {
	}
}
