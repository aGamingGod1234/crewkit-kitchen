package dev.agaminggod.arenaagents.crewkit.cast;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.crewkit.CrewkitAnchors;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * /crewkit chef ready: one real Agent Arena agent named Chef (Claude Opus 5.5, low effort) standing at the ck_agent
 * anchor in the chef skin. Reuses the existing agent when there is one, so it is safe to run on every /crewkit stage.
 * The agent player joins a few ticks after summon, so placement waits for it in a server tick hook.
 */
public final class ChefReady {
	public static final String NAME = "Chef";
	public static final String PROVIDER = "claude";
	public static final String MODEL = "claude-opus-5-5";
	public static final String EFFORT = "low";
	/** Facing south, across the pass towards the dining room and camera. */
	public static final float FACING_YAW = 0f;
	private static final int PLACE_TIMEOUT_TICKS = 20 * 60;

	private static int pendingTicks;

	private ChefReady() {}

	static void register() {
		ServerTickEvents.END_SERVER_TICK.register(ChefReady::tick);
	}

	/** Summons or reuses Chef and puts it at the anchor. Returns a one-line status for the operator. */
	public static String ready(MinecraftServer server) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		Optional<AgentRecord> existing = find(manager);
		String status;
		if (existing.isEmpty()) {
			manager.summon(server.overworld(), anchor(), PROVIDER, MODEL, EFFORT, "priority",
					Optional.of(NAME), AgentGameMode.SURVIVAL);
			status = "Summoning Chef (" + PROVIDER + " " + MODEL + " " + EFFORT + ") at the chef anchor.";
		} else {
			Optional<ServerPlayer> player = manager.findAgentPlayer(existing.get().agentId());
			if (player.isEmpty()) {
				manager.requestRespawn(existing.get().agentId().toString());
				status = "Respawning Chef; it will be placed at the chef anchor when it joins.";
			} else {
				status = "Chef is ready at the chef anchor.";
			}
		}
		// The team is keyed by player name, so the skin applies the moment the agent joins.
		CastFeature.assignChef(server, NAME);
		pendingTicks = PLACE_TIMEOUT_TICKS;
		tick(server);
		return status;
	}

	private static void tick(MinecraftServer server) {
		if (pendingTicks <= 0) return;
		pendingTicks--;
		CodexAgentManager manager = CodexAgentManager.get(server);
		Optional<ServerPlayer> player = find(manager).flatMap(record -> manager.findAgentPlayer(record.agentId()));
		if (player.isEmpty()) return;
		ServerPlayer chef = player.get();
		pendingTicks = 0;
		// Re-assigning by the real player name also discards any stand-in mannequin.
		CastFeature.assignChef(server, chef.getScoreboardName());
		dev.agaminggod.arenaagents.server.OfflineAgentPlayers.stop(chef);
		Vec3 at = anchor();
		chef.teleportTo(server.overworld(), at.x, at.y, at.z, Set.<Relative>of(), FACING_YAW, 0f, false);
		chef.setYHeadRot(FACING_YAW);
		chef.setYBodyRot(FACING_YAW);
		chef.setDeltaMovement(Vec3.ZERO);
	}

	static Optional<AgentRecord> find(CodexAgentManager manager) {
		return manager.records().stream()
				.filter(record -> record.profile().userName().filter(NAME::equalsIgnoreCase).isPresent())
				.findFirst();
	}

	private static Vec3 anchor() {
		return CrewkitAnchors.at(CrewkitAnchors.AGENT);
	}
}
