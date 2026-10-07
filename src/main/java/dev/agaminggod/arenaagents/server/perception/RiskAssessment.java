package dev.agaminggod.arenaagents.server.perception;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Risk of one creature or player to one agent, split into potential and active. Every hostile mob, neutral mob and
 * other player carries a potential risk (what it could do); it only becomes an active risk while it actually engages
 * the agent: a mob targeting it, a creeper swelling or closing in, a ranged mob with a clear shot inside 16 blocks, or
 * anyone who hurt the agent in the last 30 seconds ({@link AggressionLedger}). A player is never active just for
 * being near or armed. These are facts for the model; the mod never picks a target or retreats from them itself.
 */
public final class RiskAssessment {
	public record Assessment(double risk, boolean active, RiskModel.Result model, ThreatDamage.Estimate hit) {
	}

	private record Key(int entityId, int agentId) {
	}

	private static final ThreadLocal<Map<Key, Assessment>> CACHE = ThreadLocal.withInitial(HashMap::new);
	private static final ThreadLocal<long[]> CACHE_TICK = ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE});

	private RiskAssessment() {
	}

	/** True for creatures and players that carry a (potential) risk at all; passive animals and items do not. */
	public static boolean carriesRisk(ServerPlayer agent, Entity entity) {
		if (!(entity instanceof LivingEntity living) || entity == agent || !living.isAlive()) return false;
		if (entity instanceof Player player) return !player.isCreative() && !player.isSpectator();
		if (entity instanceof Enemy || (entity instanceof Mob mob && (ThreatPerception.isNeutral(mob) || mob.getTarget() == agent))) return true;
		return attackedRecently(agent, living);
	}

	/**
	 * Engaging the agent right now. Calm neutral mobs and players stay potential until they attack; hostile mobs
	 * turn active when they target the agent, swell, close in as a creeper or line up a ranged shot.
	 */
	public static boolean isActive(ServerPlayer agent, LivingEntity entity) {
		if (!entity.isAlive() || entity == agent) return false;
		if (attackedRecently(agent, entity)) return true;
		if (!(entity instanceof Mob mob)) return false;
		boolean targeting = mob.getTarget() == agent;
		if (targeting) return agent.distanceTo(mob) <= ThreatPerception.RELEASE_RANGE;
		if (!(mob instanceof Enemy) || ThreatPerception.isNeutral(mob)) return false;
		double distance = agent.distanceTo(mob);
		if (distance > ThreatPerception.RANGE) return false;
		boolean creeper = mob instanceof Creeper;
		boolean swelling = creeper && ((Creeper) mob).getSwellDir() > 0;
		boolean ranged = ThreatPerception.isRanged(mob);
		if (!creeper && !ranged) return false;
		boolean sight = mob.getSensing().hasLineOfSight(agent);
		return !ThreatPerception.signals(false, false, creeper, swelling, ranged, sight, distance).isEmpty();
	}

	/** Creative and spectator players never count as attackers or targets (an operator's stray hit is not a fight). */
	public static boolean attackedRecently(ServerPlayer agent, LivingEntity entity) {
		return isRiskablePlayer(entity)
				&& AggressionLedger.server().attackedRecently(agent.getUUID(), entity.getUUID(), agent.level().getGameTime());
	}

	/** False only for creative or spectator players; every other entity passes. */
	public static boolean isRiskablePlayer(LivingEntity entity) {
		return !(entity instanceof Player player) || (!player.isCreative() && !player.isSpectator());
	}

	/** Cached for one game tick per entity and agent. */
	public static Assessment assess(ServerPlayer agent, LivingEntity entity) {
		long tick = agent.level().getGameTime();
		long[] cachedTick = CACHE_TICK.get();
		Map<Key, Assessment> cache = CACHE.get();
		if (cachedTick[0] != tick) {
			cache.clear();
			cachedTick[0] = tick;
		}
		return cache.computeIfAbsent(new Key(entity.getId(), agent.getId()), ignored -> compute(agent, entity));
	}

	private static Assessment compute(ServerPlayer agent, LivingEntity entity) {
		boolean active = isActive(agent, entity);
		boolean player = entity instanceof Player;
		double distance = agent.distanceTo(entity);
		ThreatDamage.Estimate hit = ThreatDamage.expectedHit(entity, agent);
		AttributeInstance speed = entity.getAttribute(Attributes.MOVEMENT_SPEED);
		// Knockback is not the entity's own speed; while it is reeling from a hit only the attribute counts.
		Vec3 motion = entity.hurtTime > 0 ? Vec3.ZERO : player ? entity.getKnownSpeed() : entity.getDeltaMovement();
		boolean ranged = entity instanceof Mob mob ? ThreatPerception.isRanged(mob) : holdsRangedWeapon(entity);
		boolean sight = ranged && distance <= ThreatPerception.RANGE
				&& (entity instanceof Mob mob ? mob.getSensing().hasLineOfSight(agent) : entity.hasLineOfSight(agent));
		boolean creeper = entity instanceof Creeper;
		boolean swelling = creeper && ((Creeper) entity).getSwellDir() > 0;
		RiskModel.Result result = RiskModel.score(new RiskModel.Input(
				entity.getBbWidth(), entity.getBbHeight(), entity.getHealth(), entity.getArmorValue(), player,
				speed == null ? Double.NaN : speed.getValue(), Math.hypot(motion.x, motion.z), distance,
				hit.damage(), ranged, sight, active, swelling,
				creeper ? ((Creeper) entity).getSwelling(1.0F) : 0.0D));
		return new Assessment(result.risk(), active, result, hit);
	}

	private static boolean holdsRangedWeapon(LivingEntity entity) {
		ItemStack held = entity.getMainHandItem();
		return held.getItem() instanceof BowItem || held.getItem() instanceof CrossbowItem || held.is(Items.TRIDENT);
	}
}
