package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import dev.agaminggod.arenaagents.pov.OperatorAction;
import dev.agaminggod.arenaagents.pov.OperatorActionPayload;
import dev.agaminggod.arenaagents.pov.OperatorBodyController;
import dev.agaminggod.arenaagents.pov.OperatorBodyControllers;
import dev.agaminggod.arenaagents.pov.OperatorInputPayload;
import dev.agaminggod.arenaagents.pov.OperatorTextPayload;
import dev.agaminggod.arenaagents.pov.OperatorCreativeSlotPayload;
import dev.agaminggod.arenaagents.pov.PovDeath;
import dev.agaminggod.arenaagents.pov.PovMode;
import dev.agaminggod.arenaagents.pov.PovStopPayload;
import dev.agaminggod.arenaagents.pov.PovViewAnchors;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.CodexAgentServerRuntime;
import dev.agaminggod.arenaagents.server.DirectorTakeRuntime;
import dev.agaminggod.arenaagents.server.GoalControl;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import dev.agaminggod.arenaagents.server.SkitModeRuntime;
import dev.agaminggod.arenaagents.server.conversation.ConversationEvent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server side of /spectate and /takeover. Sessions are keyed by operator; takeovers additionally
 * hold an {@link AgentControlReservations} entry for the agent. Ticked once per server tick after
 * the bridge and before agent input arbitration, so operator input wins the same tick it arrives.
 * The view is published from {@link #endTick}, after physics, so it shows the tick the input produced.
 */
public final class PovSessionRuntime {
	private static final Logger LOGGER = LoggerFactory.getLogger(PovSessionRuntime.class);
	private static final Map<MinecraftServer, State> STATES = new ConcurrentHashMap<>();
	// A random base keeps a stale client from matching a session id issued before a restart.
	private static final AtomicLong NEXT_SESSION_ID = new AtomicLong(ThreadLocalRandom.current().nextLong(1L, 1L << 40));
	static final String REPORT_PREFIX = "Operator takeover report: ";
	static final int MAX_REPORT_CODE_POINTS = ConversationEvent.MAX_TEXT_CODE_POINTS;
	private static final int SAMPLE_INTERVAL_TICKS = 20;
	private static final int REPORT_RETRY_TICKS = 20;
	private static final long REPORT_RETRY_WINDOW_TICKS = 20L * 60L * 5L;
	private static final double AGENT_LOOK_RESET_JUMP_BLOCKS = 8.0D;
	private static boolean eventsRegistered;

	private PovSessionRuntime() {
	}

	private static final class State {
		private final Map<UUID, PovSession> sessions = new LinkedHashMap<>();
		private final Map<AgentId, PendingReport> reports = new LinkedHashMap<>();
		/** Takeovers that ended while the agent was dead and it should continue its goal after respawn. */
		private final Map<AgentId, Long> respawnResumes = new LinkedHashMap<>();
	}

	private record PendingReport(UUID operatorId, AgentId agentId, String text, long expiresAtTick) {
	}

	/** Pure outcome of a start request, evaluated before anything changes. */
	enum StartDecision {
		START,
		REPLACE,
		ALREADY_ACTIVE,
		REJECT_RESERVED,
		REJECT_SKIT
	}

	record ActiveView(AgentId agentId, PovMode mode) {
		ActiveView {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(mode, "mode must not be null");
		}
	}

	/**
	 * A new session replaces the operator's current one; asking for the same view again is a no-op.
	 * Takeovers are exclusive per agent and never share an agent with skit playback.
	 */
	static StartDecision planStart(UUID operatorId, AgentId agentId, PovMode mode, Optional<ActiveView> current,
			Optional<UUID> takeoverOwner, boolean skitClaimsAgent) {
		Objects.requireNonNull(operatorId, "operatorId must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(mode, "mode must not be null");
		if (current.filter(view -> view.agentId().equals(agentId) && view.mode() == mode).isPresent()) {
			return StartDecision.ALREADY_ACTIVE;
		}
		if (mode == PovMode.TAKEOVER) {
			if (takeoverOwner.filter(owner -> !owner.equals(operatorId)).isPresent()) return StartDecision.REJECT_RESERVED;
			if (skitClaimsAgent) return StartDecision.REJECT_SKIT;
		}
		return current.isPresent() ? StartDecision.REPLACE : StartDecision.START;
	}

	/** Prefixes and bounds the model-facing report to the conversation text limit. */
	static String report(String description) {
		String text = REPORT_PREFIX + Objects.requireNonNull(description, "description must not be null").strip();
		if (text.codePointCount(0, text.length()) <= MAX_REPORT_CODE_POINTS) return text;
		return text.substring(0, text.offsetByCodePoints(0, MAX_REPORT_CODE_POINTS - 3)) + "...";
	}

	public static PovSession start(ServerPlayer operator, AgentId agentId, PovMode mode) {
		AgentRecord record = CodexAgentManager.get(operator.level().getServer()).registry().require(agentId);
		return start(operator, agentId, mode, record.profile().userName().orElse(agentId.shortValue()));
	}

	/** {@code selector} is how the operator named the agent, reused in follow-up hints. */
	public static PovSession start(ServerPlayer operator, AgentId agentId, PovMode mode, String selector) {
		Objects.requireNonNull(operator, "operator must not be null");
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(mode, "mode must not be null");
		MinecraftServer server = operator.level().getServer();
		CodexAgentManager manager = CodexAgentManager.get(server);
		AgentRecord record = manager.registry().require(agentId);
		String agentName = manager.displayName(record);
		State state = STATES.computeIfAbsent(server, ignored -> new State());
		PovSession current = state.sessions.get(operator.getUUID());
		Optional<UUID> owner = AgentControlReservations.owner(server, agentId);
		StartDecision decision = planStart(operator.getUUID(), agentId, mode,
				Optional.ofNullable(current).map(session -> new ActiveView(session.agentId(), session.mode())),
				owner, mode == PovMode.TAKEOVER && skitClaims(server, agentId));
		switch (decision) {
			case ALREADY_ACTIVE -> {
				resendFull(server, current, operator);
				return current;
			}
			case REJECT_RESERVED -> throw new AgentDomainException(AgentControlReservations.RESERVED_CODE,
					playerName(server, owner.orElseThrow()) + " is already controlling " + agentName
							+ ". Only one operator can take over an agent at a time");
			case REJECT_SKIT -> throw new AgentDomainException("SKIT_AGENT_RESERVED",
					agentName + " is performing a skit or Director take. Stop it before taking over");
			case START, REPLACE -> { }
		}
		ServerPlayer agent = findAgent(server, record).orElse(null);
		if (agent != null && agent.level() != operator.level()) {
			throw new AgentDomainException("POV_DIMENSION_MISMATCH", agentName + " is in "
					+ agent.level().dimension().identifier() + ". Go to the same dimension, then run the command again");
		}
		if (current != null) end(server, state, current, PovExitReason.REPLACED, null, operator);

		long sessionId = NEXT_SESSION_ID.getAndIncrement();
		OperatorBodyController controller = mode == PovMode.TAKEOVER ? OperatorBodyControllers.create(server, agentId) : null;
		UUID agentPlayerUuid = OfflineAgentPlayers.offlineUuid(agentId, record.profile());
		PovSession session = new PovSession(sessionId, operator.getUUID(), agentId, agentPlayerUuid, agentName,
				selector == null || selector.isBlank() ? agentId.shortValue() : selector, mode,
				operator.level().dimension(), operator.position(), System.currentTimeMillis(),
				new PovStatePublisher(sessionId, mode, agentPlayerUuid, agentName), controller,
				record.state() == AgentLifecycleState.DEAD);
		if (mode == PovMode.TAKEOVER) beginTakeover(server, manager, state, session, operator, record);
		state.sessions.put(operator.getUUID(), session);
		if (mode == PovMode.TAKEOVER) PovMessageRelay.start(agentPlayerUuid, operator.getUUID(), agentName);
		if (mode == PovMode.TAKEOVER && agent != null) PovUiForwarder.showAgentRecipeBook(operator, agent);
		if (agent != null) {
			session.observeAgent(agent, agent.position(), AGENT_LOOK_RESET_JUMP_BLOCKS);
			if (session.takeover()) sampleAgent(session, agent);
			anchor(session, operator, agent);
		}
		session.publisher().sendFull(operator, agent, death(record, mode), session.lookResetSeq(), inputSequence(session));
		return session;
	}

	private static void beginTakeover(MinecraftServer server, CodexAgentManager manager, State state, PovSession session,
			ServerPlayer operator, AgentRecord record) {
		AgentId agentId = session.agentId();
		AgentControlReservations.reserve(server, agentId, operator.getUUID());
		boolean stopped = false;
		// A previous takeover's "resume after respawn" carries over while the goal is unchanged.
		Long pendingRespawnResume = state.respawnResumes.remove(agentId);
		try {
			PovSession.LifecyclePlan plan = PovSession.LifecyclePlan.forTakeover(record.state(), record.resumeAfterRespawn());
			if (plan.stopOnStart()) {
				try {
					// The registry call bypasses the reservation gate on purpose; stop cancels the turn and actions.
					manager.registry().stop(agentId, System.currentTimeMillis());
					stopped = true;
				} catch (AgentDomainException unstoppable) {
					LOGGER.info("Took over agent {} without stopping it: {}", agentId, unstoppable.getMessage());
				}
			}
			if (!stopped && dev.agaminggod.arenaagents.agent.AgentLifecycleReducer.isDetachedActionState(record.state())) {
				// An idle or completed agent may be mid-way through a detached action; the operator owns the body now.
				manager.cancelDetachedAction(agentId, "An operator took over the body");
			}
			long revision = manager.registry().require(agentId).goalRevision();
			session.initialLifecycle(stopped && plan.resumeOnExit()
					|| pendingRespawnResume != null && pendingRespawnResume == record.goalRevision(), revision);
			session.controller().begin(operator);
		} catch (RuntimeException failure) {
			AgentControlReservations.release(server, agentId, operator.getUUID());
			if (stopped) resumeQuietly(manager, agentId);
			if (pendingRespawnResume != null) state.respawnResumes.put(agentId, pendingRespawnResume);
			throw failure;
		}
	}

	/** Ends the operator's session, if any. */
	public static boolean exit(ServerPlayer operator, PovExitReason reason) {
		Objects.requireNonNull(operator, "operator must not be null");
		MinecraftServer server = operator.level().getServer();
		State state = STATES.get(server);
		PovSession session = state == null ? null : state.sessions.get(operator.getUUID());
		if (session == null) return false;
		end(server, state, session, Objects.requireNonNull(reason, "reason must not be null"), null, operator);
		return true;
	}

	public static Optional<PovSession> session(ServerPlayer operator) {
		State state = STATES.get(operator.level().getServer());
		return state == null ? Optional.empty() : Optional.ofNullable(state.sessions.get(operator.getUUID()));
	}

	public static void onPlayerDisconnect(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		State state = STATES.get(server);
		PovSession session = state == null ? null : state.sessions.get(player.getUUID());
		if (session != null) end(server, state, session, PovExitReason.OPERATOR_DISCONNECTED, null, player);
	}

	/** Called before an agent record and its player are deleted, while the body can still be released. */
	public static void onAgentRemoved(MinecraftServer server, AgentId agentId) {
		endSessionsFor(server, agentId, PovExitReason.AGENT_REMOVED, false);
	}

	/** A skit or scenario takes the agent; only the exclusive takeover yields, spectators keep watching. */
	public static void onAgentClaimed(MinecraftServer server, AgentId agentId, PovExitReason reason) {
		endSessionsFor(server, agentId, reason, true);
	}

	private static void endSessionsFor(MinecraftServer server, AgentId agentId, PovExitReason reason, boolean takeoverOnly) {
		State state = server == null ? null : STATES.get(server);
		if (state == null) return;
		for (PovSession session : List.copyOf(state.sessions.values())) {
			if (!session.agentId().equals(agentId) || takeoverOnly && !session.takeover()) continue;
			end(server, state, session, reason, null, server.getPlayerList().getPlayer(session.operatorId()));
		}
		if (reason == PovExitReason.AGENT_REMOVED) {
			state.reports.remove(agentId);
			state.respawnResumes.remove(agentId);
		}
	}

	/** Server stop: every session ends and taken-over agents resume, so a restart continues their goals. */
	public static void release(MinecraftServer server) {
		State state = STATES.get(server);
		try {
			if (state != null) {
				for (PovSession session : List.copyOf(state.sessions.values())) {
					try {
						end(server, state, session, PovExitReason.SERVER_STOPPING, null,
								server.getPlayerList().getPlayer(session.operatorId()));
					} catch (RuntimeException failure) {
						LOGGER.warn("Could not end POV session {} during shutdown", session.id(), failure);
					}
				}
			}
		} finally {
			STATES.remove(server);
			PovMessageRelay.clearAll();
			AgentControlReservations.releaseAll(server);
		}
	}

	public static void tick(MinecraftServer server) {
		State state = STATES.get(server);
		if (state == null) return;
		if (!state.sessions.isEmpty()) {
			CodexAgentManager manager = CodexAgentManager.get(server);
			for (PovSession session : List.copyOf(state.sessions.values())) {
				if (state.sessions.get(session.operatorId()) != session) continue;
				try {
					tickSession(server, manager, state, session);
				} catch (RuntimeException failure) {
					LOGGER.warn("Stopped POV session {} for agent {} after a failure", session.id(), session.agentId(), failure);
					try {
						end(server, state, session, PovExitReason.FAILED, null,
								server.getPlayerList().getPlayer(session.operatorId()));
					} catch (RuntimeException cleanupFailure) {
						LOGGER.warn("Could not clean up failed POV session {}", session.id(), cleanupFailure);
					}
				}
			}
		}
		if (!state.respawnResumes.isEmpty()) tickRespawnResumes(server, state);
		if (!state.reports.isEmpty()) tickReports(server, state);
	}

	private static void tickSession(MinecraftServer server, CodexAgentManager manager, State state, PovSession session) {
		ServerPlayer operator = server.getPlayerList().getPlayer(session.operatorId());
		boolean operatorOnline = operator != null && !operator.hasDisconnected();
		AgentRegistry registry = manager.registry();
		AgentRecord record = registry.contains(session.agentId()) ? registry.require(session.agentId()) : null;
		ServerPlayer agent = record == null ? null : findAgent(server, record).orElse(null);
		PovExitReason.Observation observation = new PovExitReason.Observation(
				session.mode(),
				operatorOnline,
				operatorOnline && operator.isAlive(),
				operatorOnline && GoalControl.mayControl(operator.createCommandSourceStack()),
				operatorOnline && !operator.level().dimension().equals(session.operatorDimension()),
				operatorOnline ? session.operatorMoved(operator.position()) : 0.0D,
				record != null,
				agent != null,
				operatorOnline && agent != null && agent.level() != operator.level(),
				session.takeover() && skitClaims(server, session.agentId()),
				session.damage().total()
		);
		Optional<PovExitReason> exit = PovExitReason.select(observation);
		if (exit.isPresent()) {
			String message = exit.get() == PovExitReason.AGENT_DIMENSION_CHANGED
					? PovExitReason.agentDimensionMessage(agent.level().dimension().identifier().toString(),
							session.mode(), session.agentSelector())
					: null;
			end(server, state, session, exit.get(), message, operatorOnline ? operator : null);
			return;
		}
		if (session.takeover()) {
			// Backstop: nothing may wake the model while the operator owns the body.
			if (record.state().isActive()) {
				record = registry.stop(session.agentId(), System.currentTimeMillis()).after();
				session.stoppedByBackstop(record.goalRevision());
			}
			AgentDeathSnapshot death = record.deathSnapshot().orElse(null);
			session.observeLifecycle(record.state(), death == null ? null : death.dimensionId(),
					death == null ? null : death.cause());
		}
		if (agent != null) {
			session.observeAgent(agent, agent.position(), AGENT_LOOK_RESET_JUMP_BLOCKS);
			if (session.takeover() && (session.startSnapshot().isEmpty()
					|| server.getTickCount() % SAMPLE_INTERVAL_TICKS == 0)) sampleAgent(session, agent);
			anchor(session, operator, agent);
		} else if (session.anchored()) {
			PovViewAnchors.clear(session.operatorId());
			session.unanchor();
			operator.level().getChunkSource().move(operator);
		}
		if (session.takeover()) session.controller().tick();
	}

	/**
	 * Publishes every session's view after the tick's physics. Publishing at the start of the tick (as the session
	 * tick used to) showed the operator the previous tick's position, one tick behind its own input.
	 */
	public static void endTick(MinecraftServer server) {
		PovMessageRelay.flush(server);
		State state = STATES.get(server);
		if (state == null || state.sessions.isEmpty()) return;
		CodexAgentManager manager = CodexAgentManager.get(server);
		for (PovSession session : List.copyOf(state.sessions.values())) {
			if (state.sessions.get(session.operatorId()) != session) continue;
			ServerPlayer operator = server.getPlayerList().getPlayer(session.operatorId());
			if (operator == null || operator.hasDisconnected()) continue;
			try {
				AgentRegistry registry = manager.registry();
				AgentRecord record = registry.contains(session.agentId()) ? registry.require(session.agentId()) : null;
				ServerPlayer agent = record == null ? null : findAgent(server, record).orElse(null);
				session.publisher().tick(operator, agent, death(record, session.mode()), session.lookResetSeq(),
						inputSequence(session));
				if (agent != null) PovUiForwarder.refreshMerchant(operator, agent, server.getTickCount());
			} catch (RuntimeException failure) {
				// Same policy as a failing session tick: end the session instead of warning every tick.
				LOGGER.warn("Stopped POV session {} for agent {} after a publish failure", session.id(), session.agentId(), failure);
				try {
					end(server, state, session, PovExitReason.FAILED, null, operator);
				} catch (RuntimeException cleanupFailure) {
					LOGGER.warn("Could not clean up failed POV session {}", session.id(), cleanupFailure);
				}
			}
		}
	}

	private static int inputSequence(PovSession session) {
		return session.takeover() ? session.controller().lastInputSequence() : 0;
	}

	/** Keeps the view anchored on the live agent; chunk tracking is re-evaluated when its section changes. */
	private static void anchor(PovSession session, ServerPlayer operator, ServerPlayer agent) {
		PovViewAnchors.set(session.operatorId(), agent.getUUID());
		long section = SectionPos.of(agent).asLong();
		boolean moved = !session.anchored() || session.agentSection() != section;
		session.anchor(section);
		if (moved) operator.level().getChunkSource().move(operator);
	}

	private static Optional<PovDeath> death(AgentRecord record, PovMode mode) {
		if (record == null || record.state() != AgentLifecycleState.DEAD) return Optional.empty();
		String cause = record.deathSnapshot().map(AgentDeathSnapshot::cause).orElse("The agent died");
		return Optional.of(new PovDeath(Component.literal(cause), mode == PovMode.TAKEOVER));
	}

	private static void sampleAgent(PovSession session, ServerPlayer agent) {
		try {
			session.captureSnapshot(PovTakeoverSummary.capture(agent));
		} catch (RuntimeException failure) {
			LOGGER.debug("Could not capture takeover summary snapshot for {}", session.agentId(), failure);
		}
		session.pickups().observe(pickupCounts(agent));
	}

	private static Map<String, Integer> pickupCounts(ServerPlayer agent) {
		ServerStatsCounter stats = agent.getStats();
		Map<String, Integer> counts = new HashMap<>();
		for (Item item : BuiltInRegistries.ITEM) {
			int value = stats.getValue(Stats.ITEM_PICKED_UP, item);
			if (value > 0) counts.put(BuiltInRegistries.ITEM.getKey(item).getPath(), value);
		}
		return counts;
	}

	/**
	 * Exit order follows the design: release the body, restore the lifecycle, report to the model,
	 * then hand the operator's view back. Each step runs even if an earlier one fails.
	 */
	private static void end(MinecraftServer server, State state, PovSession session, PovExitReason reason,
			String detail, ServerPlayer operator) {
		if (!state.sessions.remove(session.operatorId(), session)) return;
		PovMessageRelay.stop(session.agentPlayerUuid(), session.operatorId());
		String message = detail == null ? reason.message() : detail;
		boolean online = operator != null && reason != PovExitReason.OPERATOR_DISCONNECTED;
		if (session.takeover()) {
			CodexAgentManager manager = CodexAgentManager.get(server);
			AgentRecord record = manager.registry().contains(session.agentId())
					? manager.registry().require(session.agentId()) : null;
			ServerPlayer agent = record == null ? null : findAgent(server, record).orElse(null);
			step(session, "release the agent body", () -> session.controller().end());
			step(session, "close the agent's container", () -> {
				if (agent != null && agent.containerMenu != agent.inventoryMenu) agent.closeContainer();
			});
			step(session, "release the agent reservation",
					() -> AgentControlReservations.release(server, session.agentId(), session.operatorId()));
			// A removed agent is about to be deleted; resuming or reporting to it would only add noise.
			if (record != null && reason != PovExitReason.AGENT_REMOVED) {
				if (agent != null) step(session, "capture the final summary", () -> sampleAgent(session, agent));
				step(session, "restore the agent lifecycle", () -> restoreLifecycle(manager, state, session, reason));
				if (reason != PovExitReason.SERVER_STOPPING) {
					step(session, "report the takeover", () -> queueReport(server, state, session, operator));
				}
			}
		}
		if (online && session.takeover()) {
			step(session, "restore the operator recipe book", () -> PovUiForwarder.restoreOperatorRecipeBook(operator));
			step(session, "resync the operator inventory", () -> operator.inventoryMenu.sendAllDataToRemote());
		}
		if (online) {
			step(session, "send the stop payload", () -> {
				if (ServerPlayNetworking.canSend(operator, PovStopPayload.TYPE)) {
					ServerPlayNetworking.send(operator, new PovStopPayload(session.id(), message));
				}
			});
		}
		step(session, "clear the view anchor", () -> PovViewAnchors.clear(session.operatorId()));
		// A replacement session re-anchors in the same tick, so skip the round trip through the body's chunks.
		if (online && reason != PovExitReason.REPLACED) {
			step(session, "restore chunk tracking", () -> operator.level().getChunkSource().move(operator));
			// Re-sending the position snaps the client's copy of the body back to the server's.
			if (operator.isAlive()) {
				step(session, "resync the operator body", () -> operator.connection.teleport(
						operator.getX(), operator.getY(), operator.getZ(), operator.getYRot(), operator.getXRot()));
			}
			String prefix = session.takeover() ? "Takeover of " + session.agentName() + " ended: "
					: "Stopped viewing " + session.agentName() + ": ";
			step(session, "notify the operator", () -> operator.sendSystemMessage(Component.literal(prefix + message)));
		}
	}

	private static void restoreLifecycle(CodexAgentManager manager, State state, PovSession session, PovExitReason reason) {
		AgentRecord record = manager.registry().require(session.agentId());
		boolean unfinished = record.currentGoal()
				.filter(goal -> goal.status() != GoalStatus.SATISFIED && goal.status() != GoalStatus.CANCELLED)
				.isPresent();
		switch (PovSession.ExitLifecycle.decide(session.resumeOnExit(), record.state(), unfinished,
				record.goalRevision() == session.expectedRevision())) {
			case RESUME -> {
				// A skit or scenario that claimed the agent now drives it; resuming the model would race that claim.
				if (reason == PovExitReason.CLAIMED_BY_SKIT || reason == PovExitReason.CLAIMED_BY_SCENARIO) return;
				manager.resume(session.agentId().toString());
			}
			case RESUME_AFTER_RESPAWN -> state.respawnResumes.put(session.agentId(), record.goalRevision());
			case NONE -> { }
		}
	}

	/**
	 * Resumes agents whose takeover ended while they were dead, once they respawn paused. A changed
	 * goal revision means someone else stopped, steered or replaced the goal, so the intent is dropped.
	 */
	private static void tickRespawnResumes(MinecraftServer server, State state) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		for (Map.Entry<AgentId, Long> entry : List.copyOf(state.respawnResumes.entrySet())) {
			AgentId agentId = entry.getKey();
			if (AgentControlReservations.isReserved(server, agentId)) continue;
			AgentRecord record = manager.registry().contains(agentId) ? manager.registry().require(agentId) : null;
			if (record != null && record.state() == AgentLifecycleState.DEAD && record.goalRevision() == entry.getValue()) continue;
			state.respawnResumes.remove(agentId);
			if (record == null || record.goalRevision() != entry.getValue() || record.state() != AgentLifecycleState.PAUSED) continue;
			try {
				manager.resume(agentId.toString());
			} catch (RuntimeException failure) {
				LOGGER.info("Agent {} did not resume after its takeover ended: {}", agentId, failure.getMessage());
			}
		}
	}

	private static void queueReport(MinecraftServer server, State state, PovSession session, ServerPlayer operator) {
		long durationMs = Math.max(0L, System.currentTimeMillis() - session.startedAtEpochMs());
		String description = session.startSnapshot()
				.map(start -> PovTakeoverSummary.describe(start, session.latestSnapshot().orElse(start), durationMs,
						session.deaths(), session.respawns(), session.notableEvents()))
				.orElseGet(() -> "An operator held your body for " + Math.max(1L, durationMs / 1000L)
						+ " s while it was not in the world. Deaths: " + session.deaths()
						+ ", respawns: " + session.respawns() + ".");
		PendingReport report = new PendingReport(session.operatorId(), session.agentId(), report(description),
				server.getTickCount() + REPORT_RETRY_WINDOW_TICKS);
		// Delivery needs the coordinator and a live recipient; a dead agent gets it after respawning.
		if (!deliver(server, report, operator)) state.reports.put(session.agentId(), report);
	}

	private static void tickReports(MinecraftServer server, State state) {
		if (server.getTickCount() % REPORT_RETRY_TICKS != 0) return;
		CodexAgentManager manager = CodexAgentManager.get(server);
		for (PendingReport report : List.copyOf(state.reports.values())) {
			if (server.getTickCount() > report.expiresAtTick() || !manager.registry().contains(report.agentId())) {
				state.reports.remove(report.agentId(), report);
				LOGGER.info("Dropped an undelivered takeover report for agent {}", report.agentId());
				continue;
			}
			if (AgentControlReservations.isReserved(server, report.agentId())) continue;
			if (deliver(server, report, server.getPlayerList().getPlayer(report.operatorId()))) {
				state.reports.remove(report.agentId(), report);
			}
		}
	}

	/** Uses the same path as /codex dm, so the model sees an ordinary operator message. */
	private static boolean deliver(MinecraftServer server, PendingReport report, ServerPlayer operator) {
		if (operator == null) return false;
		try {
			CodexAgentServerRuntime.sendDirectMessage(server, operator, report.agentId(), report.text());
			return true;
		} catch (AgentDomainException unavailable) {
			LOGGER.debug("Takeover report for {} is waiting: {}", report.agentId(), unavailable.code());
			return false;
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not deliver the takeover report for agent {}", report.agentId(), failure);
			return false;
		}
	}

	public static void handleInput(ServerPlayer operator, OperatorInputPayload frame) {
		PovSession session = activeTakeover(operator, frame.sessionId());
		if (session != null) session.controller().applyFrame(frame);
	}

	public static void handleCreativeSlot(ServerPlayer operator, OperatorCreativeSlotPayload slot) {
		PovSession session = activeTakeover(operator, slot.sessionId());
		if (session != null) session.controller().applyCreativeSlot(slot);
	}

	public static void handleText(ServerPlayer operator, OperatorTextPayload text) {
		PovSession session = activeTakeover(operator, text.sessionId());
		if (session != null) session.controller().applyText(text);
	}

	/** The takeover session driving this agent player, if any; used to show it the agent's screens. */
	static Optional<PovSession> takeoverOf(ServerPlayer agent) {
		State state = STATES.get(agent.level().getServer());
		if (state == null) return Optional.empty();
		for (PovSession session : state.sessions.values()) {
			if (session.takeover() && session.agentPlayerUuid().equals(agent.getUUID())) return Optional.of(session);
		}
		return Optional.empty();
	}

	public static void handleAction(ServerPlayer operator, OperatorActionPayload action) {
		PovSession session = activeTakeover(operator, action.sessionId());
		if (session == null) return;
		if (action.action() != OperatorAction.RESPAWN) {
			// The agent's own inventory is always clickable server-side; the publisher mirrors it only while the
			// operator has the E screen open, so the client receives container-0 contents to fill that screen.
			if (action.action() == OperatorAction.OPEN_INVENTORY) session.publisher().setInventoryOpen(true);
			if (action.action() == OperatorAction.CLOSE_MENU) {
				session.publisher().setInventoryOpen(false);
				// The creative screen edits the operator's own client inventory view; give it back its real contents.
				operator.inventoryMenu.sendAllDataToRemote();
			}
			session.controller().applyAction(operator, action);
			return;
		}
		CodexAgentManager manager = CodexAgentManager.get(operator.level().getServer());
		if (!manager.registry().contains(session.agentId())
				|| manager.registry().require(session.agentId()).state() != AgentLifecycleState.DEAD) return;
		try {
			// The verified respawn path, never a raw respawn packet. Respawn intent was cleared at start,
			// so the agent comes back paused and stays under the operator's control.
			manager.requestRespawn(session.agentId().toString());
		} catch (AgentDomainException rejected) {
			if (!"RESPAWN_ALREADY_PENDING".equals(rejected.code())) {
				operator.sendSystemMessage(Component.literal("Could not respawn " + session.agentName() + ": " + rejected.getMessage()));
			}
		}
	}

	/** Input is accepted only for the operator's current takeover; permission is re-checked per payload. */
	private static PovSession activeTakeover(ServerPlayer operator, long sessionId) {
		if (operator == null) return null;
		State state = STATES.get(operator.level().getServer());
		PovSession session = state == null ? null : state.sessions.get(operator.getUUID());
		if (session == null || session.id() != sessionId || !session.takeover()) return null;
		return GoalControl.mayControl(operator.createCommandSourceStack()) ? session : null;
	}

	/** Registers the operator body-damage listeners once. */
	public static synchronized void registerEvents() {
		if (eventsRegistered) return;
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			PovSession session = damagedTakeover(entity);
			if (session != null) session.damage().before(healthAndAbsorption(entity));
			return true;
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
			PovSession session = damagedTakeover(entity);
			if (session != null) session.damage().after(healthAndAbsorption(entity));
		});
		eventsRegistered = true;
	}

	private static PovSession damagedTakeover(LivingEntity entity) {
		if (!(entity instanceof ServerPlayer player)) return null;
		State state = STATES.get(player.level().getServer());
		if (state == null) return null;
		PovSession session = state.sessions.get(player.getUUID());
		return session != null && session.takeover() ? session : null;
	}

	private static double healthAndAbsorption(LivingEntity entity) {
		return (double) entity.getHealth() + (double) entity.getAbsorptionAmount();
	}

	private static void resendFull(MinecraftServer server, PovSession session, ServerPlayer operator) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		AgentRecord record = manager.registry().require(session.agentId());
		session.publisher().sendFull(operator, findAgent(server, record).orElse(null), death(record, session.mode()),
				session.lookResetSeq(), inputSequence(session));
	}

	private static Optional<ServerPlayer> findAgent(MinecraftServer server, AgentRecord record) {
		return OfflineAgentPlayers.find(server, record.agentId(), record.profile()).filter(player -> !player.isRemoved());
	}

	/** Skit playback and Director takes own their actors outright; a takeover never shares one. */
	private static boolean skitClaims(MinecraftServer server, AgentId agentId) {
		if (SkitModeRuntime.isPlaying(server, agentId)) return true;
		try {
			DirectorTakeRuntime.requireUnreserved(server, agentId);
			return false;
		} catch (AgentDomainException reserved) {
			return true;
		}
	}

	private static String playerName(MinecraftServer server, UUID playerId) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		return player == null ? "Another operator" : player.getScoreboardName();
	}

	private static void resumeQuietly(CodexAgentManager manager, AgentId agentId) {
		try {
			manager.registry().resume(agentId, System.currentTimeMillis());
		} catch (RuntimeException failure) {
			LOGGER.warn("Could not resume agent {} after a failed takeover start", agentId, failure);
		}
	}

	private static void step(PovSession session, String description, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException failure) {
			LOGGER.warn("POV session {} could not {}", session.id(), description, failure);
		}
	}
}
