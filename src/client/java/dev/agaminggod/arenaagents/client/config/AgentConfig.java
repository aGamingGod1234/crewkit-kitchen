package dev.agaminggod.arenaagents.client.config;

import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.protocol.ProtocolException;

public record AgentConfig(
		String agentId,
		int bridgePort,
		int observationRadius,
		boolean enabled
) {
	public static final String DEFAULT_AGENT_ID = "agent-local";
	public static final int DEFAULT_BRIDGE_PORT = 25_571;
	public static final int DEFAULT_OBSERVATION_RADIUS = 12;
	public static final boolean DEFAULT_ENABLED = false;
	public static final int MIN_BRIDGE_PORT = 1_024;
	public static final int MAX_BRIDGE_PORT = 65_535;
	public static final int MIN_OBSERVATION_RADIUS = 1;
	public static final int MAX_OBSERVATION_RADIUS = 32;

	public AgentConfig {
		if (agentId == null || agentId.isBlank()) {
			throw new ProtocolException(ProtocolConstants.ERROR_INVALID_FIELD, "agentId must not be blank");
		}
		if (agentId.length() > ProtocolConstants.MAX_COMMAND_ID_LENGTH) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"agentId must not exceed " + ProtocolConstants.MAX_COMMAND_ID_LENGTH + " characters"
			);
		}
		if (bridgePort < MIN_BRIDGE_PORT || bridgePort > MAX_BRIDGE_PORT) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"bridgePort must be between " + MIN_BRIDGE_PORT + " and " + MAX_BRIDGE_PORT
			);
		}
		if (observationRadius < MIN_OBSERVATION_RADIUS || observationRadius > MAX_OBSERVATION_RADIUS) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"observationRadius must be between " + MIN_OBSERVATION_RADIUS
							+ " and " + MAX_OBSERVATION_RADIUS
			);
		}
	}

	public static AgentConfig defaults() {
		return new AgentConfig(
				DEFAULT_AGENT_ID,
				DEFAULT_BRIDGE_PORT,
				DEFAULT_OBSERVATION_RADIUS,
				DEFAULT_ENABLED
		);
	}
}
