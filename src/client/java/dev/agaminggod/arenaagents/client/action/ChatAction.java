package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.Objects;

public final class ChatAction implements RunningAction {
	public static final long TIMEOUT_MS = 1_000L;

	private final String message;
	private ActionUpdate terminalUpdate;

	public ChatAction(String message) {
		this.message = requireMessage(message);
	}

	@Override
	public long timeoutMs() {
		return TIMEOUT_MS;
	}

	@Override
	public ActionUpdate tick(ActionContext context, long elapsedMs) {
		if (terminalUpdate == null) {
			terminalUpdate = from(context.sendChat(message));
		}
		return terminalUpdate;
	}

	private static ActionUpdate from(ActionContext.OperationResult result) {
		return result.successful()
				? ActionUpdate.succeeded(result.reasonCode(), result.message())
				: ActionUpdate.failed(result.reasonCode(), result.message());
	}

	private static String requireMessage(String message) {
		Objects.requireNonNull(message, "message must not be null");
		if (message.isBlank()) {
			throw new IllegalArgumentException("message must not be blank");
		}
		if (message.length() > ProtocolConstants.MAX_CHAT_LENGTH) {
			throw new IllegalArgumentException(
					"message must not exceed " + ProtocolConstants.MAX_CHAT_LENGTH + " characters"
			);
		}
		return message;
	}
}
