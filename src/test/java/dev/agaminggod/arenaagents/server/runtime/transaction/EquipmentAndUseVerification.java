package dev.agaminggod.arenaagents.server.runtime.transaction;

import dev.agaminggod.arenaagents.server.runtime.controller.ServerRangedUseController;
import java.util.Map;
import java.util.UUID;

public final class EquipmentAndUseVerification {
	private EquipmentAndUseVerification() {
	}

	public static int verify() {
		UseConfirmation confirmation = UseConfirmation.initial();
		assertFalse(confirmation.observeUsing(false).confirmed(), "shield release without observed start fails closed");
		confirmation = confirmation.observeUsing(true);
		assertTrue(confirmation.observedStart(), "shield use start is observed");
		confirmation = confirmation.observeUsing(false);
		assertTrue(confirmation.confirmed(), "shield use release is observed after start");

		UUID owner = UUID.randomUUID();
		UUID otherOwner = UUID.randomUUID();
		UUID existingArrow = UUID.randomUUID();
		UUID newOwnedArrow = UUID.randomUUID();
		UUID newForeignArrow = UUID.randomUUID();
		assertFalse(ServerRangedUseController.confirmOwnedProjectile(
				100, Map.of(99, owner), owner), "pre-existing owned arrow cannot confirm a shot");
		assertFalse(ServerRangedUseController.confirmOwnedProjectile(
				100, Map.of(101, otherOwner), owner), "foreign arrow cannot confirm a shot");
		assertTrue(ServerRangedUseController.confirmOwnedProjectile(
				100, Map.of(99, owner, 101, owner), owner), "new owned arrow confirms a shot");
		assertFalse(ServerRangedUseController.confirmOwnedProjectile(
				100, Map.of(80, owner), owner), "owned arrow outside the initial local scan stays pre-existing by entity ID");

		ServerTransactionAdapter.ConfirmedUseTimer timer = new ServerTransactionAdapter.ConfirmedUseTimer();
		assertFalse(timer.durationElapsed(5_000L, 250L), "duration cannot elapse before observed use start");
		timer.observeStarted(true, 5_000L);
		assertFalse(timer.durationElapsed(5_000L, 1L), "use cannot start and stop in the same tick");
		assertFalse(timer.durationElapsed(5_249L, 250L), "requested duration begins at observed use start");
		assertTrue(timer.durationElapsed(5_250L, 250L), "requested duration elapses after confirmed start");

		assertTrue(ServerRangedUseController.canStartBowUse(false, true),
				"infinite-material players may start vanilla bow use without an inventory arrow");
		assertFalse(ServerRangedUseController.canStartBowUse(false, false),
				"survival bow use without an arrow fails closed");
		assertFalse(new ServerRangedUseController.TargetFacts(true, true, false, false, false, true).eligible(),
				"invulnerable ranged target is rejected");
		assertFalse(new ServerRangedUseController.TargetFacts(false, false, false, false, false, true).eligible(),
				"ranged protection denial is enforced");
		assertFalse(new ServerRangedUseController.TargetFacts(true, false, true, false, false, true).eligible(),
				"creative player target is rejected explicitly");
		assertFalse(new ServerRangedUseController.TargetFacts(true, false, false, true, false, true).eligible(),
				"spectator player target is rejected explicitly");
		assertFalse(new ServerRangedUseController.TargetFacts(true, false, false, false, true, true).eligible(),
				"ability-invulnerable player target is rejected explicitly");
		assertTrue(new ServerRangedUseController.TargetFacts(true, false, false, false, false, true).eligible(),
				"protected and projectile-hittable target is eligible");
		return 19;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}
}
