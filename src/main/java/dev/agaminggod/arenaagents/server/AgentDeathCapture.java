package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentDeathSnapshot;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.tags.DamageTypeTags;

public final class AgentDeathCapture {
	private AgentDeathCapture() {
	}

	public static boolean record(
			AgentRegistry registry,
			UUID playerUuid,
			AgentDeathSnapshot snapshot,
			long nowEpochMs
	) {
		Objects.requireNonNull(registry, "registry must not be null");
		Objects.requireNonNull(playerUuid, "playerUuid must not be null");
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		for (AgentRecord record : registry.records()) {
			if (!OfflineAgentPlayers.offlineUuid(record.agentId(), record.profile()).equals(playerUuid)) continue;
			if (record.state() != AgentLifecycleState.DEAD) {
				registry.die(record.agentId(), snapshot, nowEpochMs);
			}
			return true;
		}
		return false;
	}

	public static boolean allowVanillaDeath(boolean recoveredByScenario, Runnable recordDeath) {
		return allowVanillaDeath(recoveredByScenario, false, recordDeath);
	}

	/** How Entity.kill(ServerLevel) (the /kill command) treats a Carpet fake player. */
	public enum KillRoute {
		/** Carpet's own behaviour: disconnect the fake. Kept for players that are not Arena bodies. */
		CARPET_DISCONNECT,
		/** Vanilla LivingEntity.kill: lethal generic_kill damage, so the body dies like a real player and stays connected. */
		VANILLA_DEATH,
		/** The body is already dead and waiting for respawn; a second kill records nothing and keeps it connected. */
		ALREADY_DEAD
	}

	/**
	 * Carpet overrides kill(ServerLevel) to disconnect the fake player. For an Arena body that left the server,
	 * the manager's recovery recreated it in place, so /kill looked like leave/rejoin with nothing lost.
	 */
	public static KillRoute killRoute(boolean arenaBody, boolean deadOrDying) {
		if (!arenaBody) return KillRoute.CARPET_DISCONNECT;
		return deadOrDying ? KillRoute.ALREADY_DEAD : KillRoute.VANILLA_DEATH;
	}

	/** A totem save is not a death: let vanilla pop it and record nothing. */
	public static boolean allowVanillaDeath(boolean recoveredByScenario, boolean totemWillSave, Runnable recordDeath) {
		Objects.requireNonNull(recordDeath, "recordDeath must not be null");
		if (recoveredByScenario) return false;
		if (totemWillSave) return true;
		recordDeath.run();
		return true;
	}

	/**
	 * Fabric's ALLOW_DEATH fires at the isDeadOrDying check, before vanilla's
	 * LivingEntity.checkTotemDeathProtection. Mirrors that check: damage that bypasses
	 * invulnerability is never stopped, otherwise any hand item with DEATH_PROTECTION saves.
	 */
	public static boolean totemWillSave(LivingEntity entity, DamageSource source) {
		Objects.requireNonNull(entity, "entity must not be null");
		Objects.requireNonNull(source, "source must not be null");
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
		for (InteractionHand hand : InteractionHand.values()) {
			if (entity.getItemInHand(hand).get(DataComponents.DEATH_PROTECTION) != null) return true;
		}
		return false;
	}
}
