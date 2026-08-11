package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentServerRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentServerRuntime.class);
	private static final Map<MinecraftServer, MultiplexedServerBridge> BRIDGES = new ConcurrentHashMap<>();
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
		registered = true;
	}

	private static void start(MinecraftServer server) {
		if (BRIDGES.containsKey(server)) {
			return;
		}
		try {
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
		manager.maintainChunkTickets();
		manager.reconcileDeaths();
		MultiplexedServerBridge bridge = BRIDGES.get(server);
		if (bridge != null) {
			bridge.tick();
		}
		ScenarioRuntimeService.tick(server);
	}

	private static void stop(MinecraftServer server) {
		MultiplexedServerBridge bridge = BRIDGES.remove(server);
		try {
			CodexAgentManager.release(server);
		} finally {
			ScenarioRuntimeService.release(server);
			if (bridge != null) {
				bridge.close();
			}
		}
	}
}
