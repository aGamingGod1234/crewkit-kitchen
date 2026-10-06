package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

/**
 * Transient exclusive control of an agent's body by one operator (takeover). Normal control paths
 * (start, steer, resume, queue, skit, conversation wake) consult this gate; the POV runtime itself
 * bypasses it by calling the registry directly. Nothing here is persisted: a crash simply drops it.
 */
public final class AgentControlReservations {
	public static final String RESERVED_CODE = "AGENT_RESERVED";
	private static final Map<MinecraftServer, Table> TABLES = new ConcurrentHashMap<>();

	private AgentControlReservations() {
	}

	public static void requireUnreserved(MinecraftServer server, AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		if (server == null) return;
		Table table = TABLES.get(server);
		if (table != null) table.requireUnreserved(agentId);
	}

	public static boolean isReserved(MinecraftServer server, AgentId agentId) {
		return owner(server, agentId).isPresent();
	}

	public static Optional<UUID> owner(MinecraftServer server, AgentId agentId) {
		if (server == null || agentId == null) return Optional.empty();
		Table table = TABLES.get(server);
		return table == null ? Optional.empty() : table.owner(agentId);
	}

	static void reserve(MinecraftServer server, AgentId agentId, UUID owner) {
		TABLES.computeIfAbsent(Objects.requireNonNull(server, "server must not be null"), ignored -> new Table())
				.reserve(agentId, owner);
	}

	static boolean release(MinecraftServer server, AgentId agentId, UUID owner) {
		Table table = TABLES.get(server);
		return table != null && table.release(agentId, owner);
	}

	static void releaseAll(MinecraftServer server) {
		TABLES.remove(server);
	}

	/** Pure reservation rules, kept free of server state so they can be verified directly. */
	static final class Table {
		private final Map<AgentId, UUID> owners = new HashMap<>();

		synchronized Optional<UUID> owner(AgentId agentId) {
			return Optional.ofNullable(owners.get(agentId));
		}

		synchronized void requireUnreserved(AgentId agentId) {
			if (owners.containsKey(agentId)) {
				throw new AgentDomainException(RESERVED_CODE,
						"An operator is controlling this agent with /takeover. Wait until they run /takeover exit");
			}
		}

		/** Re-reserving by the same owner is harmless; a second operator is always rejected. */
		synchronized void reserve(AgentId agentId, UUID owner) {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(owner, "owner must not be null");
			UUID current = owners.putIfAbsent(agentId, owner);
			if (current != null && !current.equals(owner)) {
				throw new AgentDomainException(RESERVED_CODE, "Another operator is already controlling this agent");
			}
		}

		/** Only the owner can release, so a stale session never frees another operator's takeover. */
		synchronized boolean release(AgentId agentId, UUID owner) {
			return owners.remove(agentId, owner);
		}

		synchronized boolean isEmpty() {
			return owners.isEmpty();
		}
	}
}
