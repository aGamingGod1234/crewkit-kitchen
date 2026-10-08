package dev.agaminggod.arenaagents.server.perception;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.runtime.ServerActionExecutor;
import dev.agaminggod.arenaagents.world.WorldMutationRevisions;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;

/** Real collector caches, without loading a world or invoking a gameplay provider. */
public final class TerminalObservationCacheVerification {
	public static void main(String[] args) throws Exception {
		System.out.println("TerminalObservationCacheVerification assertions=" + verify());
	}

	@SuppressWarnings("unchecked")
	public static int verify() throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
		var collector = new ServerObservationCollector(
				(CodexAgentManager) unsafe.allocateInstance(CodexAgentManager.class),
				(ServerActionExecutor) unsafe.allocateInstance(ServerActionExecutor.class));
		var spatial = (ObservationSectionCache<RawSpatialObservation.Key, RawSpatialObservation>) field(collector, "spatialCache");
		var landmarks = (ObservationSectionCache<ServerObservationCollector.LandmarkSampleKey, ServerObservationCollector.SightSample>) field(collector, "landmarkCache");
		var rawStates = (Map<AgentId, Object>) field(collector, "lastRawStates");
		var inventories = (Map<AgentId, Object>) field(collector, "lastInventories");
		var keys = (Map<AgentId, RawSpatialObservation.Key>) field(collector, "spatialKeys");
		var agent = AgentId.random();
		var other = AgentId.random();
		var revisions = new WorldMutationRevisions();
		var position = new BlockPos(0, 64, 0);
		long revision = revisions.revision(0, 0, 6);
		var spatialKey = ServerObservationCollector.spatialKey(agent, "minecraft:overworld", position, revision);
		var landmarkKey = new ServerObservationCollector.LandmarkSampleKey(agent, "minecraft:overworld",
				0, 64, 0, .5, 65.62, .5, 0F, 0F, 64, false, Items.AIR, revision);
		var terrainLoads = new AtomicInteger();
		var landmarkLoads = new AtomicInteger();
		java.util.function.Supplier<RawSpatialObservation> loadTerrain = () -> {
			terrainLoads.incrementAndGet(); return new RawSpatialObservation(List.of(), List.of());
		};
		java.util.function.Supplier<ServerObservationCollector.SightSample> loadLandmarks = () -> {
			landmarkLoads.incrementAndGet(); return new ServerObservationCollector.SightSample(List.of());
		};
		spatial.getOrCompute(spatialKey, 10, loadTerrain);
		landmarks.getOrCompute(landmarkKey, 10, loadLandmarks);
		keys.put(agent, spatialKey);
		rawStates.put(agent, null); inventories.put(agent, null);
		rawStates.put(other, null); inventories.put(other, null);
		collector.invalidatePlayerState(agent);
		spatial.getOrCompute(spatialKey, 11, loadTerrain);
		landmarks.getOrCompute(landmarkKey, 11, loadLandmarks);
		require(terrainLoads.get() == 1 && landmarkLoads.get() == 1, "non-spatial terminal refresh reuses proven geometry");
		require(!rawStates.containsKey(agent) && !inventories.containsKey(agent), "player and inventory snapshots refresh");
		require(rawStates.containsKey(other) && inventories.containsKey(other), "another agent's snapshots remain intact");
		require(keys.containsKey(agent), "terminal refresh preserves the geometry identity");
		revisions.recordMutation(1, 1);
		var mutated = ServerObservationCollector.spatialKey(agent, "minecraft:overworld", position, revisions.revision(0, 0, 6));
		spatial.getOrCompute(mutated, 11, loadTerrain);
		require(terrainLoads.get() == 2, "real world mutation refreshes geometry in the same tick");
		var moved = ServerObservationCollector.spatialKey(agent, "minecraft:overworld", position.offset(1, 0, 0), revision);
		spatial.getOrCompute(moved, 11, loadTerrain);
		require(terrainLoads.get() == 3, "position change cannot reuse old geometry");
		collector.invalidate(agent);
		spatial.getOrCompute(spatialKey, 11, loadTerrain);
		landmarks.getOrCompute(landmarkKey, 11, loadLandmarks);
		require(terrainLoads.get() == 4 && landmarkLoads.get() == 2, "lifecycle invalidation still clears both terrain caches");
		require(!keys.containsKey(agent), "lifecycle invalidation clears the geometry identity");
		return 8;
	}

	private static Object field(Object owner, String name) throws Exception {
		var field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
