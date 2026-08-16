package dev.agaminggod.arenaagents.server;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class OfflineAgentPlayersVerification {
	private OfflineAgentPlayersVerification() {
	}

	public static int verify() {
		assertEquals(-180.0F,
				OfflineAgentPlayers.calculateRespawnYaw(new Vec3(0.5D, 65.0D, 1.5D), new BlockPos(0, 64, 0)),
				"respawn yaw matches vanilla look-at calculation");
		assertEquals(90.0F,
				OfflineAgentPlayers.calculateRespawnYaw(new Vec3(1.5D, 65.0D, 0.5D), new BlockPos(0, 64, 0)),
				"respawn yaw points toward the saved bed or anchor");
		return 2;
	}

	private static void assertEquals(float expected, float actual, String label) {
		if (Math.abs(expected - actual) > 0.001F) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
