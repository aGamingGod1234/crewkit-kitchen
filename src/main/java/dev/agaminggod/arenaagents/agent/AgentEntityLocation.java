package dev.agaminggod.arenaagents.agent;

import java.util.Objects;

public record AgentEntityLocation(String dimension, int chunkX, int chunkZ) {
	private static final int MAX_DIMENSION_IDENTIFIER_LENGTH = 256;

	public AgentEntityLocation {
		dimension = Objects.requireNonNull(dimension, "dimension must not be null").trim();
		if (dimension.isEmpty() || dimension.length() > MAX_DIMENSION_IDENTIFIER_LENGTH) {
			throw new AgentDomainException(
					"INVALID_ENTITY_DIMENSION",
					"dimension must contain between 1 and " + MAX_DIMENSION_IDENTIFIER_LENGTH + " characters"
			);
		}
	}
}
