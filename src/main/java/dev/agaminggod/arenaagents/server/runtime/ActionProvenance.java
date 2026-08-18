package dev.agaminggod.arenaagents.server.runtime;

import java.util.Objects;

/** Immutable identity of the model-authored ArenaScript step that issued an action. */
public record ActionProvenance(
		String provider,
		String model,
		String reasoningEffort,
		String serviceTier,
		String programId,
		long programVersion,
		String sourceStepId,
		long eventSequence
) {
	public static final int MAX_TEXT_LENGTH = 256;
	public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

	public ActionProvenance {
		provider = boundedNonblank(provider, "provider");
		model = boundedNonblank(model, "model");
		reasoningEffort = boundedNonblank(reasoningEffort, "reasoningEffort");
		serviceTier = boundedNonblank(serviceTier, "serviceTier");
		programId = boundedNonblank(programId, "programId");
		if (programVersion <= 0L || programVersion > MAX_SAFE_INTEGER) {
			throw new IllegalArgumentException("programVersion must be a positive safe integer");
		}
		sourceStepId = boundedNonblank(sourceStepId, "sourceStepId");
		if (eventSequence < 0L || eventSequence > MAX_SAFE_INTEGER) {
			throw new IllegalArgumentException("eventSequence must be a nonnegative safe integer");
		}
	}

	private static String boundedNonblank(String value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.length() > MAX_TEXT_LENGTH || value.codePoints().allMatch(ActionProvenance::isProtocolWhitespace)) {
			throw new IllegalArgumentException(field + " must be nonblank and at most " + MAX_TEXT_LENGTH + " characters");
		}
		return value;
	}

	private static boolean isProtocolWhitespace(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0xfeff;
	}
}
