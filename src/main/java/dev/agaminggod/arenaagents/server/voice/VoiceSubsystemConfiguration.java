package dev.agaminggod.arenaagents.server.voice;

import java.util.Objects;

/** Worker-validated voice transport configuration passed without global properties or file reads. */
public record VoiceSubsystemConfiguration(String endpoint, String secret) {
	public VoiceSubsystemConfiguration {
		endpoint = Objects.requireNonNull(endpoint, "voice endpoint must not be null").strip();
		if (endpoint.isEmpty() || endpoint.length() > 2_048) {
			throw new IllegalArgumentException("voice endpoint must be nonblank and bounded");
		}
		secret = Objects.requireNonNull(secret, "voice secret must not be null").strip();
		if (secret.length() < 16 || secret.length() > 512) {
			throw new IllegalArgumentException("voice secret is invalid");
		}
	}
}
