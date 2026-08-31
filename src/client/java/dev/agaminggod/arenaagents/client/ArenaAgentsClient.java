package dev.agaminggod.arenaagents.client;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.client.action.ActionEventPublisher;
import dev.agaminggod.arenaagents.client.action.ClientActionRuntime;
import dev.agaminggod.arenaagents.client.action.MinecraftActionContext;
import dev.agaminggod.arenaagents.client.bridge.BridgeEventSink;
import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.client.config.AgentConfigLoader;
import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.network.GoalReceiver;
import dev.agaminggod.arenaagents.client.perception.Observation;
import dev.agaminggod.arenaagents.client.perception.ObservationCollector;
import dev.agaminggod.arenaagents.client.perception.ObservationWireBudget;
import dev.agaminggod.arenaagents.client.render.CodexAgentRenderers;
import dev.agaminggod.arenaagents.protocol.ActionCommand;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ArenaAgentsClient implements ClientModInitializer {
	private static final String EVENT_OBSERVATION = "observation";
	private static final String COORDINATOR_CANCEL_REASON = "coordinator_cancelled";
	private static final String CLIENT_STOPPING_REASON = "client_stopping";
	private static final String CLIENT_START_FAILURE_REASON = "client_start_failed";
	private static final Logger LOGGER = LoggerFactory.getLogger(ArenaAgentsClient.class);

	private BridgeServer bridgeServer;
	private ProtocolCodec protocolCodec;
	private ObservationCollector observationCollector;
	private ClientActionRuntime actionRuntime;
	private long observationSessionId;

	@Override
	public void onInitializeClient() {
		CodexAgentRenderers.register();
		AgentControlClient.register();
		AgentConfig config = loadConfig();
		if (!config.enabled()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		protocolCodec = new ProtocolCodec();
		bridgeServer = new BridgeServer(
				config,
				protocolCodec,
				minecraft::execute,
				new ClientBridgeEventSink()
		);
		ActionEventPublisher actionPublisher = new ActionEventPublisher(
				bridgeServer,
				exception -> LOGGER.warn("Could not publish an Arena Agents action event", exception)
		);
		actionRuntime = new ClientActionRuntime(
				new MinecraftActionContext(minecraft),
				(ClientActionRuntime.SessionEventSink) actionPublisher
		);
		observationCollector = new ObservationCollector(
				minecraft,
				config.observationRadius(),
				() -> actionRuntime.currentStatus(observationSessionId),
				() -> actionRuntime.lastResult(observationSessionId)
		);
		if (!startBridgeOrCleanup(
				bridgeServer::start,
				() -> actionRuntime.stop(CLIENT_START_FAILURE_REASON),
				bridgeServer::close,
				exception -> LOGGER.error(
						"Arena Agents legacy client bridge failed to start and has been disabled",
						exception
				)
		)) {
			RuntimeException lifecycleFailure = actionRuntime.lastLifecycleFailure();
			if (lifecycleFailure != null) {
				LOGGER.warn("Could not release all Arena Agents resources after bridge startup failure", lifecycleFailure);
			}
			clearLegacyRuntime();
			return;
		}
		GoalReceiver.register(actionRuntime, bridgeServer);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> bridgeServer.rotateSession());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> bridgeServer.rotateSession());
		ClientTickEvents.END_CLIENT_TICK.register(client -> actionRuntime.tick());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopClient());
	}

	private void stopClient() {
		actionRuntime.stop(CLIENT_STOPPING_REASON);
		RuntimeException lifecycleFailure = actionRuntime.lastLifecycleFailure();
		if (lifecycleFailure != null) {
			LOGGER.warn("Could not release all Arena Agents resources during client shutdown", lifecycleFailure);
		}
		bridgeServer.close();
	}

	private void clearLegacyRuntime() {
		bridgeServer = null;
		protocolCodec = null;
		observationCollector = null;
		actionRuntime = null;
	}

	private void publishObservation(long sessionId) {
		observationSessionId = sessionId;
		Observation observation;
		try {
			observation = observationCollector.collect();
		} finally {
			observationSessionId = 0L;
		}
		ObservationWireBudget.FittedObservation fitted = ObservationWireBudget.fit(
				observation,
				protocolCodec,
				bridgeServer,
				EVENT_OBSERVATION
		);
		JsonObject payload = fitted.payload();
		bridgeServer.sendEvent(sessionId, EVENT_OBSERVATION, payload);
	}

	private final class ClientBridgeEventSink implements BridgeEventSink {
		@Override
		public void onActionCommand(ActionCommand command) {
			actionRuntime.onActionCommand(command);
		}

		@Override
		public void onActionCommand(long sessionId, ActionCommand command) {
			actionRuntime.onActionCommand(sessionId, command);
		}

		@Override
		public void onCancelAction(String commandId) {
			actionRuntime.onCancelAction(commandId, COORDINATOR_CANCEL_REASON);
		}

		@Override
		public void onCancelAction(long sessionId, String commandId) {
			actionRuntime.onCancelAction(sessionId, commandId, COORDINATOR_CANCEL_REASON);
		}

		@Override
		public void onObservationRequested() {
			long sessionId = bridgeServer.authenticatedSessionId();
			if (sessionId != 0L) publishObservation(sessionId);
		}

		@Override
		public void onObservationRequested(long sessionId) {
			publishObservation(sessionId);
		}

		@Override
		public void onSessionClosed(long sessionId) {
			actionRuntime.onSessionClosed(sessionId);
		}
	}

	private static AgentConfig loadConfig() {
		return loadConfigOrDisabled(
				AgentConfigLoader::loadOrCreate,
				exception -> LOGGER.error(
						"Arena Agents legacy client bridge configuration is invalid or unreadable; the bridge is disabled",
						exception
				)
		);
	}

	static AgentConfig loadConfigOrDisabled(
			ConfigLoader loader,
			Consumer<RuntimeException> failureReporter
	) {
		Objects.requireNonNull(loader, "loader must not be null");
		Objects.requireNonNull(failureReporter, "failureReporter must not be null");
		try {
			return Objects.requireNonNull(loader.load(), "loader returned null config");
		} catch (IOException exception) {
			RuntimeException wrapped = new IllegalStateException("Could not load Arena Agents client config", exception);
			failureReporter.accept(wrapped);
			return AgentConfig.defaults();
		} catch (RuntimeException exception) {
			failureReporter.accept(exception);
			return AgentConfig.defaults();
		}
	}

	static boolean startBridgeOrCleanup(
			Runnable starter,
			Runnable actionStopper,
			Runnable bridgeCloser,
			Consumer<RuntimeException> failureReporter
	) {
		Objects.requireNonNull(starter, "starter must not be null");
		Objects.requireNonNull(actionStopper, "actionStopper must not be null");
		Objects.requireNonNull(bridgeCloser, "bridgeCloser must not be null");
		Objects.requireNonNull(failureReporter, "failureReporter must not be null");
		try {
			starter.run();
			return true;
		} catch (RuntimeException exception) {
			runCleanup(exception, actionStopper);
			runCleanup(exception, bridgeCloser);
			failureReporter.accept(exception);
			return false;
		}
	}

	private static void runCleanup(RuntimeException startFailure, Runnable cleanup) {
		try {
			cleanup.run();
		} catch (RuntimeException cleanupFailure) {
			startFailure.addSuppressed(cleanupFailure);
		}
	}

	@FunctionalInterface
	interface ConfigLoader {
		AgentConfig load() throws IOException;
	}
}
