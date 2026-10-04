package dev.agaminggod.arenaagents.server.runtime.input;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** No world: real Carpet scheduling/setters and concrete sink callbacks, with player effects recorded. */
public final class CarpetInputStateVerification {
	private CarpetInputStateVerification() { }

	public static void main(String[] args) {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.out.println("PASS: " + verify() + " concrete Carpet input assertions");
	}

	public static int verify() {
		try {
			return verifyMovementAndRelease() + verifyAttackPressAndHold() + verifyAttackTargetHandoff();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect pinned Carpet action state", exception);
		}
	}

	private static int verifyMovementAndRelease() throws ReflectiveOperationException {
		FixturePlayer player = player();
		EntityPlayerActionPack pack = player.pack;
		AgentInputState sprinting = state(false, false, true, true);
		CarpetInputStateSink.applyMovement(pack, player, sprinting);
		check(player.sprinting && !player.sneaking, "initial sprint reaches the player");
		AgentInputState both = state(true, true, true, true);
		CarpetInputStateSink.applyMovement(pack, player, both);
		check(player.sneaking && !player.sprinting, "real Carpet setters retain crouch over sprint");
		check(player.input.shift() && !player.input.sprint(), "effective client input matches physical crouch");
		CarpetInputStateSink.applyHeldActions(pack, null, both);
		EntityPlayerActionPack.Action attack = actions(pack).get(EntityPlayerActionPack.ActionType.ATTACK);
		check((boolean) field(EntityPlayerActionPack.Action.class, "isContinuous").get(attack),
				"held mining uses pinned Carpet continuous scheduling, never interval(1)");
		BlockPos mining = new BlockPos(1, 64, 2);
		field(EntityPlayerActionPack.class, "currentBlock").set(pack, mining);
		field(EntityPlayerActionPack.class, "curBlockDamageMP").setFloat(pack, .625F);
		AgentInputState releasedJump = state(false, true, true, true);
		CarpetInputStateSink.applyHeldActions(pack, both, releasedJump);
		check(actions(pack).get(EntityPlayerActionPack.ActionType.ATTACK) == attack,
				"jump release preserves the exact scheduled attack object");
		check(!actions(pack).containsKey(EntityPlayerActionPack.ActionType.JUMP), "jump alone is unscheduled");
		check(field(EntityPlayerActionPack.class, "currentBlock").get(pack) == mining,
				"jump release never enters Carpet's mining abort path");
		check(field(EntityPlayerActionPack.class, "curBlockDamageMP").getFloat(pack) == .625F,
				"jump release preserves accumulated mining damage");
		CarpetInputStateSink.applyHeldActions(pack, releasedJump, releasedJump);
		check(actions(pack).get(EntityPlayerActionPack.ActionType.ATTACK) == attack, "unchanged hold never restarts attack");
		// Release with no active block exercises the real ATTACK.stop/inactiveTick without creating a world.
		field(EntityPlayerActionPack.class, "currentBlock").set(pack, null);
		AgentInputState resumedJump = state(true, false, false, true);
		CarpetInputStateSink.applyHeldActions(pack, releasedJump, resumedJump);
		EntityPlayerActionPack.Action jump = actions(pack).get(EntityPlayerActionPack.ActionType.JUMP);
		CarpetInputStateSink.applyHeldActions(pack, resumedJump, state(true, false, false, false));
		check(!actions(pack).containsKey(EntityPlayerActionPack.ActionType.ATTACK), "attack release removes only attack");
		check(actions(pack).get(EntityPlayerActionPack.ActionType.JUMP) == jump, "attack release retains the exact held jump action");
		return 11;
	}

	private static int verifyAttackPressAndHold() throws ReflectiveOperationException {
		FixturePlayer player = player();
		AgentInputState held = state(false, false, false, true);
		AgentInputState idle = state(false, false, false, false);
		CarpetInputStateSink.applyHeldActions(player.pack, null, held);
		EntityPlayerActionPack.Action action = actions(player.pack).get(EntityPlayerActionPack.ActionType.ATTACK);
		HitResult target = new EntityHitResult(player, Vec3.ZERO);
		check(Boolean.TRUE.equals(tick(player, action, target)), "first authored press performs an entity hit");
		check(player.hits == 1 && player.swings == 1 && player.strengthResets == 1, "press reaches real player-effect calls once");
		for (int tick = 0; tick < 5; tick++) tick(player, action, target);
		check(player.hits == 1 && player.strengthResets == 1, "held entity attack neither repeats nor resets recharge");
		CarpetInputStateSink.applyHeldActions(player.pack, held, idle);
		CarpetInputStateSink.applyHeldActions(player.pack, idle, held);
		action = actions(player.pack).get(EntityPlayerActionPack.ActionType.ATTACK);
		tick(player, action, target);
		check(player.hits == 2, "release/repress performs the next melee hit");

		CarpetInputStateSink.applyHeldActions(player.pack, held, idle);
		CarpetInputStateSink.applyHeldActions(player.pack, idle, held);
		action = actions(player.pack).get(EntityPlayerActionPack.ActionType.ATTACK);
		HitResult block = new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false);
		for (int tick = 0; tick < 5; tick++) tick(player, action, block);
		check(player.originalTicks == 5, "block holds delegate every tick to the continuous Carpet action");
		tick(player, action, target);
		check(player.hits == 2, "moving from a held block to an entity is not a new press");
		tick(player, EntityPlayerActionPack.Action.once(), target);
		check(player.originalTicks == 6, "non-authored Carpet attacks keep their original execution");

		CarpetInputStateSink.applyHeldActions(player.pack, held, idle);
		CarpetInputStateSink.applyHeldActions(player.pack, idle, held);
		EntityPlayerActionPack.Action pending = actions(player.pack).get(EntityPlayerActionPack.ActionType.ATTACK);
		ExactHandUseDriver use = new ExactHandUseDriver();
		UseFixture access = new UseFixture();
		AgentId agent = AgentId.random();
		use.arbitrate(agent, InteractionHand.OFF_HAND, access, () -> tick(player, pending, target), 1);
		check(player.hits == 2 && access.lastHand == InteractionHand.OFF_HAND,
				"successful exact off-hand use suppresses the entity press");
		access.consume = false;
		for (int tick = 2; tick <= 5; tick++) {
			use.arbitrate(agent, InteractionHand.OFF_HAND, access, () -> tick(player, pending, target), tick);
		}
		check(player.hits == 3, "press executes when exact-hand use stops consuming the attack slot");
		use.arbitrate(agent, InteractionHand.OFF_HAND, access, () -> tick(player, pending, target), 5);
		check(player.hits == 3, "same-tick arbitration never duplicates the press");
		CarpetInputStateSink.applyHeldActions(player.pack, held, idle);
		CarpetInputStateSink.applyHeldActions(player.pack, idle, held);
		action = actions(player.pack).get(EntityPlayerActionPack.ActionType.ATTACK);
		player.inRange = false;
		tick(player, action, target);
		check(player.hits == 3 && player.strengthResets == 3, "out-of-reach targets neither hit nor reset recharge");
		player.inRange = true;
		tick(player, action, target);
		check(player.hits == 3, "walking into reach without another press does not create an entity hit");
		CarpetInputStateSink.applyHeldActions(player.pack, held, idle);
		CarpetInputStateSink.applyHeldActions(player.pack, idle, held);
		action = actions(player.pack).get(EntityPlayerActionPack.ActionType.ATTACK);
		player.spectator = true;
		tick(player, action, target);
		check(player.hits == 3 && player.originalTicks == 7, "spectator handling delegates to Carpet's existing spectator guard");
		return 13;
	}

	private static int verifyAttackTargetHandoff() throws ReflectiveOperationException {
		FixturePlayer player = player();
		FixturePlayer other = player();
		CarpetInputStateSink.applyHeldActions(player.pack, null, state(false, false, false, true));
		EntityPlayerActionPack.Action action = actions(player.pack).get(EntityPlayerActionPack.ActionType.ATTACK);
		HitResult first = new BlockHitResult(Vec3.ZERO, Direction.UP, BlockPos.ZERO, false);
		HitResult second = BlockHitResult.miss(Vec3.ZERO, Direction.UP, BlockPos.ZERO);
		for (HitResult hit : new HitResult[]{first, second}) {
			CarpetInputStateSink.tickAttack(player, action, () -> {
				check(CarpetInputStateSink.takeAttackTarget(other) == null, "another player cannot consume attack target");
				check(CarpetInputStateSink.takeAttackTarget(player) == hit, "original tick receives this invocation's exact target");
				check(CarpetInputStateSink.takeAttackTarget(player) == null, "attack target is consumed only once");
				return false;
			}, () -> hit);
			check(CarpetInputStateSink.takeAttackTarget(player) == null, "target never leaks across ticks or post-attack use");
		}
		try {
			CarpetInputStateSink.tickAttack(player, action, () -> { throw new IllegalStateException("owned attack failure"); }, () -> first);
			throw new AssertionError("injected attack must fail");
		} catch (IllegalStateException expected) { }
		check(CarpetInputStateSink.takeAttackTarget(player) == null, "failed original tick releases unconsumed target");
		return 9;
	}

	private static Boolean tick(FixturePlayer player, EntityPlayerActionPack.Action action, HitResult target) {
		return CarpetInputStateSink.tickAttack(player, action, () -> { player.originalTicks++; return false; }, () -> target);
	}

	private static AgentInputState state(boolean jump, boolean sneak, boolean sprint, boolean attack) {
		return new AgentInputState(1, 0, jump, sneak, sprint, attack, false, 0, 0, 0, InteractionHand.MAIN_HAND);
	}

	@SuppressWarnings("unchecked")
	private static Map<EntityPlayerActionPack.ActionType, EntityPlayerActionPack.Action> actions(EntityPlayerActionPack pack)
			throws ReflectiveOperationException {
		return (Map<EntityPlayerActionPack.ActionType, EntityPlayerActionPack.Action>) field(EntityPlayerActionPack.class, "actions").get(pack);
	}

	private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static FixturePlayer player() throws ReflectiveOperationException {
		sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
		FixturePlayer player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
		player.inRange = true;
		player.pack = new EntityPlayerActionPack(player) {
			@Override public void setSlot(int slot) { } // Packet/inventory transport is unrelated to these key transitions.
		};
		return player;
	}

	private static final class FixturePlayer extends ServerPlayer implements ServerPlayerInterface {
		private EntityPlayerActionPack pack;
		private boolean sneaking;
		private boolean sprinting;
		private boolean inRange, spectator;
		private Input input;
		private int hits, swings, strengthResets, originalTicks;
		private FixturePlayer() { super(null, null, null, null); }
		@Override public void setShiftKeyDown(boolean value) { sneaking = value; }
		@Override public void setSprinting(boolean value) { sprinting = value; }
		@Override public void setLastClientInput(Input value) { input = value; }
		@Override public void setJumping(boolean value) { }
		@Override public boolean isSpectator() { return spectator; }
		@Override public boolean isWithinEntityInteractionRange(Entity entity, double padding) { return inRange; }
		@Override public void attack(Entity entity) { hits++; }
		@Override public void swing(InteractionHand hand) { swings++; }
		@Override public void resetAttackStrengthTicker() { strengthResets++; }
		@Override public void resetLastActionTime() { }
		@Override public EntityPlayerActionPack getActionPack() { return pack; }
		@Override public void invalidateEntityObjectReference() { }
		@Override public boolean isInvalidEntityObject() { return false; }
	}

	private static final class UseFixture implements ExactHandUseDriver.PlayerUseAccess {
		private boolean consume = true;
		private InteractionHand lastHand;
		@Override public boolean isUsingItem() { return false; }
		@Override public InteractionHand usedHand() { return null; }
		@Override public void releaseUsingItem() { }
		@Override public ExactHandUseDriver.TargetKind target() { return ExactHandUseDriver.TargetKind.MISS; }
		@Override public ExactHandUseDriver.TargetAttempt useBlock(InteractionHand hand) { throw new AssertionError(); }
		@Override public ExactHandUseDriver.TargetAttempt useEntity(InteractionHand hand) { throw new AssertionError(); }
		@Override public boolean useItem(InteractionHand hand) { lastHand = hand; return consume; }
		@Override public void swing(InteractionHand hand) { }
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
