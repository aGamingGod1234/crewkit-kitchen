package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.client.combat.CombatTarget;
import dev.agaminggod.arenaagents.client.combat.WeaponCandidate;
import dev.agaminggod.arenaagents.protocol.ActionState;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface ActionContext {
	boolean isClientThread();

	long monotonicTimeMs();

	long epochTimeMs();

	SafetyState safetyState();

	NavigationSnapshot navigationSnapshot();

	WalkabilityView walkabilityView();

	void setMovement(MovementInput movement);

	default CombatSnapshot combatSnapshot() {
		return new CombatSnapshot(List.of(), List.of(), 0.0F, 3.0D);
	}

	default OperationResult attackTarget(UUID targetId) {
		Objects.requireNonNull(targetId, "targetId must not be null");
		return OperationResult.failed("ATTACK_UNAVAILABLE", "Minecraft attack interaction is unavailable");
	}

	default BlockInteractionSnapshot inspectBlock(GridPosition position) {
		Objects.requireNonNull(position, "position must not be null");
		return new BlockInteractionSnapshot(false, false, false, false, BlockFace.UP);
	}

	default BlockProgress breakBlock(GridPosition position, BlockFace face) {
		Objects.requireNonNull(position, "position must not be null");
		Objects.requireNonNull(face, "face must not be null");
		return BlockProgress.failed("BLOCK_INTERACTION_UNAVAILABLE", "Minecraft block breaking is unavailable");
	}

	default OperationResult placeBlock(GridPosition position, BlockFace face, String itemId) {
		Objects.requireNonNull(position, "position must not be null");
		Objects.requireNonNull(face, "face must not be null");
		Objects.requireNonNull(itemId, "itemId must not be null");
		return OperationResult.failed("BLOCK_INTERACTION_UNAVAILABLE", "Minecraft block placement is unavailable");
	}

	LookResult lookAt(
			double x,
			double y,
			double z,
			float maxYawDelta,
			float maxPitchDelta,
			float toleranceDegrees
	);

	OperationResult sendChat(String message);

	OperationResult selectHotbarItem(String itemId);

	OperationResult startUsingItem(Hand hand);

	void stopUsingItem();

	void releaseAll();

	enum Hand {
		MAIN_HAND,
		OFF_HAND
	}

	enum BlockFace {
		DOWN("down"),
		UP("up"),
		NORTH("north"),
		SOUTH("south"),
		WEST("west"),
		EAST("east");

		private final String wireName;

		BlockFace(String wireName) {
			this.wireName = wireName;
		}

		public String wireName() {
			return wireName;
		}

		public static BlockFace fromWireName(String wireName) {
			for (BlockFace face : values()) {
				if (face.wireName.equals(wireName)) {
					return face;
				}
			}
			throw new ActionCreationException("INVALID_BLOCK_FACE", "Unknown block face '" + wireName + "'");
		}
	}

	record CombatSnapshot(
			List<CombatTarget> targets,
			List<WeaponCandidate> hotbarItems,
			float attackStrength,
			double attackReach
	) {
		public CombatSnapshot {
			targets = List.copyOf(Objects.requireNonNull(targets, "targets must not be null"));
			hotbarItems = List.copyOf(Objects.requireNonNull(hotbarItems, "hotbarItems must not be null"));
			if (!Float.isFinite(attackStrength) || attackStrength < 0.0F) {
				throw new IllegalArgumentException("attackStrength must be finite and nonnegative");
			}
			if (!Double.isFinite(attackReach) || attackReach <= 0.0D) {
				throw new IllegalArgumentException("attackReach must be finite and positive");
			}
		}
	}

	record BlockInteractionSnapshot(
			boolean chunkLoaded,
			boolean withinReach,
			boolean visible,
			boolean blockPresent,
			BlockFace visibleFace
	) {
		public BlockInteractionSnapshot {
			visibleFace = Objects.requireNonNull(visibleFace, "visibleFace must not be null");
		}
	}

	record BlockProgress(ActionState state, String reasonCode, String message) {
		public BlockProgress {
			state = Objects.requireNonNull(state, "state must not be null");
			if (state != ActionState.RUNNING && state != ActionState.SUCCEEDED && state != ActionState.FAILED) {
				throw new IllegalArgumentException("block progress must be running, succeeded, or failed");
			}
			reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
			message = Objects.requireNonNull(message, "message must not be null");
		}

		public static BlockProgress running(String message) {
			return new BlockProgress(ActionState.RUNNING, "RUNNING", message);
		}

		public static BlockProgress succeeded(String reasonCode, String message) {
			return new BlockProgress(ActionState.SUCCEEDED, reasonCode, message);
		}

		public static BlockProgress failed(String reasonCode, String message) {
			return new BlockProgress(ActionState.FAILED, reasonCode, message);
		}
	}

	record LookResult(boolean withinTolerance, float yawErrorDegrees, float pitchErrorDegrees) {
		public LookResult {
			if (!Float.isFinite(yawErrorDegrees) || !Float.isFinite(pitchErrorDegrees)) {
				throw new IllegalArgumentException("look errors must be finite");
			}
			if (yawErrorDegrees < 0.0F || pitchErrorDegrees < 0.0F) {
				throw new IllegalArgumentException("look errors must not be negative");
			}
		}
	}

	record NavigationSnapshot(
			double x,
			double y,
			double z,
			float yaw,
			float pitch,
			GridPosition feetPosition
	) {
		public NavigationSnapshot {
			requireFinite(x, "x");
			requireFinite(y, "y");
			requireFinite(z, "z");
			requireFinite(yaw, "yaw");
			requireFinite(pitch, "pitch");
			feetPosition = Objects.requireNonNull(feetPosition, "feetPosition must not be null");
		}

		private static void requireFinite(double value, String field) {
			if (!Double.isFinite(value)) {
				throw new IllegalArgumentException(field + " must be finite");
			}
		}
	}

	record MovementInput(
			boolean forward,
			boolean backward,
			boolean left,
			boolean right,
			boolean jump,
			boolean sprint
	) {
		private static final MovementInput STOPPED = new MovementInput(
				false,
				false,
				false,
				false,
				false,
				false
		);

		public MovementInput {
			if (forward && backward) {
				throw new IllegalArgumentException("forward and backward must not both be active");
			}
			if (left && right) {
				throw new IllegalArgumentException("left and right must not both be active");
			}
			if (sprint && !forward) {
				throw new IllegalArgumentException("sprint requires forward movement");
			}
		}

		public static MovementInput stopped() {
			return STOPPED;
		}

		public boolean active() {
			return forward || backward || left || right || jump || sprint;
		}
	}

	record OperationResult(boolean successful, String reasonCode, String message) {
		public OperationResult {
			reasonCode = requireText(
					reasonCode,
					"reasonCode",
					ProtocolConstants.MAX_REASON_CODE_LENGTH,
					false
			);
			message = requireText(message, "message", ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH, true);
		}

		public static OperationResult succeeded(String reasonCode, String message) {
			return new OperationResult(true, reasonCode, message);
		}

		public static OperationResult failed(String reasonCode, String message) {
			return new OperationResult(false, reasonCode, message);
		}

		private static String requireText(
				String value,
				String field,
				int maximumLength,
				boolean emptyAllowed
		) {
			Objects.requireNonNull(value, field + " must not be null");
			if (!emptyAllowed && value.isBlank()) {
				throw new IllegalArgumentException(field + " must not be blank");
			}
			if (value.length() > maximumLength) {
				throw new IllegalArgumentException(field + " must not exceed " + maximumLength + " characters");
			}
			return value;
		}
	}
}
