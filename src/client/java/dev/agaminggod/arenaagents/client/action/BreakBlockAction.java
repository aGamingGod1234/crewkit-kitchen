package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.interaction.BlockInteractionPreconditions;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.protocol.ActionState;

public final class BreakBlockAction implements RunningAction {
	private static final float MAX_YAW_DELTA = 20.0F;
	private static final float MAX_PITCH_DELTA = 20.0F;
	private static final float AIM_TOLERANCE = 2.0F;

	private final GridPosition position;
	private final long timeoutMs;
	private boolean started;

	public BreakBlockAction(int x, int y, int z, long timeoutMs) {
		position = new GridPosition(x, y, z);
		if (timeoutMs <= 0L) {
			throw new ActionCreationException("INVALID_BREAK_TIMEOUT", "Block-break timeout must be positive");
		}
		this.timeoutMs = timeoutMs;
	}

	@Override
	public long timeoutMs() {
		return timeoutMs;
	}

	@Override
	public ActionUpdate tick(ActionContext context, long elapsedMs) {
		ActionContext.LookResult look = context.lookAt(
				position.x() + 0.5D,
				position.y() + 0.5D,
				position.z() + 0.5D,
				MAX_YAW_DELTA,
				MAX_PITCH_DELTA,
				AIM_TOLERANCE
		);
		if (!look.withinTolerance()) {
			return ActionUpdate.running("Facing block to break");
		}
		ActionContext.BlockInteractionSnapshot snapshot = context.inspectBlock(position);
		if (started && !snapshot.blockPresent()) {
			return ActionUpdate.succeeded("BLOCK_BROKEN", "Target block is absent after breaking");
		}
		ActionContext.OperationResult precondition = BlockInteractionPreconditions.check(snapshot, false);
		if (!precondition.successful()) {
			return ActionUpdate.failed(precondition.reasonCode(), precondition.message());
		}
		ActionContext.BlockProgress progress = context.breakBlock(position, snapshot.visibleFace());
		started = true;
		return switch (progress.state()) {
			case RUNNING -> ActionUpdate.running(progress.message());
			case SUCCEEDED -> ActionUpdate.succeeded(progress.reasonCode(), progress.message());
			case FAILED -> ActionUpdate.failed(progress.reasonCode(), progress.message());
			default -> ActionUpdate.failed("BLOCK_BREAK_FAILED", "Invalid block-break progress state");
		};
	}
}
