package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.conversation.DeliveryReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime;
import dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry;
import dev.agaminggod.arenaagents.server.goal.GoalVerificationRuntime;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentServerRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentServerRuntime.class);
	private static final Map<MinecraftServer, MultiplexedServerBridge> BRIDGES = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, CoordinatorProcessSupervisor> COORDINATORS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, Map<String, Long>> PLANNING_UPDATES = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, GoalVerificationRuntime> GOAL_VERIFIERS = new ConcurrentHashMap<>();
	private static final long PLANNING_UPDATE_INTERVAL_MS = 30_000L;
	private static boolean registered;

	private CodexAgentServerRuntime() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(CodexAgentServerRuntime::start);
		ServerTickEvents.END_SERVER_TICK.register(CodexAgentServerRuntime::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(CodexAgentServerRuntime::stop);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				VoiceConsentRegistry.clearPlayer(server, handler.getPlayer().getUUID()));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damageAmount) -> {
			if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) return true;
			return AgentDeathCapture.allowVanillaDeath(
					ScenarioRuntimeService.recoverParkourDeath(player),
					() -> CodexAgentManager.get(player.level().getServer()).captureDeath(player, source)
			);
		});
		ServerLivingEntityEvents.AFTER_DEATH.register(CodexAgentServerRuntime::recordAttributedKill);
		registered = true;
	}

	private static void start(MinecraftServer server) {
		if (BRIDGES.containsKey(server)) {
			return;
		}
		CodexAgentManager manager = CodexAgentManager.get(server);
		GOAL_VERIFIERS.computeIfAbsent(server, ignored -> new GoalVerificationRuntime(
				manager.registry(),
				agentId -> manager.findAgentPlayer(agentId).map(dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier::minecraftFacts),
				server::getTickCount,
				System::currentTimeMillis
		));
		CoordinatorProcessSupervisor supervisor = null;
		try {
			// Prepare the package, Node executable, and canonical secret before any optional
			// voice or catalog-dependent bridge work is constructed.
			supervisor = new CoordinatorProcessSupervisor();
			if (supervisor.configured() || supervisor.failureCode() != null) {
				COORDINATORS.put(server, supervisor);
			}
			if (supervisor.failureCode() != null) {
				LOGGER.error("Arena Agents automation startup stopped cleanly [{}]: {}",
						supervisor.failureCode(), supervisor.failureMessage());
				return;
			}
			VoiceSubsystemRuntime.start(server);
			Path secretPath = supervisor.secretPath();
			AgentVerboseState verboseState = AgentVerboseState.forServer(server);
			MultiplexedServerBridge bridge = secretPath == null
					? new MultiplexedServerBridge(CodexAgentManager.get(server), verboseState)
					: new MultiplexedServerBridge(CodexAgentManager.get(server), secretPath, verboseState);
			bridge.start();
			MultiplexedServerBridge previous = BRIDGES.putIfAbsent(server, bridge);
			if (previous != null) {
				bridge.close();
			}
		} catch (RuntimeException exception) {
			if (supervisor != null) {
				COORDINATORS.remove(server, supervisor);
				supervisor.close();
			}
			VoiceSubsystemRuntime.close(server);
			LOGGER.error(
					"Codex agent bridge is unavailable; summoned agents will remain locally controllable but autonomous planning is disabled",
					exception
			);
		}
	}

	private static void tick(MinecraftServer server) {
		if (!ScenarioRuntimeService.restorePersistedState(server)) {
			return;
		}
		CodexAgentManager manager = CodexAgentManager.get(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		if (supervisor != null) supervisor.tick(bridge != null && bridge.authenticated());
		manager.reconcileDeaths();
		manager.maintainChunkTickets();
		GoalVerificationRuntime goalVerifier = GOAL_VERIFIERS.get(server);
		if (goalVerifier != null) goalVerifier.tick();
		VoiceSubsystemRuntime.tick(server);
		maintainPlanningProgress(manager);
		if (bridge != null) {
			bridge.tick();
		}
		ScenarioRuntimeService.tick(server);
	}

	private static void maintainPlanningProgress(CodexAgentManager manager) {
		long now = System.currentTimeMillis();
		Map<String, Long> lastUpdates = PLANNING_UPDATES.computeIfAbsent(manager.server(), ignored -> new HashMap<>());
		java.util.Set<String> planning = new java.util.HashSet<>();
		for (var record : manager.records()) {
			if (record.state() != AgentLifecycleState.PLANNING) continue;
			String agentId = record.agentId().toString();
			planning.add(agentId);
			long last = lastUpdates.getOrDefault(agentId, record.updatedAtEpochMs());
			if (now - last >= PLANNING_UPDATE_INTERVAL_MS) {
				AgentChatReporter.stillPlanning(manager, record);
				lastUpdates.put(agentId, now);
			}
		}
		lastUpdates.keySet().retainAll(planning);
	}

	public static boolean automationAvailable(MinecraftServer server) {
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		return bridge != null && bridge.authenticated();
	}

	public static void setVerbose(MinecraftServer server, boolean enabled) {
		AgentVerboseState state = AgentVerboseState.forServer(server);
		state.setEnabled(enabled);
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		if (bridge != null) bridge.setVerbose(enabled);
	}

	public static String automationStatus(MinecraftServer server) {
		CoordinatorProcessSupervisor supervisor = COORDINATORS.get(server);
		if (supervisor != null && supervisor.failureCode() != null) {
			return startupFailureStatus(supervisor.failureCode());
		}
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		if (bridge == null) {
			return "Automation is offline. Restart Minecraft after checking the bridge setup.";
		}
		return bridge.authenticated() ? "Automation ready" : "Waiting for the agent coordinator...";
	}

	private static String startupFailureStatus(String code) {
		if (code.startsWith("NODE_RUNTIME")) {
			return "Automation is offline. Node.js 22+ was not found; set -Darenaagents.nodePath to an absolute executable or install the bundled profile runtime.";
		}
		if ("BRIDGE_SECRET_PATH_CONFLICT".equals(code)) {
			return "Automation is offline. Bridge and voice secret paths must point to the prepared runtime secret.";
		}
		if ("COORDINATOR_RESTART_EXHAUSTED".equals(code)) {
			return "Automation is offline. The coordinator stopped repeatedly; check the coordinator error log and restart Minecraft.";
		}
		return "Automation is offline. Restart Minecraft after checking the coordinator setup.";
	}

	public static List<AgentControlModelOption> modelCatalog(MinecraftServer server) {
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		return bridge == null ? AgentControlCatalog.fallbackOptions() : bridge.catalogModels();
	}

	public static void requireAutomation(MinecraftServer server) {
		if (!automationAvailable(server)) {
			throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		}
	}

	public static DeliveryReceipt sendDirectMessage(
			MinecraftServer server,
			ServerPlayer source,
			AgentId recipientAgentId,
			String text
	) {
		requireAutomation(server);
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		if (bridge == null) throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		return bridge.sendPlayerDirectMessage(source, recipientAgentId, text);
	}

	public static DeliveryReceipt sendNativeDirectMessage(
			MinecraftServer server,
			ServerPlayer source,
			AgentId recipientAgentId,
			String text
	) {
		requireAutomation(server);
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		if (bridge == null) throw new AgentDomainException("AUTOMATION_UNAVAILABLE", automationStatus(server));
		return bridge.sendNativePlayerDirectMessage(source, recipientAgentId, text);
	}

	public static DeliveryReceipt deliverHumanSpeech(
			MinecraftServer server,
			UUID sourcePlayerId,
			String transcript,
			boolean whispering
	) {
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		if (bridge == null || !bridge.authenticated()) return new DeliveryReceipt(List.of(), List.of());
		ServerPlayer source = server.getPlayerList().getPlayer(sourcePlayerId);
		if (source == null) return new DeliveryReceipt(List.of(), List.of());
		for (var record : CodexAgentManager.get(server).records()) {
			if (record.entityUuid().filter(sourcePlayerId::equals).isPresent()) {
				return new DeliveryReceipt(List.of(), List.of());
			}
		}
		return bridge.sendPlayerProximitySpeech(source, transcript, whispering);
	}

	public static boolean hasVoiceConsent(MinecraftServer server, UUID playerId) {
		return VoiceConsentRegistry.granted(server, playerId);
	}

	private static void recordAttributedKill(net.minecraft.world.entity.LivingEntity entity, net.minecraft.world.damagesource.DamageSource source) {
		if (!(source.getEntity() instanceof ServerPlayer responsible)) return;
		MinecraftServer server = responsible.level().getServer();
		GoalVerificationRuntime runtime = GOAL_VERIFIERS.get(server);
		if (runtime == null) return;
		for (var record : CodexAgentManager.get(server).records()) {
			if (record.entityUuid().filter(responsible.getUUID()::equals).isEmpty()) continue;
			runtime.recordKill(record.agentId(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			return;
		}
	}

	private static void stop(MinecraftServer server) {
		PLANNING_UPDATES.remove(server);
		GOAL_VERIFIERS.remove(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.remove(server);
		MultiplexedServerBridge bridge = BRIDGES.remove(server);
		try {
			VoiceSubsystemRuntime.close(server);
			VoiceConsentRegistry.clear(server);
			CodexAgentManager.release(server);
		} finally {
			ScenarioRuntimeService.release(server);
			if (bridge != null) {
				bridge.close();
			}
			if (supervisor != null) supervisor.close();
			AgentVerboseState.release(server);
		}
	}
}
