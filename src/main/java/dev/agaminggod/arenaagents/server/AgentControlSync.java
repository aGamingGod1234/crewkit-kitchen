package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.control.AgentControlRequestPayload;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import dev.agaminggod.arenaagents.control.AgentControlSnapshotPayload;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshotPayload;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentControlSync {
	private static final Logger LOGGER = LoggerFactory.getLogger(AgentControlSync.class);
	private static final Map<MinecraftServer, SpectatorPublication> SPECTATOR_PUBLICATIONS = new WeakHashMap<>();
	private static boolean registered;

	private AgentControlSync() {
	}

	public static synchronized void register() {
		if (registered) {
			return;
		}
		PayloadTypeRegistry.serverboundPlay().register(AgentControlRequestPayload.TYPE, AgentControlRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ScenarioLaunchPayload.TYPE, ScenarioLaunchPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AgentControlSnapshotPayload.TYPE, AgentControlSnapshotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				ArenaSpectatorSnapshotPayload.TYPE,
				ArenaSpectatorSnapshotPayload.CODEC
		);
		boolean receiverRegistered = ServerPlayNetworking.registerGlobalReceiver(
				AgentControlRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> sendSnapshot(context.player()))
		);
		if (!receiverRegistered) {
			throw new IllegalStateException("Arena Agents control snapshot receiver is already registered");
		}
		boolean scenarioReceiverRegistered = ServerPlayNetworking.registerGlobalReceiver(
				ScenarioLaunchPayload.TYPE,
				(payload, context) -> context.server().execute(() -> {
					try {
						ScenarioRuntimeService.launch(context.player(), payload.request());
					} catch (RuntimeException exception) {
						context.player().sendSystemMessage(Component.literal(
								"Scenario launch rejected: " + safeMessage(exception)
						));
					}
				})
		);
		if (!scenarioReceiverRegistered) {
			throw new IllegalStateException("Arena Agents scenario launch receiver is already registered");
		}
		ServerTickEvents.END_SERVER_TICK.register(AgentControlSync::publishSpectatorSnapshot);
		registered = true;
	}

	private static synchronized void publishSpectatorSnapshot(MinecraftServer server) {
		SpectatorPublication publication = SPECTATOR_PUBLICATIONS.computeIfAbsent(
				server,
				ignored -> new SpectatorPublication()
		);
		long currentTick = publication.nextTick();
		Optional<ArenaSpectatorSnapshot.PublicView> view = ScenarioRuntimeService.spectatorView(server);
		Optional<ArenaSpectatorSnapshot.PublicView> retainedView = ArenaSpectatorSnapshot.retainPublication(
				Optional.ofNullable(publication.retainedView),
				view
		);
		publication.retainedView = retainedView.orElse(null);
		if (retainedView.isEmpty()) return;
		if (!publication.cadence.due(currentTick)) return;
		ArenaSpectatorSnapshot.PublicView publicView = publication.retainedView;
		if (!publicView.runId().equals(publication.runId)) {
			publication.runId = publicView.runId();
			publication.lastByPlayer.clear();
		}
		ArenaSpectatorSnapshot snapshot = ArenaSpectatorSnapshot.fromPublicView(
				publication.nextRevision(),
				publicView
		);
		Set<UUID> connectedPlayers = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			connectedPlayers.add(player.getUUID());
			if (!ServerPlayNetworking.canSend(player, ArenaSpectatorSnapshotPayload.TYPE)) continue;
			try {
				ArenaSpectatorSnapshot previous = publication.lastByPlayer.get(player.getUUID());
				ArenaSpectatorSnapshotPayload payload = previous == null
						? ArenaSpectatorSnapshotPayload.full(snapshot)
						: ArenaSpectatorSnapshotPayload.delta(previous, snapshot);
				ServerPlayNetworking.send(player, payload);
				publication.lastByPlayer.put(player.getUUID(), snapshot);
			} catch (RuntimeException exception) {
				LOGGER.warn("Could not send Arena Agents spectator snapshot to {}", player.getScoreboardName(), exception);
			}
		}
		publication.lastByPlayer.keySet().retainAll(connectedPlayers);
		publication.cadence.markPublished(currentTick);
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}

	public static void sendSnapshot(ServerPlayer player) {
		try {
			boolean canControl = GoalControl.mayControl(player.createCommandSourceStack());
			AgentControlSnapshot snapshot = AgentControlSnapshot.fromRecords(
					canControl,
					System.currentTimeMillis(),
					CodexAgentManager.get(player.level().getServer()).records()
			);
			if (ServerPlayNetworking.canSend(player, AgentControlSnapshotPayload.TYPE)) {
				ServerPlayNetworking.send(player, AgentControlSnapshotPayload.fromSnapshot(snapshot));
			}
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not send Arena Agents control snapshot to {}", player.getScoreboardName(), exception);
		}
	}

	private static final class SpectatorPublication {
		private final ArenaSpectatorSnapshot.PublicationCadence cadence =
				new ArenaSpectatorSnapshot.PublicationCadence(4);
		private final Map<UUID, ArenaSpectatorSnapshot> lastByPlayer = new LinkedHashMap<>();
		private long serverTick;
		private long revision;
		private String runId = "";
		private ArenaSpectatorSnapshot.PublicView retainedView;

		private long nextTick() {
			return ++serverTick;
		}

		private long nextRevision() {
			return ++revision;
		}
	}
}
