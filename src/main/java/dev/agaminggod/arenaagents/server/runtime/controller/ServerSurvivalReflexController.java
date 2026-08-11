package dev.agaminggod.arenaagents.server.runtime.controller;

import carpet.helpers.EntityPlayerActionPack;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Two-second maximum motor-only safety reflex. It never chooses goals, targets, items, or resources.
 */
public final class ServerSurvivalReflexController {
	public static final long MAX_DURATION_MS = 2_000L;

	private final SurvivalReflex reflex;
	private final Vec3 attackerPosition;
	private final long expiresAt;

	private ServerSurvivalReflexController(SurvivalReflex reflex, Vec3 attackerPosition, long expiresAt) {
		this.reflex = reflex;
		this.attackerPosition = attackerPosition;
		this.expiresAt = expiresAt;
	}

	public static ServerSurvivalReflexController detect(
			ServerPlayer player,
			boolean tookDamage,
			long nowEpochMs
	) {
		LivingEntity attacker = player.getLastHurtByMob();
		Vec3 attackerPosition = attacker != null && attacker.isAlive() ? attacker.position() : player.position();
		SurvivalThreat threat = new SurvivalThreat(
				player.isOnFire(),
				Math.max(0, player.getAirSupply()),
				Math.max(1, player.getMaxAirSupply()),
				player.isInWall(),
				!player.onGround() && player.fallDistance > 6.0F,
				tookDamage,
				attackerPosition.x,
				attackerPosition.z
		);
		SurvivalReflex chosen = SurvivalReflex.choose(threat);
		return chosen == SurvivalReflex.NONE
				? null
				: new ServerSurvivalReflexController(chosen, attackerPosition, nowEpochMs + MAX_DURATION_MS);
	}

	public SurvivalReflex reflex() {
		return reflex;
	}

	public boolean tick(ServerPlayer player, long nowEpochMs) {
		if (!player.isAlive() || nowEpochMs >= expiresAt) {
			OfflineAgentPlayers.stop(player);
			return true;
		}
		EntityPlayerActionPack actions = OfflineAgentPlayers.actions(player);
		switch (reflex) {
			case SURFACE -> actions
					.setForward(1.0F)
					.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.continuous());
			case ESCAPE_SUFFOCATION -> actions
					.setForward(1.0F)
					.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.interval(4));
			case LEAVE_FIRE -> actions
					.setSprinting(player.getFoodData().getFoodLevel() > 6)
					.setForward(1.0F)
					.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.interval(6));
			case STOP_DANGEROUS_FALL -> actions.setForward(0.0F).setSneaking(true);
			case BACK_AWAY -> actions
					.lookAt(attackerPosition.add(0.0D, 1.0D, 0.0D))
					.setSprinting(player.getHealth() <= 6.0F && player.getFoodData().getFoodLevel() > 6)
					.setForward(-1.0F);
			case NONE -> {
				OfflineAgentPlayers.stop(player);
				return true;
			}
		}
		return false;
	}
}
