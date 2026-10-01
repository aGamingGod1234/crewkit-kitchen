package dev.agaminggod.arenaagents.server;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.PlayerSpawnFinder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

final class AgentSpawnPlacement {
	static final double SPAWN_DISTANCE = 2.0D;
	private static final double MIN_HORIZONTAL_LENGTH_SQUARED = 1.0E-6D;
	private static final Vec3 DEFAULT_FORWARD = new Vec3(0.0D, 0.0D, 1.0D);

	private AgentSpawnPlacement() {
	}

	static Vec3 inFrontOf(Vec3 origin, Vec3 lookDirection) {
		Objects.requireNonNull(origin, "origin must not be null");
		Objects.requireNonNull(lookDirection, "lookDirection must not be null");
		Vec3 forward = horizontalForward(lookDirection);
		return origin.add(forward.scale(SPAWN_DISTANCE));
	}

	static CompletableFuture<Vec3> availableNear(ServerLevel level, Vec3 origin, Vec3 lookDirection) {
		Objects.requireNonNull(level, "level must not be null");
		// Use the same terrain, player dimensions, spawn radius and chunk loading as vanilla player joins.
		return PlayerSpawnFinder.findSpawn(level, BlockPos.containing(inFrontOf(origin, lookDirection)));
	}

	private static Vec3 horizontalForward(Vec3 lookDirection) {
		Vec3 horizontal = new Vec3(lookDirection.x, 0.0D, lookDirection.z);
		return horizontal.lengthSqr() < MIN_HORIZONTAL_LENGTH_SQUARED
				? DEFAULT_FORWARD
				: horizontal.normalize();
	}
}
