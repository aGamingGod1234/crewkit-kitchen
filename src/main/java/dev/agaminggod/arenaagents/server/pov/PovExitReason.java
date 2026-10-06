package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.pov.PovMode;
import java.util.Objects;
import java.util.Optional;

/** Why a POV session ended, with the operator-facing explanation. */
public enum PovExitReason {
	MANUAL("You returned to your body."),
	REPLACED("You switched to another agent view."),
	OPERATOR_DISCONNECTED("You disconnected."),
	OPERATOR_DIED("Your body died."),
	OPERATOR_DIMENSION_CHANGED("Your body changed dimension."),
	OPERATOR_TELEPORTED("Your body was teleported."),
	PERMISSION_LOST("You no longer have operator permission."),
	AGENT_REMOVED("The agent was removed."),
	AGENT_DIMENSION_CHANGED("The agent moved to another dimension."),
	BODY_DAMAGED("Your body lost 2 hearts, so the takeover ended."),
	CLAIMED_BY_SKIT("A skit or Director take claimed the agent."),
	CLAIMED_BY_SCENARIO("A scenario claimed the agent."),
	SERVER_STOPPING("The server is stopping."),
	FAILED("The agent view stopped after an error. Check the server log.");

	/** Any larger jump between two ticks is a teleport; the client keeps the body still in both modes. */
	public static final double TELEPORT_DISTANCE_BLOCKS = 32.0D;

	private final String message;

	PovExitReason(String message) {
		this.message = message;
	}

	public String message() {
		return message;
	}

	/** Dimension changes end the session in v1; the operator re-runs the same command afterwards. */
	public static String agentDimensionMessage(String dimensionId, PovMode mode, String agentName) {
		String command = mode == PovMode.TAKEOVER ? "/takeover " : "/spectator ";
		return "Agent moved to " + dimensionId + ". Run " + command + agentName
				+ " again once you are in the same dimension.";
	}

	/**
	 * Picks the single exit reason for one tick, most fundamental first, so a disconnect is never
	 * reported as a teleport. Claims and body damage only end takeovers; spectating stays passive.
	 */
	public static Optional<PovExitReason> select(Observation observation) {
		Objects.requireNonNull(observation, "observation must not be null");
		boolean takeover = observation.mode() == PovMode.TAKEOVER;
		if (!observation.operatorOnline()) return Optional.of(OPERATOR_DISCONNECTED);
		if (!observation.agentRegistered()) return Optional.of(AGENT_REMOVED);
		if (!observation.operatorMayControl()) return Optional.of(PERMISSION_LOST);
		if (!observation.operatorAlive()) return Optional.of(OPERATOR_DIED);
		if (takeover && PovSession.BodyDamage.reachesExit(observation.bodyDamage())) return Optional.of(BODY_DAMAGED);
		if (observation.operatorDimensionChanged()) return Optional.of(OPERATOR_DIMENSION_CHANGED);
		if (observation.operatorMovedBlocks() > TELEPORT_DISTANCE_BLOCKS) return Optional.of(OPERATOR_TELEPORTED);
		if (takeover && observation.agentClaimed()) return Optional.of(CLAIMED_BY_SKIT);
		if (observation.agentPresent() && observation.agentDimensionDiffers()) return Optional.of(AGENT_DIMENSION_CHANGED);
		return Optional.empty();
	}

	/** One tick of facts about a session; built by the runtime, evaluated without a server. */
	public record Observation(
			PovMode mode,
			boolean operatorOnline,
			boolean operatorAlive,
			boolean operatorMayControl,
			boolean operatorDimensionChanged,
			double operatorMovedBlocks,
			boolean agentRegistered,
			boolean agentPresent,
			boolean agentDimensionDiffers,
			boolean agentClaimed,
			double bodyDamage
	) {
		public Observation {
			Objects.requireNonNull(mode, "mode must not be null");
			if (!Double.isFinite(operatorMovedBlocks) || operatorMovedBlocks < 0.0D) {
				throw new IllegalArgumentException("operatorMovedBlocks must be finite and non-negative");
			}
			if (!Double.isFinite(bodyDamage) || bodyDamage < 0.0D) {
				throw new IllegalArgumentException("bodyDamage must be finite and non-negative");
			}
		}
	}
}
