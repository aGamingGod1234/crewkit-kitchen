package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.CompletionStage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VoiceSubsystemRuntime {
	public static final String ENTRYPOINT = "arenaagents_voice";
	private static final Logger LOGGER = LoggerFactory.getLogger(VoiceSubsystemRuntime.class);
	private static final int MAX_DIAGNOSTIC_AGENTS = 64;
	private static final Map<MinecraftServer, Holder> INSTANCES = new WeakHashMap<>();
	private static final Map<MinecraftServer, LinkedHashMap<AgentId, String>> AVAILABILITY_DIAGNOSTICS =
			new WeakHashMap<>();
	private static final Map<MinecraftServer, String> RUNTIME_DIAGNOSTICS = new WeakHashMap<>();

	private VoiceSubsystemRuntime() {
	}

	public static synchronized boolean start(MinecraftServer server) {
		return start(server, legacyConfiguration());
	}

	private static VoiceSubsystemConfiguration legacyConfiguration() {
		String endpoint = System.getProperty("arenaagents.voiceUrl", "http://127.0.0.1:8766/v1/tts");
		String secretPath = System.getProperty(
				"arenaagents.voiceSecretFile",
				System.getProperty("arenaagents.bridgeSecretFile", "runtime/bridge-secret.txt")
		);
		try {
			String secret = Files.readString(Path.of(secretPath), StandardCharsets.UTF_8).trim();
			return new VoiceSubsystemConfiguration(endpoint, secret);
		} catch (IOException exception) {
			throw new IllegalStateException("Voice worker secret is unavailable", exception);
		}
	}

	public static synchronized boolean start(MinecraftServer server, VoiceSubsystemConfiguration configuration) {
		return start(server, configuration, FabricLoader.getInstance().getEntrypoints(
				ENTRYPOINT, VoiceSubsystemProvider.class
		));
	}

	static synchronized boolean start(
			MinecraftServer server,
			VoiceSubsystemConfiguration configuration,
			Iterable<VoiceSubsystemProvider> providers
	) {
		Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(configuration, "voice configuration must not be null");
		Objects.requireNonNull(providers, "voice providers must not be null");
		Holder existing = INSTANCES.get(server);
		if (existing != null && existing.configuration().equals(configuration)
				&& safelyAvailable(server, existing.subsystem())) return true;
		if (existing != null) {
			INSTANCES.remove(server);
			close(server, existing);
		}
		boolean foundProvider = false;
		for (VoiceSubsystemProvider provider : providers) {
			foundProvider = true;
			try {
				VoiceSubsystem candidate = Objects.requireNonNull(
						provider.create(server, configuration), "voice provider returned null"
				);
				INSTANCES.put(server, new Holder(
						configuration, candidate, new VoiceRegistrationTracker(), new LinkedHashMap<>()
				));
				reportRuntimeRecovery(server);
				return true;
			} catch (RuntimeException exception) {
				reportRuntimeFailure(server, "VOICE_PROVIDER_START_FAILED", exception);
			}
		}
		if (foundProvider) return false;
		INSTANCES.put(server, new Holder(
				configuration, NoVoiceSubsystem.INSTANCE, new VoiceRegistrationTracker(), new LinkedHashMap<>()
		));
		return true;
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
		return holder != null && safelyAvailable(server, holder.subsystem());
	}

	public static synchronized void reportAvailabilityFallback(MinecraftServer server, AgentId agentId) {
		Holder holder = INSTANCES.get(server);
		String code = holder == null || holder.subsystem() == NoVoiceSubsystem.INSTANCE
				? "VOICE_ADDON_UNAVAILABLE" : "VOICE_TRANSPORT_UNAVAILABLE";
		String reason = holder == null || holder.subsystem() == NoVoiceSubsystem.INSTANCE
				? "voice addon is not installed or initialized" : "Simple Voice Chat server API is unavailable";
		if (!availabilityFallbackTransition(server, agentId, code)) return;
		LOGGER.warn(
				"Proximity voice fallback [{}] at availability boundary for agent {}: {}",
				code, agentId, reason
		);
	}

	static synchronized boolean availabilityFallbackTransition(
			MinecraftServer server,
			AgentId agentId,
			String code
	) {
		Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(code, "voice diagnostic code must not be null");
		if (code.isBlank() || code.length() > 64) {
			throw new IllegalArgumentException("voice diagnostic code must be nonblank and bounded");
		}
		LinkedHashMap<AgentId, String> transitions = AVAILABILITY_DIAGNOSTICS.computeIfAbsent(
				server, ignored -> new LinkedHashMap<>()
		);
		if (code.equals(transitions.get(agentId))) return false;
		if (!transitions.containsKey(agentId) && transitions.size() >= MAX_DIAGNOSTIC_AGENTS) {
			transitions.remove(transitions.keySet().iterator().next());
		}
		transitions.put(agentId, code);
		return true;
	}

	static synchronized String clearAvailabilityFallback(MinecraftServer server, AgentId agentId) {
		LinkedHashMap<AgentId, String> transitions = AVAILABILITY_DIAGNOSTICS.get(server);
		if (transitions == null) return null;
		String recovered = transitions.remove(agentId);
		if (transitions.isEmpty()) AVAILABILITY_DIAGNOSTICS.remove(server);
		return recovered;
	}

	public static synchronized CompletionStage<VoiceReceipt> speak(MinecraftServer server, VoiceRequest request) {
		Holder holder = INSTANCES.get(server);
		VoiceSubsystem subsystem = holder == null ? NoVoiceSubsystem.INSTANCE : holder.subsystem();
		CompletionStage<VoiceReceipt> speech;
		try {
			speech = Objects.requireNonNull(subsystem.speak(request), "voice subsystem returned no receipt");
		} catch (RuntimeException exception) {
			if (availabilityFallbackTransition(server, request.agentId(), "VOICE_RUNTIME_FAILED")) {
				LOGGER.warn("Proximity voice entered text fallback [VOICE_RUNTIME_FAILED] at runtime boundary for agent {}",
						request.agentId());
			}
			return java.util.concurrent.CompletableFuture.completedFuture(
					VoiceReceipt.degraded("[VOICE_RUNTIME_FAILED] Voice transport failed")
			);
		}
		speech.whenComplete((receipt, failure) -> {
			if (failure != null || receipt == null || receipt.status() == VoiceReceipt.Status.FAILED
					|| receipt.status() == VoiceReceipt.Status.DEGRADED_TO_TEXT) return;
			String recovered = clearAvailabilityFallback(server, request.agentId());
			if (recovered != null) {
				LOGGER.info("Proximity voice recovered from [{}] at availability boundary for agent {}",
						recovered, request.agentId());
			}
		});
		return speech;
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
		AVAILABILITY_DIAGNOSTICS.remove(server);
		Holder holder = INSTANCES.remove(server);
		if (holder != null) close(server, holder);
		RUNTIME_DIAGNOSTICS.remove(server);
	}

	private static boolean safelyAvailable(MinecraftServer server, VoiceSubsystem subsystem) {
		try {
			boolean available = subsystem.available();
			if (available) reportRuntimeRecovery(server);
			return available;
		} catch (RuntimeException exception) {
			reportRuntimeFailure(server, "VOICE_HEALTH_CHECK_FAILED", exception);
			return false;
		}
	}

	private static void close(MinecraftServer server, Holder holder) {
		try {
			holder.tracker().clear(holder.subsystem());
		} catch (RuntimeException exception) {
			reportRuntimeFailure(server, "VOICE_REGISTRATION_CLEANUP_FAILED", exception);
		} finally {
			try {
				holder.subsystem().close();
			} catch (RuntimeException exception) {
				reportRuntimeFailure(server, "VOICE_TRANSPORT_CLEANUP_FAILED", exception);
			}
		}
	}

	private static void reportRuntimeFailure(MinecraftServer server, String code, RuntimeException failure) {
		if (code.equals(RUNTIME_DIAGNOSTICS.get(server))) return;
		RUNTIME_DIAGNOSTICS.put(server, code);
		LOGGER.warn("Proximity voice degraded [{}] ({}); coordinator recovery remains available",
				code, failure.getClass().getSimpleName());
	}

	private static void reportRuntimeRecovery(MinecraftServer server) {
		String recovered = RUNTIME_DIAGNOSTICS.remove(server);
		if (recovered != null) LOGGER.info("Proximity voice recovered from [{}]", recovered);
	}

	private record Holder(
			VoiceSubsystemConfiguration configuration,
			VoiceSubsystem subsystem,
			VoiceRegistrationTracker tracker,
			Map<AgentId, Long> sequences
	) {
	}
}
