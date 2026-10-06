package dev.agaminggod.arenaagents.pov;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side map from an operator to the agent player whose surroundings (chunks, entities, sounds) that operator
 * receives instead of its own body's. Mixins in ChunkMap and PlayerList read it for every player every tick, so
 * lookups are a single map read and allocate nothing for operators without an anchor.
 */
public final class PovViewAnchors {
	private static final ConcurrentHashMap<UUID, UUID> ANCHORS = new ConcurrentHashMap<>();

	private PovViewAnchors() {
	}

	public static void set(UUID operatorId, UUID agentPlayerUuid) {
		Objects.requireNonNull(operatorId, "operatorId must not be null");
		Objects.requireNonNull(agentPlayerUuid, "agentPlayerUuid must not be null");
		if (operatorId.equals(agentPlayerUuid)) throw new IllegalArgumentException("an operator cannot anchor to itself");
		ANCHORS.put(operatorId, agentPlayerUuid);
	}

	public static void clear(UUID operatorId) {
		ANCHORS.remove(operatorId);
	}

	public static boolean hasAnchor(UUID operatorId) {
		return ANCHORS.containsKey(operatorId);
	}

	/**
	 * The anchored agent, re-resolved by UUID because a respawn replaces the player object. Empty when no anchor is
	 * set, the agent is offline or removed, or it is in another dimension. A dead agent stays a valid anchor until
	 * vanilla removes its body 20 ticks after death; from then until respawn this returns empty.
	 */
	public static Optional<ServerPlayer> anchor(ServerPlayer operator) {
		UUID agentId = ANCHORS.get(operator.getUUID());
		if (agentId == null) return Optional.empty();
		ServerLevel level = operator.level();
		ServerPlayer agent = level.getServer().getPlayerList().getPlayer(agentId);
		if (agent == null || agent.isRemoved() || agent.level().dimension() != level.dimension()) {
			return Optional.empty();
		}
		return Optional.of(agent);
	}

	public static void clearAll() {
		ANCHORS.clear();
	}
}
