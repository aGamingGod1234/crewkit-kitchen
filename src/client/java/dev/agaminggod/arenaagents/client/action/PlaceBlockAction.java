package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.interaction.BlockInteractionPreconditions;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import java.util.Objects;

public final class PlaceBlockAction implements RunningAction {
	private static final long TIMEOUT_MS = 5_000L;
	private static final float MAX_YAW_DELTA = 20.0F;
	private static final float MAX_PITCH_DELTA = 20.0F;
	private static final float AIM_TOLERANCE = 2.0F;

	private final GridPosition position;
	private final ActionContext.BlockFace face;
	private final String itemId;
	private boolean itemSelected;

	public PlaceBlockAction(int x, int y, int z, String face, String itemId) {
		position = new GridPosition(x, y, z);
		this.face = ActionContext.BlockFace.fromWireName(Objects.requireNonNull(face, "face must not be null"));
		this.itemId = Objects.requireNonNull(itemId, "itemId must not be null");
	}

	@Override
	public long timeoutMs() {
		return TIMEOUT_MS;
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
			return ActionUpdate.running("Facing placement support");
		}
		ActionContext.BlockInteractionSnapshot snapshot = context.inspectBlock(position);
		ActionContext.OperationResult precondition = BlockInteractionPreconditions.check(snapshot, true);
		if (!precondition.successful()) {
			return ActionUpdate.failed(precondition.reasonCode(), precondition.message());
		}
		if (snapshot.visibleFace() != face) {
			return ActionUpdate.failed("BLOCK_FACE_NOT_VISIBLE", "Requested placement face is not under the crosshair");
		}
		if (!itemSelected) {
			ActionContext.OperationResult selection = context.selectHotbarItem(itemId);
			if (!selection.successful()) {
				return ActionUpdate.failed(selection.reasonCode(), selection.message());
			}
			itemSelected = true;
		}
		ActionContext.OperationResult placement = context.placeBlock(position, face, itemId);
		return placement.successful()
				? ActionUpdate.succeeded(placement.reasonCode(), placement.message())
				: ActionUpdate.failed(placement.reasonCode(), placement.message());
	}
}
