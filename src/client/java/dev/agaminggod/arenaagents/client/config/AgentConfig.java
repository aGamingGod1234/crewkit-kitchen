package dev.agaminggod.arenaagents.client.config;

import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.protocol.ProtocolException;

public record AgentConfig(
		String agentId,
		int bridgePort,
		int observationRadius,
		boolean enabled,
		String bridgeSecret
) {
	public static final String DEFAULT_AGENT_ID = "agent-local";
	public static final int DEFAULT_BRIDGE_PORT = 25_571;
	public static final int DEFAULT_OBSERVATION_RADIUS = 12;
	public static final boolean DEFAULT_ENABLED = false;
	public static final String DEFAULT_BRIDGE_SECRET = "";
	public static final int MIN_BRIDGE_PORT = 1_024;
	public static final int MAX_BRIDGE_PORT = 65_535;
	public static final int MIN_OBSERVATION_RADIUS = 1;
	public static final int MAX_OBSERVATION_RADIUS = 32;
	public static final int MIN_BRIDGE_SECRET_LENGTH = 32;
	public static final int MAX_BRIDGE_SECRET_LENGTH = ProtocolConstants.MAX_COMMAND_ID_LENGTH;

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
		bridgeSecret = bridgeSecret == null ? "" : bridgeSecret.strip();
		if (enabled && bridgeSecret.isEmpty()) {
			throw new ProtocolException("BRIDGE_SECRET_REQUIRED", "Enabled bridge requires an explicit secret");
		}
		if (!bridgeSecret.isEmpty() && (bridgeSecret.length() < MIN_BRIDGE_SECRET_LENGTH
				|| bridgeSecret.length() > MAX_BRIDGE_SECRET_LENGTH)) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_OUT_OF_RANGE,
					"bridgeSecret must be between " + MIN_BRIDGE_SECRET_LENGTH + " and "
							+ MAX_BRIDGE_SECRET_LENGTH + " characters"
			);
		}
	}

	public static AgentConfig defaults() {
		return new AgentConfig(
				DEFAULT_AGENT_ID,
				DEFAULT_BRIDGE_PORT,
				DEFAULT_OBSERVATION_RADIUS,
				DEFAULT_ENABLED,
				DEFAULT_BRIDGE_SECRET
		);
	}

	@Override
	public String toString() {
		return "AgentConfig[agentId=" + agentId
				+ ", bridgePort=" + bridgePort
				+ ", observationRadius=" + observationRadius
				+ ", enabled=" + enabled
				+ ", bridgeSecret=" + (bridgeSecret.isEmpty() ? "<empty>" : "<redacted>")
				+ ']';
	}
}
