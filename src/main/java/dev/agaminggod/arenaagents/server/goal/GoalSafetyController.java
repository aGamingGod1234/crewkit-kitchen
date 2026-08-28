package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import dev.agaminggod.arenaagents.client.navigation.GridPosition;
import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.runtime.controller.MinecraftNavigationWorld;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Closed, non-strategic body reflex authority for immediately dangerous states. */
public final class GoalSafetyController {
	private static final int SAFETY_PRIORITY = 1_000;
	private static final long REPEATED_DAMAGE_WINDOW_TICKS = 40L;
	private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

	private final CodexAgentManager manager;
	private final LeasedServerInputController inputs;
	private final Map<AgentId, InputLease> leases = new HashMap<>();
	private final Map<AgentId, DamageState> damage = new HashMap<>();

	public GoalSafetyController(CodexAgentManager manager) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
		this.inputs = AgentInputRuntime.controller(manager.server());
	}

	public static SafetyDirective decide(HazardSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		if (snapshot.dead()) return SafetyDirective.RESPAWN_RECOVERY;
		if (snapshot.inLava()) return SafetyDirective.LEAVE_LAVA;
		if (snapshot.inWater() && snapshot.airSupply() <= snapshot.drowningThreshold()) return SafetyDirective.SWIM_UP;
		if (snapshot.onFire() && snapshot.verifiedEscapeRoute()) return SafetyDirective.LEAVE_FIRE;
		if (snapshot.repeatedDamage() && snapshot.equippedShield()) return SafetyDirective.RAISE_EQUIPPED_SHIELD;
		if (snapshot.repeatedDamage() && snapshot.verifiedRetreatRoute()) return SafetyDirective.RETREAT_FROM_REPEATED_DAMAGE;
		return SafetyDirective.NONE;
	}

	public void tick() {
		long tick = manager.server().getTickCount();
		Set<AgentId> retained = new HashSet<>();
		for (var record : manager.records()) {
			boolean goalNeedsWork = record.currentGoal()
					.map(goal -> goal.status() == GoalStatus.ACTIVE || goal.status() == GoalStatus.RECOVERING)
					.orElse(false);
			if (!goalNeedsWork) continue;
			ServerPlayer player = manager.findAgentPlayer(record.agentId()).orElse(null);
			if (player == null) continue;
			retained.add(record.agentId());
			Vec3 escape = verifiedEscape(player, null);
			LivingEntity attacker = player.getLastHurtByMob();
			Vec3 retreat = attacker == null ? null : verifiedEscape(player, attacker.position());
			boolean repeatedDamage = repeatedDamage(record.agentId(), player.getHealth(), tick);
			boolean shield = player.getMainHandItem().is(Items.SHIELD) || player.getOffhandItem().is(Items.SHIELD);
			SafetyDirective directive = decide(new HazardSnapshot(
					player.isDeadOrDying(), player.isInWater(), player.isInLava(), player.isOnFire(),
					Math.max(0, player.getAirSupply()), 40, repeatedDamage, shield, escape != null, retreat != null));
			apply(record.agentId(), player, directive, escape, retreat);
		}
		for (AgentId agentId : Set.copyOf(leases.keySet())) {
			if (!retained.contains(agentId)) release(agentId);
		}
		damage.keySet().retainAll(retained);
	}

	public void close() {
		for (AgentId agentId : Set.copyOf(leases.keySet())) release(agentId);
		damage.clear();
	}

	private void apply(AgentId agentId, ServerPlayer player, SafetyDirective directive, Vec3 escape, Vec3 retreat) {
		if (directive == SafetyDirective.NONE || directive == SafetyDirective.RESPAWN_RECOVERY) {
			release(agentId);
			return;
		}
		boolean immediatelyLethal = directive == SafetyDirective.SWIM_UP
				|| directive == SafetyDirective.LEAVE_LAVA || directive == SafetyDirective.LEAVE_FIRE;
		if (!immediatelyLethal && !leases.containsKey(agentId) && inputs.currentState(agentId).isPresent()) return;
		InteractionHand hand = player.getOffhandItem().is(Items.SHIELD) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		Vec3 target = switch (directive) {
			case SWIM_UP -> player.getEyePosition().add(0.0D, 3.0D, 0.0D);
			case LEAVE_LAVA, LEAVE_FIRE -> escape;
			case RETREAT_FROM_REPEATED_DAMAGE -> retreat;
			case RAISE_EQUIPPED_SHIELD -> player.getEyePosition().add(player.getLookAngle());
			case NONE, RESPAWN_RECOVERY -> null;
		};
		if (target == null) {
			release(agentId);
			return;
		}
		InputLease lease = leases.computeIfAbsent(agentId,
				ignored -> inputs.acquire(agentId, InputOwner.SYSTEM, SAFETY_PRIORITY));
		boolean shieldUse = directive == SafetyDirective.RAISE_EQUIPPED_SHIELD;
		float forward = shieldUse || directive == SafetyDirective.SWIM_UP ? 0.0F : 1.0F;
		AgentInputState state = AgentInputStates.safetyReflex(
				player, target, forward, directive == SafetyDirective.SWIM_UP, !shieldUse, shieldUse, hand);
		inputs.apply(lease, state);
	}

	private boolean repeatedDamage(AgentId agentId, float health, long tick) {
		DamageState previous = damage.get(agentId);
		DamageState next = trackDamage(previous, health, tick);
		damage.put(agentId, next);
		return next.repeatedAt(tick);
	}

	static DamageState trackDamage(DamageState previous, float health, long tick) {
		boolean damaged = previous != null && health < previous.health();
		boolean withinWindow = previous != null && previous.withinWindow(tick);
		int streak = damaged && withinWindow ? previous.streak() + 1 : damaged ? 1 : previous == null ? 0 : previous.streak();
		long lastDamageTick = damaged ? tick : previous == null ? Long.MIN_VALUE : previous.lastDamageTick();
		return new DamageState(health, lastDamageTick, streak);
	}

	private Vec3 verifiedEscape(ServerPlayer player, Vec3 threat) {
		var origin = player.blockPosition();
		GridPosition selected = selectEscapeCandidate(
				new GridPosition(origin.getX(), origin.getY(), origin.getZ()),
				player.position(),
				threat,
				new MinecraftNavigationWorld(player.level())
		);
		return selected == null ? null : new Vec3(selected.x() + 0.5D, selected.y(), selected.z() + 0.5D);
	}

	static GridPosition selectEscapeCandidate(
			GridPosition origin,
			Vec3 currentPosition,
			Vec3 threat,
			WalkabilityView world
	) {
		Objects.requireNonNull(origin, "origin must not be null");
		Objects.requireNonNull(currentPosition, "current position must not be null");
		Objects.requireNonNull(world, "world must not be null");
		GridPosition best = null;
		double bestDistance = threat == null ? Double.NEGATIVE_INFINITY : currentPosition.distanceToSqr(threat);
		for (Direction direction : HORIZONTAL) {
			GridPosition candidate = origin.offset(direction.getStepX(), 0, direction.getStepZ());
			if (!world.isStandable(candidate)) continue;
			Vec3 target = new Vec3(candidate.x() + 0.5D, candidate.y(), candidate.z() + 0.5D);
			double distance = threat == null ? 0.0D : target.distanceToSqr(threat);
			if (best == null || distance > bestDistance) {
				best = candidate;
				bestDistance = distance;
			}
		}
		return best;
	}

	private void release(AgentId agentId) {
		InputLease lease = leases.remove(agentId);
		if (lease == null) return;
		try {
			inputs.release(lease);
		} catch (IllegalStateException ignored) {
			// Lifecycle cleanup may already have cleared all input leases.
		}
	}

	record DamageState(float health, long lastDamageTick, int streak) {
		boolean repeatedAt(long tick) {
			return streak >= 2 && withinWindow(tick);
		}

		private boolean withinWindow(long tick) {
			return lastDamageTick != Long.MIN_VALUE && tick >= lastDamageTick
					&& tick - lastDamageTick <= REPEATED_DAMAGE_WINDOW_TICKS;
		}
	}

	public enum SafetyDirective {
		NONE,
		SWIM_UP,
		LEAVE_LAVA,
		LEAVE_FIRE,
		RAISE_EQUIPPED_SHIELD,
		RETREAT_FROM_REPEATED_DAMAGE,
		RESPAWN_RECOVERY
	}

	public record HazardSnapshot(
			boolean dead,
			boolean inWater,
			boolean inLava,
			boolean onFire,
			int airSupply,
			int drowningThreshold,
			boolean repeatedDamage,
			boolean equippedShield,
			boolean verifiedEscapeRoute,
			boolean verifiedRetreatRoute
	) {
		public HazardSnapshot {
			if (airSupply < 0 || drowningThreshold < 0) throw new IllegalArgumentException("air values must be nonnegative");
		}
	}
}
