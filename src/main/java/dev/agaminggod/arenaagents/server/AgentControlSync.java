package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.control.DirectorSnapshotPayload;
import dev.agaminggod.arenaagents.control.DirectorEditorPayload;
import dev.agaminggod.arenaagents.control.DirectorCommandRequestPayload;
import dev.agaminggod.arenaagents.control.DirectorCommandResultPayload;
import dev.agaminggod.arenaagents.control.AgentControlRequestPayload;
import dev.agaminggod.arenaagents.control.AgentControlGroup;
import dev.agaminggod.arenaagents.control.AgentControlModelOption;
import dev.agaminggod.arenaagents.control.AgentControlSnapshot;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.control.AgentControlSnapshotPayload;
import dev.agaminggod.arenaagents.pov.OperatorActionPayload;
import dev.agaminggod.arenaagents.pov.OperatorInputPayload;
import dev.agaminggod.arenaagents.pov.PovPayloads;
import dev.agaminggod.arenaagents.server.pov.PovSessionRuntime;
import dev.agaminggod.arenaagents.scenario.ScenarioLaunchPayload;
import dev.agaminggod.arenaagents.scenario.ScenarioCancelPayload;
import dev.agaminggod.arenaagents.scenario.ScenarioPresets;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorClearPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshot;
import dev.agaminggod.arenaagents.scenario.presentation.ArenaSpectatorSnapshotPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgressClearPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgressPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioPresentationExpiry;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioRuntimeService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
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
		PovPayloads.registerTypes();
		// Takeover input is routed on the server thread; the runtime re-checks the session and permission.
		if (!ServerPlayNetworking.registerGlobalReceiver(OperatorInputPayload.TYPE,
				(payload, context) -> context.server().execute(() -> PovSessionRuntime.handleInput(context.player(), payload)))
				|| !ServerPlayNetworking.registerGlobalReceiver(OperatorActionPayload.TYPE,
				(payload, context) -> context.server().execute(() -> PovSessionRuntime.handleAction(context.player(), payload)))
				|| !ServerPlayNetworking.registerGlobalReceiver(dev.agaminggod.arenaagents.pov.OperatorTextPayload.TYPE,
				(payload, context) -> context.server().execute(() -> PovSessionRuntime.handleText(context.player(), payload)))) {
			throw new IllegalStateException("Arena Agents takeover input receivers are already registered");
		}
		PayloadTypeRegistry.serverboundPlay().register(AgentControlRequestPayload.TYPE, AgentControlRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(dev.agaminggod.arenaagents.control.LiveTaskViewPayload.Request.TYPE, dev.agaminggod.arenaagents.control.LiveTaskViewPayload.Request.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.agaminggod.arenaagents.control.LiveTaskViewPayload.Snapshot.TYPE, dev.agaminggod.arenaagents.control.LiveTaskViewPayload.Snapshot.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(dev.agaminggod.arenaagents.control.LiveTaskViewPayload.Request.TYPE,
				(payload, context) -> context.server().execute(() -> LiveTaskViewSync.request(context.player(), payload.agentId())));
		PayloadTypeRegistry.serverboundPlay().register(ScenarioLaunchPayload.TYPE, ScenarioLaunchPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ScenarioCancelPayload.TYPE, ScenarioCancelPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AgentControlSnapshotPayload.TYPE, AgentControlSnapshotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(dev.agaminggod.arenaagents.control.DirectorTakePlaybackPayload.TYPE, dev.agaminggod.arenaagents.control.DirectorTakePlaybackPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Request.TYPE, dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Result.TYPE, dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Result.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Request.TYPE, (payload, context) -> context.server().execute(() -> DirectorScriptGeneration.request(context.player(), payload)));
        ServerTickEvents.END_SERVER_TICK.register(DirectorScriptGeneration::tick);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(DirectorScriptGeneration::release);
		PayloadTypeRegistry.serverboundPlay().register(DirectorEditorPayload.Request.TYPE, DirectorEditorPayload.Request.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DirectorEditorPayload.Snapshot.TYPE, DirectorEditorPayload.Snapshot.CODEC);
		if (!ServerPlayNetworking.registerGlobalReceiver(DirectorEditorPayload.Request.TYPE,
				(payload, context) -> context.server().execute(() -> {
					if (ServerPlayNetworking.canSend(context.player(), DirectorEditorPayload.Snapshot.TYPE))
						ServerPlayNetworking.send(context.player(), DirectorScriptEditor.execute(context.player(), payload));
				}))) throw new IllegalStateException("Director editor receiver is already registered");
		PayloadTypeRegistry.clientboundPlay().register(DirectorSnapshotPayload.TYPE, DirectorSnapshotPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DirectorCommandRequestPayload.TYPE, DirectorCommandRequestPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DirectorCommandResultPayload.TYPE, DirectorCommandResultPayload.CODEC);
		if (!ServerPlayNetworking.registerGlobalReceiver(DirectorCommandRequestPayload.TYPE,
				(payload, context) -> context.server().execute(() -> {
					if (!ServerPlayNetworking.canSend(context.player(), DirectorCommandResultPayload.TYPE)) return;
					var result = DirectorCommands.execute(payload, context.server().getCommands().getDispatcher(), context.player().createCommandSourceStack());
					ServerPlayNetworking.send(context.player(), result);
				}))) throw new IllegalStateException("Director command receiver is already registered");
		PayloadTypeRegistry.clientboundPlay().register(
				ArenaSpectatorSnapshotPayload.TYPE,
				ArenaSpectatorSnapshotPayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
				ScenarioBuildProgressPayload.TYPE,
				ScenarioBuildProgressPayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
				ScenarioBuildProgressClearPayload.TYPE,
				ScenarioBuildProgressClearPayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(
				ArenaSpectatorClearPayload.TYPE,
				ArenaSpectatorClearPayload.CODEC
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
					executeAuthorizedControlAction(
							GoalControl.mayControl(context.player().createCommandSourceStack()),
							() -> {
								try {
									ScenarioRuntimeService.launch(context.player(), payload.request());
									sendCurrentBuildProgress(context.player());
								} catch (RuntimeException exception) {
									publishRejectedBuild(context.player(), payload, exception);
								}
							},
							() -> publishRejectedBuild(
									context.player(), payload,
									new IllegalStateException("Operator permission is required to launch an arena")
							)
					);
				})
		);
		if (!scenarioReceiverRegistered) {
			throw new IllegalStateException("Arena Agents scenario launch receiver is already registered");
		}
		boolean scenarioCancelReceiverRegistered = ServerPlayNetworking.registerGlobalReceiver(
				ScenarioCancelPayload.TYPE,
				(payload, context) -> context.server().execute(() -> executeAuthorizedControlAction(
						GoalControl.mayControl(context.player().createCommandSourceStack()),
						() -> {
							try {
								ScenarioRuntimeService.cancel(context.player(), payload.buildId());
								sendCurrentBuildProgress(context.player());
							} catch (RuntimeException exception) {
								context.player().sendSystemMessage(Component.literal(safeMessage(exception)));
								sendCurrentBuildProgress(context.player());
							}
						},
						() -> publishControlDenial(context.player(),
								"Operator permission is required to cancel an arena")
				))
		);
		if (!scenarioCancelReceiverRegistered) {
			throw new IllegalStateException("Arena Agents scenario cancel receiver is already registered");
		}
		ServerTickEvents.END_SERVER_TICK.register(AgentControlSync::publishSpectatorSnapshot);
		ServerTickEvents.END_SERVER_TICK.register(AgentControlSync::publishBuildProgress);
		registered = true;
	}

	private static void publishControlDenial(ServerPlayer player, String message) {
		player.sendSystemMessage(Component.literal(message));
		sendSnapshot(player);
	}

	private static void sendCurrentBuildProgress(ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, ScenarioBuildProgressPayload.TYPE)) return;
		ScenarioRuntimeService.buildProgress(player.level().getServer()).ifPresent(progress -> {
			try {
				ServerPlayNetworking.send(player, ScenarioBuildProgressPayload.fromProgress(progress));
			} catch (RuntimeException exception) {
				LOGGER.warn("Could not send initial arena coordinates to {}", player.getScoreboardName(), exception);
			}
		});
	}

	private static void publishRejectedBuild(
			ServerPlayer player,
			ScenarioLaunchPayload payload,
			RuntimeException exception
	) {
		if (!ServerPlayNetworking.canSend(player, ScenarioBuildProgressPayload.TYPE)) return;
		String title;
		try {
			title = ScenarioPresets.require(payload.request().scenarioId()).title();
		} catch (RuntimeException invalidScenario) {
			title = payload.request().scenarioId();
		}
		BlockPos origin = player.blockPosition();
		String detail = compactDetail("Arena launch rejected: " + safeMessage(exception));
		ScenarioBuildProgress rejected = ScenarioBuildProgress.rejected(
				"rejected-" + UUID.randomUUID(), title,
				origin.getX(), origin.getY(), origin.getZ(), detail);
		try {
			ServerPlayNetworking.send(player, ScenarioBuildProgressPayload.fromProgress(rejected));
			rememberRejectedBuild(player.level().getServer(), player.getUUID(), rejected);
		} catch (RuntimeException sendFailure) {
			LOGGER.warn("Could not send rejected arena status to {}", player.getScoreboardName(), sendFailure);
		}
	}

	private static synchronized void publishBuildProgress(MinecraftServer server) {
		Optional<ScenarioBuildProgress> current = ScenarioRuntimeService.buildProgress(server);
		SpectatorPublication publication = SPECTATOR_PUBLICATIONS.computeIfAbsent(
				server, ignored -> new SpectatorPublication());
		if (current.isEmpty()) {
			clearBuildProgress(server, publication);
			return;
		}
		ScenarioBuildProgress progress = current.orElseThrow();
		if (publication.buildExpired(progress, publication.currentTick())) {
			clearBuildProgress(server, publication);
			return;
		}
		Set<UUID> connectedPlayers = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			connectedPlayers.add(player.getUUID());
			if (!ServerPlayNetworking.canSend(player, ScenarioBuildProgressPayload.TYPE)) continue;
			ScenarioBuildProgress selected = publication.directBuild(player.getUUID(), publication.currentTick())
					.orElse(progress);
			ScenarioBuildProgress previous = publication.lastBuildByPlayer.get(player.getUUID());
			if (previous != null && previous.buildId().equals(selected.buildId())
					&& previous.revision() >= selected.revision()) continue;
			try {
				ServerPlayNetworking.send(player, ScenarioBuildProgressPayload.fromProgress(selected));
				publication.rememberBuild(player.getUUID(), selected);
			} catch (RuntimeException exception) {
				LOGGER.warn("Could not send Arena Agents build progress to {}", player.getScoreboardName(), exception);
			}
		}
		publication.lastBuildByPlayer.keySet().retainAll(connectedPlayers);
		publication.directBuildTerminalTicks.keySet().retainAll(connectedPlayers);
	}

	private static void clearBuildProgress(MinecraftServer server, SpectatorPublication publication) {
		if (publication.lastBuildByPlayer.isEmpty()) return;
		Set<UUID> connectedPlayers = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			connectedPlayers.add(player.getUUID());
			if (publication.directBuild(player.getUUID(), publication.currentTick()).isPresent()) continue;
			if (!publication.lastBuildByPlayer.containsKey(player.getUUID())
					|| !ServerPlayNetworking.canSend(player, ScenarioBuildProgressClearPayload.TYPE)) continue;
			try {
				ServerPlayNetworking.send(player, ScenarioBuildProgressClearPayload.INSTANCE);
				publication.forgetBuild(player.getUUID());
			} catch (RuntimeException exception) {
				LOGGER.warn("Could not clear Arena Agents build progress for {}",
						player.getScoreboardName(), exception);
			}
		}
		publication.lastBuildByPlayer.keySet().retainAll(connectedPlayers);
		publication.directBuildTerminalTicks.keySet().retainAll(connectedPlayers);
	}

	private static synchronized void rememberRejectedBuild(
			MinecraftServer server,
			UUID playerId,
			ScenarioBuildProgress rejected
	) {
		SPECTATOR_PUBLICATIONS.computeIfAbsent(server, ignored -> new SpectatorPublication())
				.rememberDirectBuild(playerId, rejected);
	}

	private static synchronized void publishSpectatorSnapshot(MinecraftServer server) {
		SpectatorPublication publication = SPECTATOR_PUBLICATIONS.computeIfAbsent(
				server,
				ignored -> new SpectatorPublication()
		);
		long currentTick = publication.nextTick();
		if (!publication.cadence.due(currentTick)) return;
		Set<UUID> connectedPlayers = new HashSet<>();
		List<ServerPlayer> recipients = new ArrayList<>();
		boolean hasClearRecipient = false;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			connectedPlayers.add(player.getUUID());
			if (ServerPlayNetworking.canSend(player, ArenaSpectatorSnapshotPayload.TYPE)) recipients.add(player);
			if (publication.lastByPlayer.containsKey(player.getUUID())
					&& ServerPlayNetworking.canSend(player, ArenaSpectatorClearPayload.TYPE)) {
				hasClearRecipient = true;
			}
		}
		publication.lastByPlayer.keySet().retainAll(connectedPlayers);
		if (recipients.isEmpty() && !hasClearRecipient) return;
		Optional<ArenaSpectatorSnapshot.PublicView> view = ScenarioRuntimeService.spectatorView(server);
		Optional<ArenaSpectatorSnapshot.PublicView> retainedView = ArenaSpectatorSnapshot.retainPublication(
				Optional.ofNullable(publication.retainedView),
				view
		);
		publication.retainedView = retainedView.orElse(null);
		if (retainedView.isEmpty()) {
			clearSpectatorView(server, publication);
			publication.cadence.markPublished(currentTick);
			return;
		}
		if (publication.spectatorExpired(publication.retainedView, currentTick)) {
			clearSpectatorView(server, publication);
			publication.retainedView = null;
			publication.cadence.markPublished(currentTick);
			return;
		}
		if (recipients.isEmpty()) {
			publication.cadence.markPublished(currentTick);
			return;
		}
		ArenaSpectatorSnapshot.PublicView publicView = publication.retainedView;
		if (!publicView.runId().equals(publication.runId)) {
			publication.runId = publicView.runId();
			publication.lastByPlayer.clear();
		}
		ArenaSpectatorSnapshot snapshot = ArenaSpectatorSnapshot.fromPublicView(
				publication.nextRevision(),
				publicView
		);
		for (ServerPlayer player : recipients) {
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
		publication.cadence.markPublished(currentTick);
	}

	private static void clearSpectatorView(MinecraftServer server, SpectatorPublication publication) {
		Set<UUID> connectedPlayers = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			connectedPlayers.add(player.getUUID());
			if (!publication.lastByPlayer.containsKey(player.getUUID())
					|| !ServerPlayNetworking.canSend(player, ArenaSpectatorClearPayload.TYPE)) continue;
			try {
				ServerPlayNetworking.send(player, ArenaSpectatorClearPayload.INSTANCE);
				publication.lastByPlayer.remove(player.getUUID());
			} catch (RuntimeException exception) {
				LOGGER.warn("Could not clear Arena Agents spectator view for {}",
						player.getScoreboardName(), exception);
			}
		}
		publication.lastByPlayer.keySet().retainAll(connectedPlayers);
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}

	private static String compactDetail(String value) {
		String compact = value.replace('\n', ' ').replace('\r', ' ').strip();
		return compact.length() <= ScenarioBuildProgress.MAX_DETAIL_LENGTH
				? compact : compact.substring(0, ScenarioBuildProgress.MAX_DETAIL_LENGTH - 3) + "...";
	}

	public static void sendSnapshot(ServerPlayer player) {
		try {
			var server = player.level().getServer();
			if (ServerPlayNetworking.canSend(player, DirectorSnapshotPayload.TYPE)) {
				var actors = SkitActors.records(server).stream().map(actor -> new DirectorSnapshotPayload.Actor(
						actor.agentId().toString(), actor.name(), dev.agaminggod.arenaagents.agent.AgentIdentity.playerName(actor.agentId(), actor.profile()),
						actor.appearance(), actor.dead(), SkitActors.find(server, actor.agentId()).filter(ServerPlayer::isAlive).isPresent(),
						dev.agaminggod.arenaagents.server.voice.VoiceDirector.status(server, actor.agentId()),
						dev.agaminggod.arenaagents.server.voice.VoiceDirectorSavedData.get(server).profile(actor.agentId()))).toList();
				ServerPlayNetworking.send(player, new DirectorSnapshotPayload(SkitModeRuntime.enabled(server), GoalControl.mayControl(player.createCommandSourceStack()), actors));
			}
			boolean canControl = GoalControl.mayControl(player.createCommandSourceStack());
			CodexAgentManager manager = CodexAgentManager.get(player.level().getServer());
			AgentControlSnapshot snapshot = controlSnapshot(
					canControl,
					CodexAgentServerRuntime.automationAvailable(player.level().getServer()),
					CodexAgentServerRuntime.automationStatus(player.level().getServer()),
					nextControlRevision(player.level().getServer()),
					manager.records(),
					manager.groups().stream()
							.map(group -> new AgentControlGroup(
									group.name(),
									group.memberIds().stream().map(Object::toString).toList()
							))
							.toList(),
					CodexAgentServerRuntime.modelCatalog(player.level().getServer()),
					manager::findAgentPlayer
			);
			if (ServerPlayNetworking.canSend(player, AgentControlSnapshotPayload.TYPE)) {
				ServerPlayNetworking.send(player, AgentControlSnapshotPayload.fromSnapshot(snapshot));
			}
		} catch (RuntimeException exception) {
			LOGGER.warn("Could not send Arena Agents control snapshot to {}", player.getScoreboardName(), exception);
		}
	}

	static AgentControlSnapshot controlSnapshot(
			boolean canControl, boolean automationAvailable, String automationStatus, long revision,
			List<AgentRecord> records, List<AgentControlGroup> groups, List<AgentControlModelOption> catalog,
			Function<AgentId, Optional<ServerPlayer>> findPlayer
	) {
		return AgentControlSnapshot.fromRecords(canControl, automationAvailable, automationStatus, revision,
				records, groups, catalog, id -> findPlayer.apply(id).filter(ServerPlayer::isAlive).isPresent());
	}

	private static synchronized long nextControlRevision(MinecraftServer server) {
		return SPECTATOR_PUBLICATIONS.computeIfAbsent(server, ignored -> new SpectatorPublication())
				.nextControlRevision(System.currentTimeMillis());
	}

	static boolean executeAuthorizedControlAction(boolean canControl, Runnable action) {
		return executeAuthorizedControlAction(canControl, action, () -> { });
	}

	static boolean executeAuthorizedControlAction(boolean canControl, Runnable action, Runnable denied) {
		Objects.requireNonNull(action, "action must not be null");
		Objects.requireNonNull(denied, "denied action must not be null");
		if (!canControl) {
			denied.run();
			return false;
		}
		action.run();
		return true;
	}

	static final class SpectatorPublication {
		private final ArenaSpectatorSnapshot.PublicationCadence cadence =
				new ArenaSpectatorSnapshot.PublicationCadence(4);
		private final Map<UUID, ArenaSpectatorSnapshot> lastByPlayer = new LinkedHashMap<>();
		private final Map<UUID, ScenarioBuildProgress> lastBuildByPlayer = new LinkedHashMap<>();
		private final Map<UUID, Long> directBuildTerminalTicks = new LinkedHashMap<>();
		private long serverTick;
		private long revision;
		private long controlRevision;
		private long buildTerminalTick = -1L;
		private String terminalBuildId = "";
		private long spectatorTerminalTick = -1L;
		private String terminalSpectatorRunId = "";
		private String runId = "";
		private ArenaSpectatorSnapshot.PublicView retainedView;

		void rememberBuild(UUID playerId, ScenarioBuildProgress progress) {
			lastBuildByPlayer.put(Objects.requireNonNull(playerId, "playerId must not be null"),
					Objects.requireNonNull(progress, "progress must not be null"));
		}

		void rememberDirectBuild(UUID playerId, ScenarioBuildProgress progress) {
			rememberDirectBuild(playerId, progress, currentTick());
		}

		void rememberDirectBuild(UUID playerId, ScenarioBuildProgress progress, long publishedTick) {
			if (publishedTick < 0L) throw new IllegalArgumentException("publishedTick must not be negative");
			rememberBuild(playerId, progress);
			directBuildTerminalTicks.put(playerId, publishedTick);
		}

		Optional<ScenarioBuildProgress> directBuild(UUID playerId, long currentTick) {
			Long firstTerminalTick = directBuildTerminalTicks.get(playerId);
			if (firstTerminalTick == null) return Optional.empty();
			if (ScenarioPresentationExpiry.expired(
					firstTerminalTick, currentTick, ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS)) {
				directBuildTerminalTicks.remove(playerId);
				return Optional.empty();
			}
			return Optional.ofNullable(lastBuildByPlayer.get(playerId));
		}

		void forgetBuild(UUID playerId) {
			lastBuildByPlayer.remove(playerId);
			directBuildTerminalTicks.remove(playerId);
		}

		private long nextTick() {
			return ++serverTick;
		}

		private long currentTick() {
			return serverTick;
		}

		private long nextRevision() {
			return ++revision;
		}

		private long nextControlRevision(long wallClock) {
			controlRevision = Math.max(wallClock, controlRevision + 1L);
			return controlRevision;
		}

		private boolean buildExpired(ScenarioBuildProgress progress, long currentTick) {
			if (!progress.terminal()) {
				terminalBuildId = "";
				buildTerminalTick = -1L;
				return false;
			}
			if (!progress.buildId().equals(terminalBuildId)) {
				terminalBuildId = progress.buildId();
				buildTerminalTick = currentTick;
			}
			return ScenarioPresentationExpiry.expired(
					buildTerminalTick, currentTick, ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS);
		}

		private boolean spectatorExpired(ArenaSpectatorSnapshot.PublicView view, long currentTick) {
			if (!view.terminal()) {
				terminalSpectatorRunId = "";
				spectatorTerminalTick = -1L;
				return false;
			}
			if (!view.runId().equals(terminalSpectatorRunId)) {
				terminalSpectatorRunId = view.runId();
				spectatorTerminalTick = currentTick;
			}
			return ScenarioPresentationExpiry.expired(
					spectatorTerminalTick, currentTick, ScenarioPresentationExpiry.SPECTATOR_TERMINAL_TTL_TICKS);
		}
	}
}
