package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentServerRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentServerRuntime.class);
	private static final Map<MinecraftServer, MultiplexedServerBridge> BRIDGES = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, CoordinatorProcessSupervisor> COORDINATORS = new ConcurrentHashMap<>();
	private static final Map<MinecraftServer, Map<String, Long>> PLANNING_UPDATES = new ConcurrentHashMap<>();
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
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, damageAmount) -> {
			if (!(entity instanceof net.minecraft.server.level.ServerPlayer player)) return true;
			return AgentDeathCapture.allowVanillaDeath(
					ScenarioRuntimeService.recoverParkourDeath(player),
					() -> CodexAgentManager.get(player.level().getServer()).captureDeath(player, source)
			);
		});
		registered = true;
	}

	private static void start(MinecraftServer server) {
		if (BRIDGES.containsKey(server)) {
			return;
		}
		try {
			CoordinatorProcessSupervisor supervisor = new CoordinatorProcessSupervisor();
			if (supervisor.configured()) {
				COORDINATORS.put(server, supervisor);
			}
			MultiplexedServerBridge bridge = new MultiplexedServerBridge(CodexAgentManager.get(server));
			bridge.start();
			MultiplexedServerBridge previous = BRIDGES.putIfAbsent(server, bridge);
			if (previous != null) {
				bridge.close();
			}
		} catch (RuntimeException exception) {
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
		manager.maintainChunkTickets();
		manager.reconcileDeaths();
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

	public static String automationStatus(MinecraftServer server) {
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		if (bridge == null) {
			return "Automation is offline. Restart Minecraft after checking the bridge setup.";
		}
		return bridge.authenticated() ? "Automation ready" : "Waiting for the agent coordinator...";
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

	private static void stop(MinecraftServer server) {
		PLANNING_UPDATES.remove(server);
		CoordinatorProcessSupervisor supervisor = COORDINATORS.remove(server);
		MultiplexedServerBridge bridge = BRIDGES.remove(server);
		try {
			CodexAgentManager.release(server);
		} finally {
			ScenarioRuntimeService.release(server);
			if (bridge != null) {
				bridge.close();
			}
			if (supervisor != null) supervisor.close();
		}
	}
}
