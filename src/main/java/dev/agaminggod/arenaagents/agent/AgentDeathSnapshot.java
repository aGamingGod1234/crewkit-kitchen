package dev.agaminggod.arenaagents.agent;

import java.util.Objects;
import java.util.Optional;

/** Immutable facts captured while a dead player is still observable. */
public record AgentDeathSnapshot(
		String cause, String dimensionId, double x, double y, double z,
		Optional<String> respawnDimensionId, Optional<Double> respawnX, Optional<Double> respawnY, Optional<Double> respawnZ,
		long diedAtEpochMs
) {
	public AgentDeathSnapshot {
		cause = text(cause, "cause");
		dimensionId = text(dimensionId, "dimensionId");
		finite(x, "x"); finite(y, "y"); finite(z, "z");
		respawnDimensionId = Objects.requireNonNull(respawnDimensionId, "respawnDimensionId must not be null");
		respawnX = Objects.requireNonNull(respawnX, "respawnX must not be null");
		respawnY = Objects.requireNonNull(respawnY, "respawnY must not be null");
		respawnZ = Objects.requireNonNull(respawnZ, "respawnZ must not be null");
		boolean present = respawnDimensionId.isPresent();
		if (present != respawnX.isPresent() || present != respawnY.isPresent() || present != respawnZ.isPresent()) {
			throw new AgentDomainException("INVALID_DEATH_SNAPSHOT", "Respawn dimension and coordinates must be present together");
		}
		respawnDimensionId.ifPresent(value -> text(value, "respawnDimensionId"));
		respawnX.ifPresent(value -> finite(value, "respawnX"));
		respawnY.ifPresent(value -> finite(value, "respawnY"));
		respawnZ.ifPresent(value -> finite(value, "respawnZ"));
		if (diedAtEpochMs <= 0L) throw new AgentDomainException("INVALID_DEATH_SNAPSHOT", "diedAtEpochMs must be positive");
	}

	private static String text(String value, String field) {
		String checked = Objects.requireNonNull(value, field + " must not be null").trim();
		if (checked.isEmpty()) throw new AgentDomainException("INVALID_DEATH_SNAPSHOT", field + " must not be blank");
		return checked;
	}

	private static void finite(double value, String field) {
		if (!Double.isFinite(value)) throw new AgentDomainException("INVALID_DEATH_SNAPSHOT", field + " must be finite");
	}
}
