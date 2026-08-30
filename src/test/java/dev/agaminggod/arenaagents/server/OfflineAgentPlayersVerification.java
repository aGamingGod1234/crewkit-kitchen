package dev.agaminggod.arenaagents.server;

import java.util.UUID;
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
		UUID expectedUuid = UUID.fromString("6eb46a0e-9bd1-33e1-8fa7-d1280f08577c");
		String expectedName = "c02_193A9ADD";
		assertTrue(OfflineAgentPlayers.matchesManagedIdentity(
				true, expectedUuid, expectedName, expectedUuid, expectedName),
				"exact Carpet fake-player identity is accepted");
		assertTrue(!OfflineAgentPlayers.matchesManagedIdentity(
				false, expectedUuid, expectedName, expectedUuid, expectedName),
				"human player with a reserved UUID and name is rejected");
		assertTrue(!OfflineAgentPlayers.matchesManagedIdentity(
				true, UUID.randomUUID(), expectedName, expectedUuid, expectedName),
				"fake player with only the reserved name is rejected");
		assertTrue(!OfflineAgentPlayers.matchesManagedIdentity(
				true, expectedUuid, "ordinary_player", expectedUuid, expectedName),
				"fake player with only the reserved UUID is rejected");
		return 6;
	}

	private static void assertEquals(float expected, float actual, String label) {
		if (Math.abs(expected - actual) > 0.001F) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}
}
