package dev.agaminggod.arenaagents.server.perception;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.phys.Vec3;

/**
 * Expected damage of one hit from an attacker on one agent, using the attacker's real held item and vanilla's own
 * damage rules where they are callable without side effects: the attack-damage attribute (held weapon modifiers
 * included), EnchantmentHelper.modifyDamage (sharpness, smite and bane against this target), the item's own attack
 * bonus (the mace smash bonus grows with the attacker's current fall distance; Density adds to it), player critical
 * falls, spear lunges (kinetic damage from closing speed), bows (Power), crossbows (charged arrow or firework,
 * Multishot), tridents and creeper blasts (charged creepers double the radius). The result is then scaled by
 * difficulty and reduced by the agent's armour, toughness (Breach on the weapon applies), protection enchantments
 * and Resistance, as vanilla does. Cached per attacker and agent for one game tick.
 */
public final class ThreatDamage {
	/** Arrow base damage and launch speeds (blocks per tick) used by vanilla for full draws and mob shots. */
	static final double ARROW_BASE_DAMAGE = 2.0D;
	static final double PLAYER_BOW_SPEED = 3.0D;
	static final double CROSSBOW_SPEED = 3.15D;
	static final double MOB_SHOT_SPEED = 1.6D;
	/** A Multishot volley spreads three projectiles; on average about half the extra ones also connect. */
	static final double MULTISHOT_FACTOR = 2.0D;
	static final double THROWN_TRIDENT_DAMAGE = 8.0D;
	static final double CREEPER_RADIUS = 3.0D;

	public record Estimate(double damage, String mode, boolean disablesShield) {
	}

	private record Key(int attackerId, int agentId) {
	}

	private final Map<Key, Estimate> cache = new HashMap<>();
	private long cacheTick = Long.MIN_VALUE;
	private static final ThreadLocal<ThreatDamage> LOCAL = ThreadLocal.withInitial(ThreatDamage::new);

	/** Cached expected hit for this tick; never mutates the world. */
	public static Estimate expectedHit(LivingEntity attacker, ServerPlayer agent) {
		return LOCAL.get().cached(attacker, agent);
	}

	/** Expected blast on this agent were the creeper to explode {@code distance} blocks away (risk at contact). */
	public static double creeperBlastAt(Creeper creeper, ServerPlayer agent, double distance) {
		try {
			double radius = CREEPER_RADIUS * (creeper.isPowered() ? 2.0D : 1.0D);
			DamageSource source = agent.level().damageSources().explosion(creeper, creeper);
			return round(afterDefenses(agent.level(), agent, source, explosionDamage(distance, radius)));
		} catch (RuntimeException unexpected) {
			return RiskModel.REFERENCE_HIT_DAMAGE;
		}
	}

	private Estimate cached(LivingEntity attacker, ServerPlayer agent) {
		long tick = agent.level().getGameTime();
		if (tick != cacheTick) {
			cache.clear();
			cacheTick = tick;
		}
		return cache.computeIfAbsent(new Key(attacker.getId(), agent.getId()), ignored -> compute(attacker, agent));
	}

	private static Estimate compute(LivingEntity attacker, ServerPlayer agent) {
		ServerLevel level = agent.level();
		try {
			if (attacker instanceof Creeper creeper) {
				double radius = CREEPER_RADIUS * (creeper.isPowered() ? 2.0D : 1.0D);
				double raw = explosionDamage(agent.position().distanceTo(creeper.position()), radius);
				DamageSource source = level.damageSources().explosion(creeper, creeper);
				return new Estimate(round(afterDefenses(level, agent, source, raw)), "explosion", false);
			}
			ItemStack weapon = attacker.getWeaponItem();
			boolean player = attacker instanceof Player;
			DamageSource melee = player ? level.damageSources().playerAttack((Player) attacker) : level.damageSources().mobAttack(attacker);
			double meleeRaw = meleeDamage(level, attacker, agent, weapon, melee, player);
			double meleeHit = afterDefenses(level, agent, melee, meleeRaw);
			double rangedRaw = rangedDamage(level, attacker, weapon, player);
			double rangedHit = 0.0D;
			if (rangedRaw > 0.0D) {
				DamageSource projectile = level.damageSources().mobProjectile(attacker, attacker);
				rangedHit = afterDefenses(level, agent, projectile, rangedRaw);
			}
			Weapon weaponComponent = weapon.get(DataComponents.WEAPON);
			boolean disablesShield = weaponComponent != null && weaponComponent.disableBlockingForSeconds() > 0.0F;
			return rangedHit > meleeHit
					? new Estimate(round(rangedHit), "ranged", false)
					: new Estimate(round(meleeHit), "melee", disablesShield);
		} catch (RuntimeException unexpected) {
			// A modded entity without vanilla attributes or damage sources still gets a neutral reference estimate.
			return new Estimate(RiskModel.REFERENCE_HIT_DAMAGE, "unknown", false);
		}
	}

	private static double meleeDamage(ServerLevel level, LivingEntity attacker, ServerPlayer agent, ItemStack weapon,
			DamageSource source, boolean player) {
		AttributeInstance attack = attacker.getAttribute(Attributes.ATTACK_DAMAGE);
		double base = attack == null ? 0.0D : attack.getValue();
		if (base <= 0.0D) return 0.0D;
		float damage = EnchantmentHelper.modifyDamage(level, weapon, agent, source, (float) base);
		// Mace smash: vanilla's own bonus from the attacker's current fall distance (Density included).
		damage += weapon.getItem().getAttackDamageBonus(agent, damage, source);
		KineticWeapon kinetic = weapon.get(DataComponents.KINETIC_WEAPON);
		if (kinetic != null && attacker.isUsingItem()) {
			damage += (float) kineticBonus(closingSpeedPerSecond(attacker, agent), kinetic.damageMultiplier());
		}
		if (player && criticalFall(attacker)) damage *= 1.5F;
		return damage;
	}

	/** Bow, crossbow and trident shots plus the fixed ranged attacks of mobs that hold no weapon. */
	private static double rangedDamage(ServerLevel level, LivingEntity attacker, ItemStack weapon, boolean player) {
		if (weapon.getItem() instanceof BowItem) {
			return bowArrowDamage(level(level, weapon, Enchantments.POWER), player ? PLAYER_BOW_SPEED : MOB_SHOT_SPEED,
					player ? 0 : level.getDifficulty().getId());
		}
		if (weapon.getItem() instanceof CrossbowItem) {
			ChargedProjectiles charged = weapon.get(DataComponents.CHARGED_PROJECTILES);
			boolean firework = charged != null && charged.contains(Items.FIREWORK_ROCKET);
			return crossbowDamage(firework, level(level, weapon, Enchantments.MULTISHOT), player ? CROSSBOW_SPEED : MOB_SHOT_SPEED);
		}
		if (weapon.is(Items.TRIDENT)) return THROWN_TRIDENT_DAMAGE;
		return switch (typePath(attacker)) {
			case "ghast" -> 6.0D + 6.0D;
			case "blaze" -> 5.0D;
			case "shulker" -> 4.0D;
			case "guardian", "evoker", "witch" -> 6.0D;
			case "elder_guardian" -> 8.0D;
			case "breeze" -> 1.0D;
			default -> 0.0D;
		};
	}

	private static double afterDefenses(ServerLevel level, ServerPlayer agent, DamageSource source, double raw) {
		double damage = raw;
		if (source.scalesWithDifficulty()) damage = difficultyScaled(damage, level.getDifficulty());
		if (damage <= 0.0D) return 0.0D;
		if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
			AttributeInstance toughness = agent.getAttribute(Attributes.ARMOR_TOUGHNESS);
			damage = CombatRules.getDamageAfterAbsorb(agent, (float) damage, source, agent.getArmorValue(),
					toughness == null ? 0.0F : (float) toughness.getValue());
		}
		if (!source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
			damage = CombatRules.getDamageAfterMagicAbsorb((float) damage, EnchantmentHelper.getDamageProtection(level, agent, source));
		}
		MobEffectInstance resistance = agent.getEffect(MobEffects.RESISTANCE);
		if (resistance != null && !source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
			damage = resistanceScaled(damage, resistance.getAmplifier());
		}
		return damage;
	}

	/** Vanilla explosion damage at a distance with full exposure (the agent in the open). */
	static double explosionDamage(double distance, double radius) {
		double diameter = radius * 2.0D;
		double scaled = distance / diameter;
		if (!(scaled <= 1.0D)) return 0.0D;
		double impact = 1.0D - scaled;
		return (impact * impact + impact) / 2.0D * 7.0D * diameter + 1.0D;
	}

	static double difficultyScaled(double damage, Difficulty difficulty) {
		return switch (difficulty) {
			case PEACEFUL -> 0.0D;
			case EASY -> Math.min(damage / 2.0D + 1.0D, damage);
			case NORMAL -> damage;
			case HARD -> damage * 1.5D;
		};
	}

	static double resistanceScaled(double damage, int amplifier) {
		return Math.max(0.0D, damage * (1.0D - 0.2D * (amplifier + 1)));
	}

	/** Full-draw arrow: speed x (2 + Power bonus, + difficulty bonus for mob shots), rounded up like vanilla. */
	static double bowArrowDamage(int power, double speed, int difficultyId) {
		double base = ARROW_BASE_DAMAGE + (power > 0 ? 0.5D * power + 0.5D : 0.0D) + difficultyId * 0.11D;
		return Math.ceil(speed * base);
	}

	/** A charged arrow, or a firework rocket blast; Multishot raises the expected volley. */
	static double crossbowDamage(boolean firework, int multishot, double speed) {
		double single = firework ? 7.0D : Math.ceil(speed * ARROW_BASE_DAMAGE);
		return multishot > 0 ? single * MULTISHOT_FACTOR : single;
	}

	/** Spear lunge: vanilla floors closing speed (blocks per second) times the weapon's damage multiplier. */
	static double kineticBonus(double closingSpeedPerSecond, double multiplier) {
		return Math.floor(Math.max(0.0D, closingSpeedPerSecond) * multiplier);
	}

	static boolean criticalFall(double fallDistance, boolean onGround, boolean climbing, boolean inWater, boolean passenger) {
		return fallDistance > 0.0D && !onGround && !climbing && !inWater && !passenger;
	}

	private static boolean criticalFall(LivingEntity attacker) {
		return criticalFall(attacker.fallDistance, attacker.onGround(), attacker.onClimbable(), attacker.isInWater(), attacker.isPassenger());
	}

	private static double closingSpeedPerSecond(LivingEntity attacker, ServerPlayer agent) {
		Vec3 toward = agent.position().subtract(attacker.position());
		if (toward.lengthSqr() < 1.0E-6D) return 0.0D;
		Vec3 relative = attacker.getKnownSpeed().subtract(agent.getKnownSpeed());
		return relative.dot(toward.normalize()) * 20.0D;
	}

	private static int level(ServerLevel level, ItemStack stack, ResourceKey<Enchantment> key) {
		Holder<Enchantment> holder = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key).orElse(null);
		return holder == null ? 0 : EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
	}

	private static String typePath(LivingEntity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
	}

	private static double round(double value) {
		return Double.isFinite(value) ? Math.round(value * 10.0D) / 10.0D : RiskModel.NON_FINITE_RISK;
	}
}
