package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.Objects;

public final class SelectItemAction implements RunningAction {
	public static final long TIMEOUT_MS = 1_000L;

	private final String itemId;
	private ActionUpdate terminalUpdate;

	public SelectItemAction(String itemId) {
		this.itemId = requireItemId(itemId);
	}

	@Override
	public long timeoutMs() {
		return TIMEOUT_MS;
	}

	@Override
	public ActionUpdate tick(ActionContext context, long elapsedMs) {
		if (terminalUpdate == null) {
			terminalUpdate = from(context.selectHotbarItem(itemId));
		}
		return terminalUpdate;
	}

	private static ActionUpdate from(ActionContext.OperationResult result) {
		return result.successful()
				? ActionUpdate.succeeded(result.reasonCode(), result.message())
				: ActionUpdate.failed(result.reasonCode(), result.message());
	}

	private static String requireItemId(String itemId) {
		Objects.requireNonNull(itemId, "itemId must not be null");
		if (itemId.isBlank()) {
			throw new IllegalArgumentException("itemId must not be blank");
		}
		if (itemId.length() > ProtocolConstants.MAX_IDENTIFIER_LENGTH) {
			throw new IllegalArgumentException(
					"itemId must not exceed " + ProtocolConstants.MAX_IDENTIFIER_LENGTH + " characters"
			);
		}
		return itemId;
	}
}
