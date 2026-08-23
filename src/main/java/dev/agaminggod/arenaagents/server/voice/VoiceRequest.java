package dev.agaminggod.arenaagents.server.voice;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Objects;

public record VoiceRequest(
		AgentId agentId,
		String text,
		String profileId,
		int radius,
		long conversationSequence
) {
	public static final int MAX_TEXT_CODE_POINTS = 280;
	public static final int MAX_RADIUS = 128;

	public VoiceRequest {
		Objects.requireNonNull(agentId, "agentId must not be null");
		text = requireText(text, "text");
		profileId = requireText(profileId, "profileId");
		if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
			throw new IllegalArgumentException("Voice text must be at most 280 Unicode code points");
		}
		if (radius < 1 || radius > MAX_RADIUS) throw new IllegalArgumentException("Voice radius must be between 1 and 128");
		if (conversationSequence < 0L) throw new IllegalArgumentException("Conversation sequence must not be negative");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
