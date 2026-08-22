package dev.agaminggod.arenaagents.agent;

import java.util.Objects;
import java.util.OptionalInt;

public record AgentEntityLocation(String dimension, int chunkX, int chunkZ, OptionalInt blockY) {
	private static final int MAX_DIMENSION_IDENTIFIER_LENGTH = 256;

	public AgentEntityLocation {
		dimension = Objects.requireNonNull(dimension, "dimension must not be null").trim();
		blockY = Objects.requireNonNull(blockY, "blockY must not be null");
		if (dimension.isEmpty() || dimension.length() > MAX_DIMENSION_IDENTIFIER_LENGTH) {
			throw new AgentDomainException(
					"INVALID_ENTITY_DIMENSION",
					"dimension must contain between 1 and " + MAX_DIMENSION_IDENTIFIER_LENGTH + " characters"
			);
		}
	}

	public AgentEntityLocation(String dimension, int chunkX, int chunkZ) {
		this(dimension, chunkX, chunkZ, OptionalInt.empty());
	}
}
