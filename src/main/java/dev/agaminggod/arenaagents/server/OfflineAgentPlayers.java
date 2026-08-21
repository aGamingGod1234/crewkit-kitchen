package dev.agaminggod.arenaagents.server;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

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
		spawn(server, agentId, profile, position, yaw, pitch, dimension, toGameType(gameMode));
	}

	public static void spawn(
			MinecraftServer server,
			AgentId agentId,
			AgentProfile profile,
			Vec3 position,
			float yaw,
			float pitch,
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			GameType gameMode
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
					gameMode,
					gameMode == GameType.CREATIVE || gameMode == GameType.SPECTATOR
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
		AgentInputRuntime.clear(player);
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

	/** Revalidates persisted vanilla respawn configuration without requiring the transient dead player. */
	public static VanillaRespawnTarget resolveVanillaRespawn(MinecraftServer server, AgentDeathSnapshot death) {
		java.util.Objects.requireNonNull(server, "server must not be null");
		java.util.Objects.requireNonNull(death, "death must not be null");
		GameType gameMode = GameType.byName(death.gameMode(), null);
		if (gameMode == null) throw new AgentDomainException("INVALID_DEATH_SNAPSHOT", "Unknown saved game mode");
		if (death.respawnDimensionId().isPresent()) {
			var level = server.getAllLevels();
			net.minecraft.server.level.ServerLevel configured = null;
			for (var candidate : level) {
				if (candidate.dimension().identifier().toString().equals(death.respawnDimensionId().orElseThrow())) {
					configured = candidate;
					break;
				}
			}
			if (configured != null) {
				BlockPos pos = BlockPos.containing(
						death.respawnX().orElseThrow(), death.respawnY().orElseThrow(), death.respawnZ().orElseThrow()
				);
				float yaw = death.respawnYaw().orElseThrow();
				float pitch = death.respawnPitch().orElseThrow();
				boolean forced = death.respawnForced().orElseThrow();
				BlockState state = configured.getBlockState(pos);
				if (state.getBlock() instanceof RespawnAnchorBlock
						&& (forced || state.getValue(RespawnAnchorBlock.CHARGE) > 0)
						&& RespawnAnchorBlock.canSetSpawn(configured, pos)) {
					Optional<Vec3> stand = RespawnAnchorBlock.findStandUpPosition(EntityType.PLAYER, configured, pos);
					if (stand.isPresent()) {
						float anchorYaw = calculateRespawnYaw(stand.orElseThrow(), pos);
						Optional<AnchorCharge> anchor = forced ? Optional.empty() : Optional.of(new AnchorCharge(configured, pos, state));
						return new VanillaRespawnTarget(configured, stand.orElseThrow(), anchorYaw, 0.0F, gameMode, false, anchor);
					}
				}
				if (state.getBlock() instanceof BedBlock
						&& configured.environmentAttributes().getValue(EnvironmentAttributes.BED_RULE, pos).canSetSpawn(configured)) {
					Direction facing = state.getValue(BedBlock.FACING);
					Optional<Vec3> stand = BedBlock.findStandUpPosition(EntityType.PLAYER, configured, pos, facing, yaw);
					if (stand.isPresent()) {
						float respawnYaw = calculateRespawnYaw(stand.orElseThrow(), pos);
						return new VanillaRespawnTarget(configured, stand.orElseThrow(), respawnYaw, 0.0F, gameMode, false, Optional.empty());
					}
				}
				if (forced && state.getBlock().isPossibleToRespawnInThis(state)) {
					BlockState above = configured.getBlockState(pos.above());
					if (above.getBlock().isPossibleToRespawnInThis(above)) {
						return new VanillaRespawnTarget(configured, new Vec3(pos.getX() + 0.5D, pos.getY() + 0.1D, pos.getZ() + 0.5D), yaw, pitch, gameMode, false, Optional.empty());
					}
				}
			}
		}
		var fallback = server.findRespawnDimension();
		var data = fallback.getRespawnData();
		return new VanillaRespawnTarget(fallback, data.pos().getBottomCenter(), data.yaw(), data.pitch(), gameMode, true, Optional.empty());
	}

	/** Mirrors vanilla's private RespawnPosAngle look-at calculation. */
	static float calculateRespawnYaw(Vec3 position, BlockPos spawnBlock) {
		Vec3 direction = Vec3.atBottomCenterOf(spawnBlock).subtract(position).normalize();
		return Mth.wrapDegrees((float) (Mth.atan2(direction.z, direction.x) * 57.2957763671875D - 90.0D));
	}

	public record VanillaRespawnTarget(
			net.minecraft.server.level.ServerLevel level, Vec3 position, float yaw, float pitch,
			GameType gameMode, boolean adjustSharedSpawn, Optional<AnchorCharge> anchorCharge
	) {
		public VanillaRespawnTarget {
			java.util.Objects.requireNonNull(level, "level must not be null");
			java.util.Objects.requireNonNull(position, "position must not be null");
			java.util.Objects.requireNonNull(gameMode, "gameMode must not be null");
			java.util.Objects.requireNonNull(anchorCharge, "anchorCharge must not be null");
		}

		public Vec3 finalPosition(ServerPlayer player) {
			return adjustSharedSpawn
					? player.adjustSpawnLocation(level, level.getRespawnData().pos()).getBottomCenter()
					: position;
		}

		public Runnable commitWorldEffects() {
			return anchorCharge.map(AnchorCharge::consume).orElse(() -> { });
		}
	}

	public record AnchorCharge(net.minecraft.server.level.ServerLevel level, BlockPos pos, BlockState expected) {
		public AnchorCharge {
			java.util.Objects.requireNonNull(level, "level must not be null");
			java.util.Objects.requireNonNull(pos, "pos must not be null");
			java.util.Objects.requireNonNull(expected, "expected must not be null");
		}

		Runnable consume() {
			BlockState current = level.getBlockState(pos);
			if (!current.equals(expected) || !(current.getBlock() instanceof RespawnAnchorBlock)
					|| current.getValue(RespawnAnchorBlock.CHARGE) <= 0) {
				throw new AgentDomainException("RESPAWN_TARGET_CHANGED", "Respawn anchor changed before commit");
			}
			BlockState consumed = current.setValue(RespawnAnchorBlock.CHARGE, current.getValue(RespawnAnchorBlock.CHARGE) - 1);
			level.setBlock(pos, consumed, 3);
			return () -> {
				if (level.getBlockState(pos).equals(consumed)) level.setBlock(pos, expected, 3);
			};
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
