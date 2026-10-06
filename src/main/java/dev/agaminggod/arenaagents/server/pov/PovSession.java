package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.pov.OperatorBodyController;
import dev.agaminggod.arenaagents.pov.PovMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One operator's view of one agent. The agent's {@code ServerPlayer} is never stored for use:
 * connected respawns replace that object, so the runtime re-resolves it every tick and this
 * class only keeps an identity token to notice the replacement.
 */
public final class PovSession {
	static final int MAX_NOTABLE_EVENTS = 6;
	private static final int MAX_EVENT_LENGTH = 120;
	/** Identity token for "the agent was absent", so its reappearance counts as a replacement. */
	private static final Object ABSENT = new Object();

	private final long id;
	private final UUID operatorId;
	private final AgentId agentId;
	private final UUID agentPlayerUuid;
	private final String agentName;
	private final String agentSelector;
	private final PovMode mode;
	private final ResourceKey<Level> operatorDimension;
	private final long startedAtEpochMs;
	private final PovStatePublisher publisher;
	private final OperatorBodyController controller;
	private final BodyDamage damage = new BodyDamage();
	private final PickupTally pickups = new PickupTally();
	private final List<String> deathEvents = new ArrayList<>();
	private boolean resumeOnExit;
	private long expectedRevision;
	private Vec3 lastOperatorPosition;
	private Object agentIdentity;
	private Vec3 lastAgentPosition;
	private long agentSection;
	private boolean anchored;
	private boolean agentDead;
	private int deaths;
	private int respawns;
	private int lookResetSeq;
	private PovTakeoverSummary.Snapshot startSnapshot;
	private PovTakeoverSummary.Snapshot latestSnapshot;

	PovSession(
			long id, UUID operatorId, AgentId agentId, UUID agentPlayerUuid, String agentName, String agentSelector,
			PovMode mode, ResourceKey<Level> operatorDimension, Vec3 operatorPosition, long startedAtEpochMs,
			PovStatePublisher publisher, OperatorBodyController controller, boolean agentDead
	) {
		this.id = id;
		this.operatorId = Objects.requireNonNull(operatorId, "operatorId must not be null");
		this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
		this.agentPlayerUuid = Objects.requireNonNull(agentPlayerUuid, "agentPlayerUuid must not be null");
		this.agentName = Objects.requireNonNull(agentName, "agentName must not be null");
		this.agentSelector = Objects.requireNonNull(agentSelector, "agentSelector must not be null");
		this.mode = Objects.requireNonNull(mode, "mode must not be null");
		this.operatorDimension = Objects.requireNonNull(operatorDimension, "operatorDimension must not be null");
		this.lastOperatorPosition = Objects.requireNonNull(operatorPosition, "operatorPosition must not be null");
		this.startedAtEpochMs = startedAtEpochMs;
		this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
		if ((mode == PovMode.TAKEOVER) != (controller != null)) {
			throw new IllegalArgumentException("Only a takeover owns a body controller");
		}
		this.controller = controller;
		this.agentDead = agentDead;
	}

	public long id() { return id; }
	public UUID operatorId() { return operatorId; }
	public AgentId agentId() { return agentId; }
	public UUID agentPlayerUuid() { return agentPlayerUuid; }
	public String agentName() { return agentName; }
	public String agentSelector() { return agentSelector; }
	public PovMode mode() { return mode; }
	public boolean takeover() { return mode == PovMode.TAKEOVER; }
	ResourceKey<Level> operatorDimension() { return operatorDimension; }
	long startedAtEpochMs() { return startedAtEpochMs; }
	PovStatePublisher publisher() { return publisher; }
	OperatorBodyController controller() { return controller; }
	BodyDamage damage() { return damage; }
	PickupTally pickups() { return pickups; }
	boolean resumeOnExit() { return resumeOnExit; }
	long expectedRevision() { return expectedRevision; }
	int deaths() { return deaths; }
	int respawns() { return respawns; }
	int lookResetSeq() { return lookResetSeq; }
	boolean anchored() { return anchored; }
	long agentSection() { return agentSection; }
	Optional<PovTakeoverSummary.Snapshot> startSnapshot() { return Optional.ofNullable(startSnapshot); }
	Optional<PovTakeoverSummary.Snapshot> latestSnapshot() { return Optional.ofNullable(latestSnapshot); }

	/**
	 * Records the lifecycle intent after the takeover's own stop. Death and respawn keep the goal
	 * revision, so any other change means someone else stopped, steered or replaced the goal.
	 */
	void initialLifecycle(boolean resumeOnExit, long revision) {
		this.resumeOnExit = resumeOnExit;
		this.expectedRevision = revision;
	}

	/** The backstop stopped a model that woke during the takeover; it continues afterwards. */
	void stoppedByBackstop(long revision) {
		resumeOnExit = true;
		expectedRevision = revision;
	}

	/** Returns the distance the operator's body moved since the previous tick. */
	double operatorMoved(Vec3 position) {
		double distance = Math.sqrt(position.distanceToSqr(lastOperatorPosition));
		lastOperatorPosition = position;
		return distance;
	}

	/**
	 * Notes the agent entity seen this tick. A replaced entity (respawn) or a long jump (teleport)
	 * advances the look-reset sequence so a predicting client re-seeds its view from the server.
	 */
	void observeAgent(Object identity, Vec3 position, double jumpBlocks) {
		boolean replaced = agentIdentity != null && agentIdentity != identity;
		boolean jumped = lastAgentPosition != null && position.distanceToSqr(lastAgentPosition) > jumpBlocks * jumpBlocks;
		if (replaced || jumped) lookResetSeq++;
		agentIdentity = identity;
		lastAgentPosition = position;
	}

	void anchor(long section) {
		anchored = true;
		agentSection = section;
	}

	void unanchor() {
		anchored = false;
		agentIdentity = ABSENT;
		lastAgentPosition = null;
	}

	void captureSnapshot(PovTakeoverSummary.Snapshot snapshot) {
		if (snapshot == null) return;
		if (startSnapshot == null) startSnapshot = snapshot;
		latestSnapshot = snapshot;
	}

	/** Counts death and respawn edges seen during the session; a death already present at start is not counted. */
	void observeLifecycle(AgentLifecycleState state, String deathDimension, String deathCause) {
		boolean dead = state == AgentLifecycleState.DEAD;
		if (dead && !agentDead) {
			deaths++;
			if (deathEvents.size() < MAX_NOTABLE_EVENTS) {
				deathEvents.add(bounded("died in " + shortDimension(deathDimension)
						+ (deathCause == null || deathCause.isBlank() ? "" : " (" + deathCause.strip() + ")")));
			}
		} else if (!dead && agentDead) {
			respawns++;
		}
		agentDead = dead;
	}

	List<String> notableEvents() {
		ArrayList<String> events = new ArrayList<>(deathEvents);
		pickups.describe(4).ifPresent(events::add);
		return List.copyOf(events.subList(0, Math.min(events.size(), MAX_NOTABLE_EVENTS)));
	}

	static String shortDimension(String dimensionId) {
		if (dimensionId == null || dimensionId.isBlank()) return "an unknown dimension";
		return dimensionId.startsWith("minecraft:") ? dimensionId.substring("minecraft:".length()) : dimensionId;
	}

	private static String bounded(String value) {
		return value.length() <= MAX_EVENT_LENGTH ? value : value.substring(0, MAX_EVENT_LENGTH - 3) + "...";
	}

	/**
	 * Body damage since a takeover began, as health plus absorption. ALLOW_DAMAGE records the
	 * value before a hit and AFTER_DAMAGE adds the drop, so armor, absorption and resistance are
	 * already applied and healing between hits is never subtracted.
	 */
	static final class BodyDamage {
		static final double EXIT_THRESHOLD = 4.0D;
		// Float health arithmetic must not turn exactly two hearts into 3.9999998.
		private static final double TOLERANCE = 1.0E-4D;
		private double total;
		private double pendingBefore = Double.NaN;

		void before(double healthAndAbsorption) {
			pendingBefore = healthAndAbsorption;
		}

		void after(double healthAndAbsorption) {
			if (!Double.isNaN(pendingBefore) && Double.isFinite(healthAndAbsorption)) {
				total += Math.max(0.0D, pendingBefore - healthAndAbsorption);
			}
			pendingBefore = Double.NaN;
		}

		double total() {
			return total;
		}

		boolean exceeded() {
			return reachesExit(total);
		}

		static boolean reachesExit(double total) {
			return total >= EXIT_THRESHOLD - TOLERANCE;
		}
	}

	/** What the takeover does to the agent's lifecycle at start; see the design's decision table. */
	record LifecyclePlan(boolean stopOnStart, boolean resumeOnExit) {
		static LifecyclePlan forTakeover(AgentLifecycleState state, boolean resumeAfterRespawn) {
			Objects.requireNonNull(state, "state must not be null");
			return switch (state) {
				// DISCONNECTED is stopped too, or coordinator recovery could re-arm it mid-takeover.
				case STARTING, PLANNING, ACTING, DISCONNECTED -> new LifecyclePlan(true, true);
				// Stopping a dead agent clears its continuation, so the operator's respawn lands in PAUSED
				// rather than briefly waking the model; the runtime resumes it on exit instead.
				case DEAD -> new LifecyclePlan(resumeAfterRespawn, resumeAfterRespawn);
				case PAUSED, IDLE, COMPLETED, ERROR -> new LifecyclePlan(false, false);
			};
		}
	}

	/** What happens to the agent's lifecycle when the takeover ends. */
	enum ExitLifecycle {
		NONE,
		RESUME,
		RESUME_AFTER_RESPAWN;

		static ExitLifecycle decide(boolean resumeOnExit, AgentLifecycleState state, boolean unfinishedGoal,
				boolean revisionUnchanged) {
			Objects.requireNonNull(state, "state must not be null");
			if (!resumeOnExit || !unfinishedGoal || !revisionUnchanged) return NONE;
			if (state == AgentLifecycleState.PAUSED) return RESUME;
			if (state == AgentLifecycleState.DEAD) return RESUME_AFTER_RESPAWN;
			return NONE;
		}
	}

	/**
	 * Items the agent picked up, from the vanilla "picked up" statistic sampled while the agent is
	 * present. Only increases count, so a reloaded or replaced counter never produces negative gains.
	 */
	static final class PickupTally {
		private final Map<String, Integer> previous = new HashMap<>();
		private final Map<String, Integer> gained = new LinkedHashMap<>();
		private boolean primed;

		void observe(Map<String, Integer> counts) {
			Objects.requireNonNull(counts, "counts must not be null");
			if (primed) {
				for (Map.Entry<String, Integer> entry : counts.entrySet()) {
					int delta = entry.getValue() - previous.getOrDefault(entry.getKey(), 0);
					if (delta > 0) gained.merge(entry.getKey(), delta, Integer::sum);
				}
			}
			previous.clear();
			previous.putAll(counts);
			primed = true;
		}

		Map<String, Integer> gained() {
			return Map.copyOf(gained);
		}

		Optional<String> describe(int maxItems) {
			if (gained.isEmpty() || maxItems <= 0) return Optional.empty();
			List<Map.Entry<String, Integer>> sorted = gained.entrySet().stream()
					.sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
							.thenComparing(Map.Entry.comparingByKey()))
					.toList();
			String listed = sorted.stream().limit(maxItems)
					.map(entry -> entry.getValue() + " " + entry.getKey())
					.collect(Collectors.joining(", "));
			int more = sorted.size() - Math.min(sorted.size(), maxItems);
			return Optional.of(bounded("picked up " + listed + (more > 0 ? " and " + more + " more" : "")));
		}
	}
}
