package dev.agaminggod.arenaagents.server;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;

public final class AgentVerboseState {
	private static final Map<MinecraftServer, AgentVerboseState> SERVER_STATES = new ConcurrentHashMap<>();

	private final AtomicBoolean enabled = new AtomicBoolean();

	static AgentVerboseState forServer(MinecraftServer server) {
		return SERVER_STATES.computeIfAbsent(
				Objects.requireNonNull(server, "server must not be null"),
				ignored -> new AgentVerboseState()
		);
	}

	static boolean enabled(MinecraftServer server) {
		if (server == null) return false;
		AgentVerboseState state = SERVER_STATES.get(server);
		return state != null && state.enabled();
	}

	static void release(MinecraftServer server) {
		if (server != null) SERVER_STATES.remove(server);
	}

	public boolean enabled() {
		return enabled.get();
	}

	public void setEnabled(boolean enabled) {
		this.enabled.set(enabled);
	}
}
