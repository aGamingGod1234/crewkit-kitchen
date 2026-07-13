package dev.agaminggod.arenaagents.client.interaction;

import dev.agaminggod.arenaagents.client.action.ActionContext;
import java.util.Objects;

public final class BlockInteractionPreconditions {
	private BlockInteractionPreconditions() {
	}

	public static ActionContext.OperationResult check(
			ActionContext.BlockInteractionSnapshot snapshot,
			boolean placement
	) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		if (!snapshot.chunkLoaded()) {
			return failure("CHUNK_NOT_LOADED", "Target chunk is not cached on the client");
		}
		if (!snapshot.withinReach()) {
			return failure("BLOCK_OUT_OF_REACH", "Target block is outside survival interaction reach");
		}
		if (!snapshot.visible()) {
			return failure("BLOCK_NOT_VISIBLE", "No visible target face is under the crosshair");
		}
		if (!snapshot.blockPresent()) {
			return failure(
					placement ? "PLACE_SUPPORT_MISSING" : "BLOCK_MISSING",
					placement ? "Placement support block is missing" : "Target block is already absent"
			);
		}
		return ActionContext.OperationResult.succeeded("INTERACTION_READY", "Interaction preconditions passed");
	}

	private static ActionContext.OperationResult failure(String reasonCode, String message) {
		return ActionContext.OperationResult.failed(reasonCode, message);
	}
}
