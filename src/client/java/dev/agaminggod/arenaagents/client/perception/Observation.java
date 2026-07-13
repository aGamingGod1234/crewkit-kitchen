package dev.agaminggod.arenaagents.client.perception;

import dev.agaminggod.arenaagents.protocol.ActionResult;
import java.util.List;
import java.util.Objects;

public record Observation(
		boolean ready,
		String status,
		Position position,
		Velocity velocity,
		View view,
		PlayerStatus player,
		InventorySnapshot inventory,
		List<EntitySnapshot> entities,
		List<BlockSnapshot> blocks,
		WorldStatus world,
		ActionStatus currentAction,
		ResultStatus lastResult
) {
	public static final String READY_STATUS = "ready";

	public Observation {
		status = requireText(status, "status", !ready);
		position = Objects.requireNonNull(position, "position must not be null");
		velocity = Objects.requireNonNull(velocity, "velocity must not be null");
		view = Objects.requireNonNull(view, "view must not be null");
		player = Objects.requireNonNull(player, "player must not be null");
		inventory = Objects.requireNonNull(inventory, "inventory must not be null");
		entities = List.copyOf(Objects.requireNonNull(entities, "entities must not be null"));
		blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks must not be null"));
		world = Objects.requireNonNull(world, "world must not be null");
		currentAction = Objects.requireNonNull(currentAction, "currentAction must not be null");
		lastResult = Objects.requireNonNull(lastResult, "lastResult must not be null");
	}

	public static Observation unavailable(String reason) {
		return new Observation(
				false,
				requireText(reason, "reason", true),
				Position.ZERO,
				Velocity.ZERO,
				View.ZERO,
				PlayerStatus.empty(),
				InventorySnapshot.empty(),
				List.of(),
				List.of(),
				WorldStatus.empty(),
				ActionStatus.none(),
				ResultStatus.none()
		);
	}

	private static String requireText(String value, String field, boolean anyNonblankValueAllowed) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		if (!anyNonblankValueAllowed && !READY_STATUS.equals(value)) {
			throw new IllegalArgumentException("ready observation status must be '" + READY_STATUS + "'");
		}
		return value;
	}

	private static double requireFinite(double value, String field) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(field + " must be finite");
		}
		return value;
	}

	public record Position(double x, double y, double z) {
		private static final Position ZERO = new Position(0.0D, 0.0D, 0.0D);

		public Position {
			requireFinite(x, "position.x");
			requireFinite(y, "position.y");
			requireFinite(z, "position.z");
		}
	}

	public record Velocity(double x, double y, double z) {
		private static final Velocity ZERO = new Velocity(0.0D, 0.0D, 0.0D);

		public Velocity {
			requireFinite(x, "velocity.x");
			requireFinite(y, "velocity.y");
			requireFinite(z, "velocity.z");
		}
	}

	public record View(float yaw, float pitch) {
		private static final View ZERO = new View(0.0F, 0.0F);

		public View {
			requireFinite(yaw, "view.yaw");
			requireFinite(pitch, "view.pitch");
		}
	}

	public record PlayerStatus(
			float health,
			float maxHealth,
			int hunger,
			int armor,
			List<EffectStatus> effects
	) {
		public PlayerStatus {
			if (health < 0.0F || maxHealth < 0.0F || !Float.isFinite(health) || !Float.isFinite(maxHealth)) {
				throw new IllegalArgumentException("health values must be finite and nonnegative");
			}
			if (hunger < 0 || armor < 0) {
				throw new IllegalArgumentException("hunger and armor must not be negative");
			}
			effects = List.copyOf(Objects.requireNonNull(effects, "effects must not be null"));
		}

		private static PlayerStatus empty() {
			return new PlayerStatus(0.0F, 0.0F, 0, 0, List.of());
		}
	}

	public record EffectStatus(
			String effectId,
			int amplifier,
			int durationTicks,
			boolean ambient,
			boolean visible
	) {
		public EffectStatus {
			effectId = requireText(effectId, "effectId", true);
			if (amplifier < 0 || durationTicks < 0) {
				throw new IllegalArgumentException("effect amplifier and duration must not be negative");
			}
		}
	}

	public record WorldStatus(
			String dimensionId,
			long gameTime,
			long defaultClockTime,
			boolean raining,
			boolean thundering
	) {
		public WorldStatus {
			dimensionId = Objects.requireNonNull(dimensionId, "dimensionId must not be null");
		}

		private static WorldStatus empty() {
			return new WorldStatus("", 0L, 0L, false, false);
		}
	}

	public record ActionStatus(
			boolean present,
			String commandId,
			String type,
			String state
	) {
		public ActionStatus {
			commandId = requirePresenceText(commandId, "commandId", present);
			type = requirePresenceText(type, "type", present);
			state = requirePresenceText(state, "state", present);
		}

		public static ActionStatus none() {
			return new ActionStatus(false, "", "", "");
		}
	}

	public record ResultStatus(
			boolean present,
			String commandId,
			String state,
			String reasonCode,
			String message,
			long completedAtEpochMs
	) {
		public ResultStatus {
			commandId = requirePresenceText(commandId, "commandId", present);
			state = requirePresenceText(state, "state", present);
			reasonCode = requirePresenceText(reasonCode, "reasonCode", present);
			message = Objects.requireNonNull(message, "message must not be null");
			if (present && completedAtEpochMs <= 0L) {
				throw new IllegalArgumentException("present result must have a positive completion timestamp");
			}
			if (!present && completedAtEpochMs != 0L) {
				throw new IllegalArgumentException("absent result must have a zero completion timestamp");
			}
		}

		public static ResultStatus from(ActionResult result) {
			Objects.requireNonNull(result, "result must not be null");
			return new ResultStatus(
					true,
					result.commandId(),
					result.state().name(),
					result.reasonCode(),
					result.message(),
					result.completedAtEpochMs()
			);
		}

		public static ResultStatus none() {
			return new ResultStatus(false, "", "", "", "", 0L);
		}
	}

	private static String requirePresenceText(String value, String field, boolean present) {
		Objects.requireNonNull(value, field + " must not be null");
		if (present == value.isBlank()) {
			throw new IllegalArgumentException(field + " presence must match the snapshot presence flag");
		}
		return value;
	}
}
