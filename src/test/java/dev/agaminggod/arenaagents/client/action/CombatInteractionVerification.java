package dev.agaminggod.arenaagents.client.action;

import dev.agaminggod.arenaagents.client.combat.CombatController;
import dev.agaminggod.arenaagents.client.combat.CombatTarget;
import dev.agaminggod.arenaagents.client.combat.TargetSelector;
import dev.agaminggod.arenaagents.client.combat.WeaponCandidate;
import dev.agaminggod.arenaagents.client.combat.WeaponSelector;
import dev.agaminggod.arenaagents.client.interaction.BlockInteractionPreconditions;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.protocol.ActionState;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

public final class CombatInteractionVerification {
	private static final UUID OPPONENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID ZOMBIE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
	private static final long ACTION_TIMEOUT_MS = 5_000L;

	private CombatInteractionVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifyTargetSelection();
		assertions += verifyWeaponSelection();
		assertions += verifyCombatPhases();
		assertions += verifyBlockPreconditions();
		assertions += verifyAttackLifecycle();
		assertions += verifyBlockActions();
		return assertions;
	}

	private static int verifyTargetSelection() {
		CombatTarget fartherPlayer = target(OPPONENT_ID, "Opponent", "minecraft:player", true, false, 9.0D);
		CombatTarget nearerZombie = target(ZOMBIE_ID, "Zombie", "minecraft:zombie", false, true, 4.0D);
		TargetSelector selector = new TargetSelector();
		List<CombatTarget> targets = List.of(fartherPlayer, nearerZombie);

		assertEquals(OPPONENT_ID, selector.select(targets, "player:Opponent").orElseThrow().uuid(), "named player target");
		assertEquals(OPPONENT_ID, selector.select(targets, "uuid:" + OPPONENT_ID).orElseThrow().uuid(), "UUID target");
		assertEquals(ZOMBIE_ID, selector.select(targets, "type:minecraft:zombie").orElseThrow().uuid(), "typed target");
		assertEquals(ZOMBIE_ID, selector.select(targets, "nearest_hostile").orElseThrow().uuid(), "nearest hostile target");
		assertEquals(true, selector.select(targets, "player:Missing").isEmpty(), "missing named target");
		return 5;
	}

	private static int verifyWeaponSelection() {
		WeaponSelector selector = new WeaponSelector();
		WeaponCandidate selected = selector.selectBest(List.of(
				new WeaponCandidate("minecraft:wooden_sword", 0),
				new WeaponCandidate("minecraft:diamond_sword", 4),
				new WeaponCandidate("minecraft:stone_axe", 2)
		)).orElseThrow();
		assertEquals("minecraft:diamond_sword", selected.itemId(), "best melee item");
		assertEquals(4, selected.slot(), "best melee slot");
		assertEquals(true, selector.selectBest(List.of(new WeaponCandidate("minecraft:apple", 0))).isEmpty(), "non-weapon ignored");
		return 3;
	}

	private static int verifyCombatPhases() {
		CombatController controller = new CombatController();
		CombatTarget outsideReach = target(OPPONENT_ID, "Opponent", "minecraft:player", true, false, 16.0D);
		CombatTarget insideReach = target(OPPONENT_ID, "Opponent", "minecraft:player", true, false, 4.0D);
		assertEquals(CombatController.Phase.APPROACH, controller.phase(outsideReach, 3.0D, false, 0.0F), "approach outside reach");
		assertEquals(CombatController.Phase.FACE, controller.phase(insideReach, 3.0D, false, 1.0F), "face inside reach");
		assertEquals(CombatController.Phase.WAIT_COOLDOWN, controller.phase(insideReach, 3.0D, true, 0.5F), "wait for cooldown");
		assertEquals(CombatController.Phase.ATTACK, controller.phase(insideReach, 3.0D, true, 1.0F), "attack when ready");
		return 4;
	}

	private static int verifyBlockPreconditions() {
		assertEquals("CHUNK_NOT_LOADED", BlockInteractionPreconditions.check(snapshot(false, true, true, true), false).reasonCode(), "loaded chunk required");
		assertEquals("BLOCK_OUT_OF_REACH", BlockInteractionPreconditions.check(snapshot(true, false, true, true), false).reasonCode(), "survival reach required");
		assertEquals("BLOCK_NOT_VISIBLE", BlockInteractionPreconditions.check(snapshot(true, true, false, true), false).reasonCode(), "visible face required");
		assertEquals("BLOCK_MISSING", BlockInteractionPreconditions.check(snapshot(true, true, true, false), false).reasonCode(), "break block must exist");
		assertEquals(true, BlockInteractionPreconditions.check(snapshot(true, true, true, true), true).successful(), "valid place support");
		return 5;
	}

	private static int verifyAttackLifecycle() {
		FakeContext context = new FakeContext();
		context.targets = List.of(target(OPPONENT_ID, "Opponent", "minecraft:player", true, false, 4.0D));
		context.weapons = List.of(new WeaponCandidate("minecraft:diamond_sword", 2));
		AttackAction action = new AttackAction("player:Opponent", ACTION_TIMEOUT_MS);

		ActionUpdate attacking = action.tick(context, 0L);
		assertEquals(ActionState.RUNNING, attacking.state(), "attack remains active after swing");
		assertEquals(1, context.attackCalls, "ordinary attack requested once");
		assertEquals("minecraft:diamond_sword", context.selectedItemId, "best weapon selected");

		context.targets = List.of(new CombatTarget(
				OPPONENT_ID,
				"Opponent",
				"minecraft:player",
				true,
				false,
				false,
				2.0D,
				64.0D,
				0.0D,
				65.6D,
				4.0D
		));
		ActionUpdate defeated = action.tick(context, 50L);
		assertEquals(ActionState.SUCCEEDED, defeated.state(), "dead target completes attack");
		assertEquals("TARGET_DEFEATED", defeated.reasonCode(), "dead target result code");

		FakeContext missingContext = new FakeContext();
		ActionUpdate missing = new AttackAction("player:Missing", ACTION_TIMEOUT_MS).tick(missingContext, 0L);
		assertEquals(ActionState.FAILED, missing.state(), "missing target fails explicitly");
		assertEquals("TARGET_GONE", missing.reasonCode(), "missing target reason");

		int stopsBeforeCancel = context.movementStopCalls;
		action.cancel(context);
		assertEquals(stopsBeforeCancel + 1, context.movementStopCalls, "attack cancel stops movement");
		return 8;
	}

	private static int verifyBlockActions() {
		FakeContext breakContext = new FakeContext();
		breakContext.blockSnapshot = snapshot(true, true, true, true);
		breakContext.breakResults.add(ActionContext.BlockProgress.running("breaking"));
		breakContext.breakResults.add(ActionContext.BlockProgress.succeeded("BLOCK_BROKEN", "Block broken"));
		BreakBlockAction breakAction = new BreakBlockAction(1, 64, 2, ACTION_TIMEOUT_MS);
		assertEquals(ActionState.RUNNING, breakAction.tick(breakContext, 0L).state(), "block breaking is incremental");
		assertEquals(ActionState.SUCCEEDED, breakAction.tick(breakContext, 50L).state(), "block break succeeds after server progress");

		FakeContext placeContext = new FakeContext();
		placeContext.blockSnapshot = snapshot(true, true, true, true);
		PlaceBlockAction placeAction = new PlaceBlockAction(1, 64, 2, "up", "minecraft:stone");
		ActionUpdate placed = placeAction.tick(placeContext, 0L);
		assertEquals(ActionState.SUCCEEDED, placed.state(), "block place succeeds");
		assertEquals("minecraft:stone", placeContext.selectedItemId, "placement selects matching item");
		assertEquals(1, placeContext.placeCalls, "ordinary placement requested once");

		FakeContext unsafeContext = new FakeContext();
		unsafeContext.blockSnapshot = snapshot(false, false, false, false);
		ActionUpdate unavailable = new BreakBlockAction(1, 64, 2, ACTION_TIMEOUT_MS).tick(unsafeContext, 0L);
		assertEquals(ActionState.FAILED, unavailable.state(), "uncached block fails");
		assertEquals("CHUNK_NOT_LOADED", unavailable.reasonCode(), "uncached block reason");
		return 7;
	}

	private static CombatTarget target(
			UUID uuid,
			String name,
			String typeId,
			boolean player,
			boolean hostile,
			double distanceSquared
	) {
		return new CombatTarget(uuid, name, typeId, player, hostile, true, 2.0D, 64.0D, 0.0D, 65.6D, distanceSquared);
	}

	private static ActionContext.BlockInteractionSnapshot snapshot(
			boolean chunkLoaded,
			boolean withinReach,
			boolean visible,
			boolean blockPresent
	) {
		return new ActionContext.BlockInteractionSnapshot(
				chunkLoaded,
				withinReach,
				visible,
				blockPresent,
				ActionContext.BlockFace.UP
		);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static final class FakeContext implements ActionContext {
		private List<CombatTarget> targets = List.of();
		private List<WeaponCandidate> weapons = List.of();
		private BlockInteractionSnapshot blockSnapshot = snapshot(true, true, true, true);
		private final ArrayDeque<BlockProgress> breakResults = new ArrayDeque<>();
		private int attackCalls;
		private int placeCalls;
		private int releaseCalls;
		private int movementStopCalls;
		private String selectedItemId;

		@Override
		public boolean isClientThread() {
			return true;
		}

		@Override
		public long monotonicTimeMs() {
			return 0L;
		}

		@Override
		public long epochTimeMs() {
			return 0L;
		}

		@Override
		public SafetyState safetyState() {
			return SafetyState.READY;
		}

		@Override
		public NavigationSnapshot navigationSnapshot() {
			return new NavigationSnapshot(0.0D, 64.0D, 0.0D, 0.0F, 0.0F, new GridPosition(0, 64, 0));
		}

		@Override
		public WalkabilityView walkabilityView() {
			return position -> position.y() == 63 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		}

		@Override
		public void setMovement(MovementInput movement) {
			if (!movement.active()) {
				movementStopCalls++;
			}
		}

		@Override
		public LookResult lookAt(double x, double y, double z, float maxYawDelta, float maxPitchDelta, float toleranceDegrees) {
			return new LookResult(true, 0.0F, 0.0F);
		}

		@Override
		public CombatSnapshot combatSnapshot() {
			return new CombatSnapshot(targets, weapons, 1.0F, 3.0D);
		}

		@Override
		public OperationResult attackTarget(UUID targetId) {
			attackCalls++;
			return OperationResult.succeeded("ATTACK_SENT", "Attack sent");
		}

		@Override
		public BlockInteractionSnapshot inspectBlock(GridPosition position) {
			return blockSnapshot;
		}

		@Override
		public BlockProgress breakBlock(GridPosition position, BlockFace face) {
			return breakResults.removeFirst();
		}

		@Override
		public OperationResult placeBlock(GridPosition position, BlockFace face, String itemId) {
			placeCalls++;
			return OperationResult.succeeded("BLOCK_PLACED", "Block placed");
		}

		@Override
		public OperationResult sendChat(String message) {
			return OperationResult.succeeded("CHAT_SENT", "Chat sent");
		}

		@Override
		public OperationResult selectHotbarItem(String itemId) {
			selectedItemId = itemId;
			return OperationResult.succeeded("ITEM_SELECTED", "Item selected");
		}

		@Override
		public OperationResult startUsingItem(Hand hand) {
			return OperationResult.succeeded("ITEM_USE_STARTED", "Item use started");
		}

		@Override
		public void stopUsingItem() {
		}

		@Override
		public void releaseAll() {
			releaseCalls++;
		}
	}
}
