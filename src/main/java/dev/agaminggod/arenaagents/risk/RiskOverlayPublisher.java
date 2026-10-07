package dev.agaminggod.arenaagents.risk;

import dev.agaminggod.arenaagents.server.perception.RiskAssessment;
import dev.agaminggod.arenaagents.server.pov.PovSession;
import dev.agaminggod.arenaagents.server.pov.PovSessionRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * Sends the watched agent's risk view to players who spectate it (vanilla spectator camera) or watch/take it over
 * through a POV session, twice a second and only when it changed. Presentation only.
 */
public final class RiskOverlayPublisher {
	static final int INTERVAL_TICKS = 10;
	static final double RANGE = 24.0D;
	private static final Map<UUID, RiskOverlayPayload> LAST_SENT = new HashMap<>();
	private static boolean registered;

	private RiskOverlayPublisher() {
	}

	public static synchronized void register() {
		if (registered) return;
		RiskOverlayPayload.register();
		ServerTickEvents.END_SERVER_TICK.register(RiskOverlayPublisher::tick);
		registered = true;
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % INTERVAL_TICKS != 0) return;
		List<UUID> online = new ArrayList<>();
		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			online.add(viewer.getUUID());
			if (!ServerPlayNetworking.canSend(viewer, RiskOverlayPayload.TYPE)) continue;
			ServerPlayer agent = watchedAgent(server, viewer);
			RiskOverlayPayload payload = agent == null ? null : snapshot(agent);
			RiskOverlayPayload previous = LAST_SENT.get(viewer.getUUID());
			if (payload == null) {
				if (previous != null && !previous.rows().isEmpty()) {
					ServerPlayNetworking.send(viewer, new RiskOverlayPayload(previous.agentEntityId(), List.of()));
				}
				LAST_SENT.remove(viewer.getUUID());
				continue;
			}
			if (payload.equals(previous)) continue;
			ServerPlayNetworking.send(viewer, payload);
			LAST_SENT.put(viewer.getUUID(), payload);
		}
		LAST_SENT.keySet().retainAll(online);
	}

	private static ServerPlayer watchedAgent(MinecraftServer server, ServerPlayer viewer) {
		PovSession session = PovSessionRuntime.session(viewer).orElse(null);
		if (session != null) return server.getPlayerList().getPlayer(session.agentPlayerUuid());
		if (viewer.getCamera() instanceof ServerPlayer camera && camera != viewer
				&& AgentInputRuntime.findAgentId(camera).isPresent()) {
			return camera;
		}
		return null;
	}

	private static RiskOverlayPayload snapshot(ServerPlayer agent) {
		List<RiskOverlayPayload.Row> rows = new ArrayList<>();
		List<LivingEntity> nearby = agent.level().getEntitiesOfClass(LivingEntity.class, agent.getBoundingBox().inflate(RANGE),
				entity -> agent.distanceTo(entity) <= RANGE && RiskAssessment.carriesRisk(agent, entity));
		nearby.sort(Comparator.comparingDouble(agent::distanceTo));
		for (LivingEntity entity : nearby) {
			if (rows.size() >= RiskOverlayPayload.MAX_ROWS) break;
			RiskAssessment.Assessment assessment = RiskAssessment.assess(agent, entity);
			// Rounded so tiny per-tick movements do not resend an unchanged label.
			rows.add(new RiskOverlayPayload.Row(entity.getId(), (float) Math.round(assessment.risk()), assessment.active()));
		}
		return new RiskOverlayPayload(agent.getId(), rows);
	}
}
