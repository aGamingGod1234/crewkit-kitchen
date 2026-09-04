package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** Applies short, high-priority movement leases for immediate survival hazards. */
public final class SafetyInputRuntime {
	private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();

	private SafetyInputRuntime() {
	}

	public static synchronized void tick(MinecraftServer server) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		State state = STATES.computeIfAbsent(server, ignored -> new State());
		long now = System.currentTimeMillis();
		Set<AgentId> present = new HashSet<>();

		for (var record : manager.records()) {
			AgentId agentId = record.agentId();
			ServerPlayer player = manager.findAgentPlayer(agentId).orElse(null);
			if (player == null || !player.isAlive() || player.isCreative() || player.isSpectator()) {
				state.remove(agentId);
				continue;
			}
			present.add(agentId);
			boolean damaged = state.damage.observe(agentId, player.getHealth());
			Optional<Float> escapeYaw = escapeYaw(player, damaged);
			SafetyInputReflex.Threat threat = new SafetyInputReflex.Threat(
					damaged,
					player.isOnFire(),
					player.isInWall(),
					Math.max(0, player.getAirSupply()),
					Math.max(1, player.getMaxAirSupply()),
					player.onGround(),
					player.getYRot(),
					player.getXRot(),
					player.getInventory().getSelectedSlot(),
					escapeYaw
			);
			Optional<SafetyInputReflex.Decision> decision = SafetyInputReflex.choose(threat);
			if (decision.isPresent()) {
				SafetyInputReflex.Decision selected = decision.orElseThrow();
				state.leases.computeIfAbsent(agentId,
						ignored -> new SafetyInputLease(AgentInputRuntime.controller(server), agentId))
						.activate(selected.input(), now, selected.durationMs());
			} else {
				SafetyInputLease lease = state.leases.get(agentId);
				if (lease != null && lease.releaseExpired(now)) state.leases.remove(agentId);
			}
		}

		state.retain(present);
	}

	public static synchronized void release(MinecraftServer server) {
		State state = STATES.remove(server);
		if (state != null) state.close();
	}

	private static Optional<Float> escapeYaw(ServerPlayer player, boolean damaged) {
		if (!damaged) return Optional.empty();
		LivingEntity attacker = player.getLastHurtByMob();
		if (attacker == null || !attacker.isAlive() || attacker.level() != player.level()) return Optional.empty();
		return SafetyInputReflex.escapeYaw(player.getX(), player.getZ(), attacker.getX(), attacker.getZ());
	}

	private static final class State {
		private final RecentDamageTracker damage = new RecentDamageTracker();
		private final Map<AgentId, SafetyInputLease> leases = new HashMap<>();

		private void remove(AgentId agentId) {
			SafetyInputLease lease = leases.remove(agentId);
			if (lease != null) lease.close();
		}

		private void retain(Set<AgentId> present) {
			for (AgentId agentId : Set.copyOf(leases.keySet())) {
				if (!present.contains(agentId)) remove(agentId);
			}
			damage.retainAgents(present);
		}

		private void close() {
			leases.values().forEach(SafetyInputLease::close);
			leases.clear();
			damage.retainAgents(Set.of());
		}
	}
}
