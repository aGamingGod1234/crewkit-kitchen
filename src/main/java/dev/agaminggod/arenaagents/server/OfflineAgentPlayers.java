package dev.agaminggod.arenaagents.server;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public final class OfflineAgentPlayers {
	private static final String NAME_PREFIX = "AA";

	private OfflineAgentPlayers() {
	}

	public static String playerName(AgentId agentId) {
		return NAME_PREFIX + agentId.compactValue().substring(0, 14).toUpperCase(Locale.ROOT);
	}

	public static UUID offlineUuid(AgentId agentId) {
		String name = playerName(agentId);
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
	}

	public static void spawn(
			MinecraftServer server,
			AgentId agentId,
			Vec3 position,
			float yaw,
			float pitch,
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			AgentGameMode gameMode
	) {
		String name = playerName(agentId);
		if (server.getPlayerList().getPlayerByName(name) != null || EntityPlayerMPFake.isSpawningPlayer(name)) {
			throw new AgentDomainException("AGENT_PLAYER_EXISTS", "The offline player for this agent already exists");
		}
		boolean accepted = EntityPlayerMPFake.createFake(
				name,
				server,
				position,
				yaw,
				pitch,
				dimension,
				toGameType(gameMode),
				gameMode == AgentGameMode.CREATIVE
		);
		if (!accepted) {
			throw new AgentDomainException("PLAYER_SPAWN_FAILED", "Carpet rejected the offline agent player spawn");
		}
	}

	public static Optional<ServerPlayer> find(MinecraftServer server, AgentId agentId) {
		ServerPlayer byUuid = server.getPlayerList().getPlayer(offlineUuid(agentId));
		return Optional.ofNullable(byUuid != null ? byUuid : server.getPlayerList().getPlayerByName(playerName(agentId)));
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

	public static GameType toGameType(AgentGameMode gameMode) {
		return switch (gameMode) {
			case SURVIVAL -> GameType.SURVIVAL;
			case CREATIVE -> GameType.CREATIVE;
			case ADVENTURE -> GameType.ADVENTURE;
		};
	}
}
