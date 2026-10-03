package dev.agaminggod.arenaagents.server;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentEntityLocation;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Exercises the shutdown location writer with real Carpet identities and the durable registry. */
public final class AgentShutdownLocationVerification {
	private AgentShutdownLocationVerification() { }

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.out.println("PASS: " + verify() + " shutdown location assertions");
	}

	public static int verify() {
		Fixture fixture = fixture();
		AgentRecord pending = fixture.create("PendingRecovery", true);
		fixture.manager.registry().attachEntity(pending.agentId(), fixture.uuid(pending), fixture.oldLocation, 1_001L);
		fixture.manager.registry().detachEntity(pending.agentId(), 1_002L);
		AgentRecord detached = fixture.manager.registry().require(pending.agentId());

		// Carpet can temporarily restore health on a body whose death is already committed.
		AgentRecord dead = fixture.create("RetainedDeath", true);
		fixture.manager.registry().attachEntity(dead.agentId(), fixture.uuid(dead), fixture.oldLocation, 1_001L);
		AgentDeathSnapshot death = new AgentDeathSnapshot(
				"RetainedDeath was slain by Zombie", "minecraft:overworld", 2.5D, 69.0D, 1.5D,
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 1_002L);
		fixture.manager.registry().die(dead.agentId(), death, 1_002L);
		AgentRecord capturedDeath = fixture.manager.registry().require(dead.agentId());

		AgentRecord live = fixture.create("AttachedLive", true);
		fixture.manager.registry().attachEntity(live.agentId(), fixture.uuid(live), fixture.oldLocation, 1_001L);
		AgentRecord differentBody = fixture.create("DifferentBody", true);
		fixture.manager.registry().attachEntity(differentBody.agentId(), UUID.randomUUID(), fixture.oldLocation, 1_001L);
		AgentRecord nonliving = fixture.create("DeadPhysical", false);
		fixture.manager.registry().attachEntity(nonliving.agentId(), fixture.uuid(nonliving), fixture.oldLocation, 1_001L);
		AgentRecord unavailable = fixture.create("Unavailable", true);
		fixture.manager.registry().attachEntity(unavailable.agentId(), fixture.uuid(unavailable), fixture.oldLocation, 1_001L);
		fixture.players.connected.remove(fixture.uuid(unavailable));

		fixture.persist();
		assertEquals(detached, fixture.manager.registry().require(pending.agentId()),
				"shutdown leaves a late recovery body uncommitted instead of persisting a location without its UUID");
		assertEquals(capturedDeath, fixture.manager.registry().require(dead.agentId()),
				"shutdown preserves the captured death and detached entity even while its Carpet body remains");
		assertEquals(Optional.of(fixture.finalLocation), fixture.manager.registry().require(live.agentId()).entityLocation(),
				"shutdown persists the committed living body's exact final position and view");
		assertEquals(Optional.of(fixture.oldLocation), fixture.manager.registry().require(differentBody.agentId()).entityLocation(),
				"shutdown cannot write another UUID's physical location over the committed body");
		assertEquals(Optional.of(fixture.oldLocation), fixture.manager.registry().require(nonliving.agentId()).entityLocation(),
				"shutdown cannot replace the last living location with a body whose death has not yet reconciled");
		assertEquals(Optional.of(fixture.oldLocation), fixture.manager.registry().require(unavailable.agentId()).entityLocation(),
				"a player already removed during shutdown retains its last committed location");
		assertEquals(AgentLifecycleState.DEAD, fixture.manager.registry().require(dead.agentId()).state(),
				"shutdown location persistence never commits a respawn");
		AgentRecord savedLive = fixture.manager.registry().require(live.agentId());
		fixture.persist();
		assertEquals(savedLive, fixture.manager.registry().require(live.agentId()),
				"repeating final persistence does not revise an unchanged saved body");
		return 8;
	}

	private static Fixture fixture() {
		try {
			Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
			CodexAgentManager manager = (CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class);
			MinecraftServer server = (MinecraftServer) unsafe.allocateInstance(DedicatedServer.class);
			FixturePlayers players = (FixturePlayers) unsafe.allocateInstance(FixturePlayers.class);
			players.connected = new LinkedHashMap<>();
			set(unsafe, server, MinecraftServer.class, "playerList", players);
			set(unsafe, manager, CodexAgentManager.class, "server", server);
			set(unsafe, manager, CodexAgentManager.class, "savedData", new AgentSavedData());
			set(unsafe, manager, CodexAgentManager.class, "lastLocationPersistenceEpochMs", new LinkedHashMap<>());
			ServerLevel level = (ServerLevel) unsafe.allocateInstance(ServerLevel.class);
			set(unsafe, level, Level.class, "dimension", Level.OVERWORLD);
			return new Fixture(unsafe, manager, players, level);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not create shutdown location fixture", exception);
		}
	}

	private static void set(sun.misc.Unsafe unsafe, Object target, Class<?> owner, String name, Object value)
			throws ReflectiveOperationException {
		unsafe.putObject(target, unsafe.objectFieldOffset(owner.getDeclaredField(name)), value);
	}

	private static final class Fixture {
		private final sun.misc.Unsafe unsafe;
		private final CodexAgentManager manager;
		private final FixturePlayers players;
		private final ServerLevel level;
		private final AgentEntityLocation oldLocation = AgentEntityLocation.exact(
				"minecraft:overworld", 0, 0, 8.5D, 70.0D, 8.5D, 0.0F, 0.0F);
		private final AgentEntityLocation finalLocation = AgentEntityLocation.exact(
				"minecraft:overworld", -2, 2, -16.25D, 70.75D, 32.5D, 120.0F, -15.0F);

		private Fixture(sun.misc.Unsafe unsafe, CodexAgentManager manager, FixturePlayers players, ServerLevel level) {
			this.unsafe = unsafe;
			this.manager = manager;
			this.players = players;
			this.level = level;
		}

		private UUID uuid(AgentRecord record) {
			return OfflineAgentPlayers.offlineUuid(record.agentId(), record.profile());
		}

		@SuppressWarnings("unchecked")
		private AgentRecord create(String name, boolean alive) {
			try {
				AgentRecord record = manager.registry().create("gpt-5.6-sol", "high", Optional.of(name), 1_000L);
				EntityPlayerMPFake player = (EntityPlayerMPFake) unsafe.allocateInstance(EntityPlayerMPFake.class);
				set(unsafe, player, Entity.class, "uuid", uuid(record));
				set(unsafe, player, Player.class, "gameProfile", new GameProfile(uuid(record), name));
				set(unsafe, player, Entity.class, "level", level);
				set(unsafe, player, Entity.class, "position", new Vec3(-16.25D, 70.75D, 32.5D));
				set(unsafe, player, Entity.class, "chunkPosition", new ChunkPos(-2, 2));
				unsafe.putFloat(player, unsafe.objectFieldOffset(Entity.class.getDeclaredField("yRot")), 120.0F);
				unsafe.putFloat(player, unsafe.objectFieldOffset(Entity.class.getDeclaredField("xRot")), -15.0F);
				Field healthField = LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
				healthField.setAccessible(true);
				EntityDataAccessor<Float> health = (EntityDataAccessor<Float>) healthField.get(null);
				var items = new SynchedEntityData.DataItem<?>[health.id() + 1];
				items[health.id()] = new SynchedEntityData.DataItem<>(health, alive ? 20.0F : 0.0F);
				var dataConstructor = SynchedEntityData.class.getDeclaredConstructor(
						net.minecraft.network.syncher.SyncedDataHolder.class, SynchedEntityData.DataItem[].class);
				dataConstructor.setAccessible(true);
				set(unsafe, player, Entity.class, "entityData", dataConstructor.newInstance(player, items));
				players.connected.put(uuid(record), player);
				return record;
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError("could not allocate managed fake player", exception);
			}
		}

		private void persist() {
			try {
				var writer = CodexAgentManager.class.getDeclaredMethod("persistLiveAgentLocations");
				writer.setAccessible(true);
				writer.invoke(manager);
			} catch (InvocationTargetException exception) {
				throw new AssertionError("shutdown location persistence failed", exception.getCause());
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError("could not invoke shutdown location writer", exception);
			}
		}
	}

	private static final class FixturePlayers extends DedicatedPlayerList {
		private Map<UUID, ServerPlayer> connected;
		private FixturePlayers() { super(null, null, null); }
		@Override public ServerPlayer getPlayer(UUID uuid) { return connected.get(uuid); }
		@Override public ServerPlayer getPlayerByName(String name) {
			return connected.values().stream().filter(player -> player.getGameProfile().name().equals(name)).findFirst().orElse(null);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
	}
}
