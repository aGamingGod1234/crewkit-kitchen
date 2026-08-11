package dev.agaminggod.arenaagents.client.control;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agaminggod.arenaagents.client.gui.AgentControlScreen;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioLaunchPlan;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioLaunchRegistry;
import dev.agaminggod.arenaagents.client.gui.scenario.ScenarioSetupScreen;
import dev.agaminggod.arenaagents.client.presentation.ArenaSpectatorHud;
import dev.agaminggod.arenaagents.client.presentation.ArenaSpectatorState;
import dev.agaminggod.arenaagents.client.presentation.ScenarioResultsScreen;
import dev.agaminggod.arenaagents.client.presentation.SpectatorCameraAssistant;
import dev.agaminggod.arenaagents.scenario.ScenarioAgentSpec;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchPayload;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchRequest;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshotPayload;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlRequestPayload;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import dev.agaminggod.arenaagents.control.AgentControlSnapshotPayload;
import dev.agaminggod.arenaagents.control.AgentControlSnapshotStore;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentControlClient {
	public static final int SNAPSHOT_REFRESH_TICKS = 20;
	private static final Logger LOGGER = LoggerFactory.getLogger(AgentControlClient.class);
	private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("arenaagents", "controls")
	);
	private static final KeyMapping OPEN_CONTROL = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.arenaagents.open_controls",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_G,
			KEY_CATEGORY
	));
	private static final KeyMapping TOGGLE_CAMERA_ASSISTANT = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.arenaagents.toggle_camera_assistant",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_H,
			KEY_CATEGORY
	));
	private static final AgentControlSnapshotStore SNAPSHOTS = new AgentControlSnapshotStore();
	private static final ArenaSpectatorState SPECTATOR_STATE = new ArenaSpectatorState();
	private static final SpectatorCameraAssistant CAMERA_ASSISTANT = new SpectatorCameraAssistant();
	private static Preferences preferences = Preferences.defaults();
	private static boolean registered;
	private static final Set<String> HIDDEN_AGENT_IDS = new LinkedHashSet<>();
	private static final Set<String> AUTOMATIC_AGENT_IDS = new LinkedHashSet<>();
	private static final Set<String> KNOWN_AGENT_IDS = new LinkedHashSet<>();
	private static int refreshCountdown;

	private AgentControlClient() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		boolean receiverRegistered = ClientPlayNetworking.registerGlobalReceiver(
				AgentControlSnapshotPayload.TYPE,
				(payload, context) -> context.client().execute(() -> acceptSnapshot(payload.snapshot()))
		);
		if (!receiverRegistered) {
			throw new IllegalStateException("Arena Agents control snapshot receiver is already registered");
		}
		boolean spectatorReceiverRegistered = ClientPlayNetworking.registerGlobalReceiver(
				ArenaSpectatorSnapshotPayload.TYPE,
				(payload, context) -> context.client().execute(() -> acceptSpectatorPayload(context.client(), payload))
		);
		if (!spectatorReceiverRegistered) {
			throw new IllegalStateException("Arena Agents spectator snapshot receiver is already registered");
		}
		ArenaSpectatorHud.register(SPECTATOR_STATE);
		ClientTickEvents.END_CLIENT_TICK.register(AgentControlClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearConnectionState());
		ScenarioLaunchRegistry.register(AgentControlClient::launchScenario);
		registered = true;
	}

	public static Optional<AgentControlSnapshot> snapshot() {
		return SNAPSHOTS.current();
	}

	public static ArenaSpectatorState spectatorState() {
		return SPECTATOR_STATE;
	}

	public static Preferences preferences() {
		return preferences;
	}

	public static Set<String> hiddenAgentIds() {
		return HIDDEN_AGENT_IDS;
	}

	public static Set<String> automaticAgentIds() {
		return AUTOMATIC_AGENT_IDS;
	}

	public static Set<String> knownAgentIds() {
		return KNOWN_AGENT_IDS;
	}

	public static void rememberPreferences(String provider, String model, String reasoning) {
		preferences = new Preferences(provider, model, reasoning);
	}

	public static void requestSnapshot() {
		try {
			if (ClientPlayNetworking.canSend(AgentControlRequestPayload.TYPE)) {
				ClientPlayNetworking.send(AgentControlRequestPayload.INSTANCE);
			}
		} catch (IllegalStateException exception) {
			LOGGER.debug("Arena Agents control snapshot request skipped while disconnected");
		}
	}

	public static boolean sendCommand(String command) {
		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return false;
		}
		client.getConnection().sendCommand(Objects.requireNonNull(command, "command must not be null"));
		refreshCountdown = 2;
		return true;
	}

	private static ScenarioLaunchRegistry.Result launchScenario(ScenarioLaunchPlan plan) {
		try {
			if (!ClientPlayNetworking.canSend(ScenarioLaunchPayload.TYPE)) {
				return new ScenarioLaunchRegistry.Result(false, "The server does not support scenario launches");
			}
			ScenarioLaunchRequest request = new ScenarioLaunchRequest(
					plan.scenarioId(),
					plan.mapVersion(),
					plan.deterministicEvents(),
					plan.roster().stream().map(agent -> new ScenarioAgentSpec(
							agent.slot(),
							agent.displayName(),
							agent.provider(),
							agent.model(),
							agent.reasoning(),
							Optional.of(agent.team()).filter(value -> !value.isBlank()),
							agent.gameMode()
					)).toList()
			);
			ClientPlayNetworking.send(ScenarioLaunchPayload.fromRequest(request));
			return new ScenarioLaunchRegistry.Result(
					true,
					plan.scenarioTitle() + " is being built; progress will appear in chat"
			);
		} catch (RuntimeException exception) {
			String message = exception.getMessage();
			return new ScenarioLaunchRegistry.Result(
					false,
					message == null || message.isBlank() ? exception.getClass().getSimpleName() : message
			);
		}
	}

	private static void tick(Minecraft client) {
		while (OPEN_CONTROL.consumeClick()) {
			if (client.player != null && client.level != null && client.screen == null) {
				client.setScreen(new ScenarioSetupScreen());
			}
		}
		while (TOGGLE_CAMERA_ASSISTANT.consumeClick()) {
			if (!SPECTATOR_STATE.cameraDisabled()) {
				SPECTATOR_STATE.disableCamera();
				CAMERA_ASSISTANT.resetTracking();
			} else {
				long currentTick = SPECTATOR_STATE.snapshot()
						.map(snapshot -> snapshot.elapsedTick())
						.orElse(0L);
				SPECTATOR_STATE.enableCamera(
						client.screen == null && client.player != null && client.player.isSpectator(),
						currentTick
				);
				CAMERA_ASSISTANT.resetTracking();
			}
		}
		if (client.screen instanceof AgentControlScreen) {
			if (refreshCountdown <= 0) {
				requestSnapshot();
				refreshCountdown = SNAPSHOT_REFRESH_TICKS;
			} else {
				refreshCountdown--;
			}
		} else {
			refreshCountdown = 0;
		}
		CAMERA_ASSISTANT.tick(client, SPECTATOR_STATE);
		showResultsIfAvailable(client);
	}

	private static void acceptSnapshot(AgentControlSnapshot nextSnapshot) {
		if (!SNAPSHOTS.accept(Objects.requireNonNull(nextSnapshot, "nextSnapshot must not be null"))) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.screen instanceof AgentControlScreen screen) {
			screen.acceptSnapshot(nextSnapshot);
		}
	}

	private static void acceptSpectatorPayload(Minecraft client, ArenaSpectatorSnapshotPayload payload) {
		if (SPECTATOR_STATE.accept(Objects.requireNonNull(payload, "payload must not be null"))) {
			showResultsIfAvailable(client);
		}
	}

	private static void showResultsIfAvailable(Minecraft client) {
		if (client.screen != null || !SPECTATOR_STATE.resultsAvailable()) return;
		SPECTATOR_STATE.snapshot()
				.filter(snapshot -> snapshot.terminal())
				.ifPresent(snapshot -> client.setScreen(new ScenarioResultsScreen(
						snapshot,
						SPECTATOR_STATE::dismissResults
				)));
	}

	private static void clearConnectionState() {
		SNAPSHOTS.clear();
		SPECTATOR_STATE.clearOnDisconnect();
		CAMERA_ASSISTANT.resetTracking();
		refreshCountdown = 0;
	}

	public record Preferences(String provider, String model, String reasoning) {
		public Preferences {
			provider = AgentControlCatalog.requireProvider(provider);
			model = Objects.requireNonNull(model, "model must not be null");
			reasoning = Objects.requireNonNull(reasoning, "reasoning must not be null");
		}

		private static Preferences defaults() {
			String provider = AgentControlCatalog.providers().getFirst();
			String model = AgentControlCatalog.defaultModel(provider);
			return new Preferences(provider, model, AgentControlCatalog.defaultReasoning(provider, model));
		}
	}
}
