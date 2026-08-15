package dev.agaminggod.arenaagents.server;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

public final class OfflineAgentPlayers {
	private OfflineAgentPlayers() {
	}

	public static String playerName(AgentId agentId, AgentProfile profile) {
		return AgentIdentity.playerName(agentId, profile);
	}

	public static UUID offlineUuid(AgentId agentId, AgentProfile profile) {
		String name = playerName(agentId, profile);
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}

	public static void spawn(
			MinecraftServer server,
			AgentId agentId,
			AgentProfile profile,
			Vec3 position,
			float yaw,
			float pitch,
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			AgentGameMode gameMode
	) {
		String name = playerName(agentId, profile);
		if (server.getPlayerList().getPlayerByName(name) != null || EntityPlayerMPFake.isSpawningPlayer(name)) {
			throw new AgentDomainException("AGENT_PLAYER_EXISTS", "The offline player for this agent already exists");
		}
		UUID uuid = offlineUuid(agentId, profile);
		boolean accepted;
		OfflineAgentProfileLookup.begin(uuid);
		try {
			accepted = EntityPlayerMPFake.createFake(
					name,
					server,
					position,
					yaw,
					pitch,
					dimension,
					toGameType(gameMode),
					gameMode == AgentGameMode.CREATIVE
			);
		} finally {
			OfflineAgentProfileLookup.end(uuid);
		}
		if (!accepted) {
			throw new AgentDomainException("PLAYER_SPAWN_FAILED", "Carpet rejected the offline agent player spawn");
		}
	}

	public static Optional<ServerPlayer> find(MinecraftServer server, AgentId agentId, AgentProfile profile) {
		ServerPlayer byUuid = server.getPlayerList().getPlayer(offlineUuid(agentId, profile));
		return Optional.ofNullable(byUuid != null ? byUuid : server.getPlayerList().getPlayerByName(playerName(agentId, profile)));
	}

	public static EntityPlayerActionPack actions(ServerPlayer player) {
		return ((ServerPlayerInterface) player).getActionPack();
	}

	public static void stop(ServerPlayer player) {
		actions(player).stopAll();
		player.stopUsingItem();
	}

	public static void remove(ServerPlayer player) {
		stop(player);
		if (player instanceof EntityPlayerMPFake fake) {
			fake.kill(Component.literal("Arena agent removed"));
		} else {
			player.connection.disconnect(Component.literal("Arena agent removed"));
		}
	}

	/** Resolves the player's real vanilla bed, anchor, or world-spawn target without accepting a supplied position. */
	public static VanillaRespawnTarget resolveVanillaRespawn(ServerPlayer player) {
		java.util.Objects.requireNonNull(player, "player must not be null");
		// Match ordinary vanilla respawn: a charged respawn anchor is consumed here.
		TeleportTransition transition = player.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING);
		return new VanillaRespawnTarget(transition.newLevel(), transition.position(), transition.yRot(), transition.xRot());
	}

	public record VanillaRespawnTarget(net.minecraft.server.level.ServerLevel level, Vec3 position, float yaw, float pitch) {
		public VanillaRespawnTarget {
			java.util.Objects.requireNonNull(level, "level must not be null");
			java.util.Objects.requireNonNull(position, "position must not be null");
		}
	}

	public static GameType toGameType(AgentGameMode gameMode) {
		return switch (gameMode) {
			case SURVIVAL -> GameType.SURVIVAL;
			case CREATIVE -> GameType.CREATIVE;
			case ADVENTURE -> GameType.ADVENTURE;
		};
	}
}
