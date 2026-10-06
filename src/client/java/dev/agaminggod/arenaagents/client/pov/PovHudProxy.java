package dev.agaminggod.arenaagents.client.pov;

import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.pov.AgentPovStatePayload;
import dev.agaminggod.arenaagents.pov.PovInventory;
import dev.agaminggod.arenaagents.pov.PovVitals;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * Stand-in player that the vanilla HUD reads instead of the operator. It is never added to a level,
 * so the operator's own player, inventory and packets are never touched and exit needs no resync.
 */
public final class PovHudProxy {
	// Inventory slots 36 to 39 hold boots, leggings, chestplate and helmet, the PovInventory order.
	private static final int ARMOR_SLOT_START = Inventory.INVENTORY_SIZE;
	private static final int ARMOR_SLOTS = 4;
	private static final int HURT_BLINK_TICKS = 20;
	private static PovClientSession session;
	private static AgentPovStatePayload state;
	private static RemotePlayer proxy;

	private PovHudProxy() {
	}

	/** The stand-in for the current level, or null when the vanilla HUD should read the operator. */
	public static RemotePlayer current() {
		RemotePlayer value = proxy;
		return value != null && value.level() == Minecraft.getInstance().level ? value : null;
	}

	/** The agent's game mode for HUD gates, present exactly when {@link #current()} is. */
	public static GameType gameMode() {
		AgentPovStatePayload value = state;
		return value == null || current() == null ? null : value.vitals().gameMode();
	}

	static void update(Minecraft client, PovClientSession nextSession, AgentPovStatePayload next) {
		if (session == null || !session.agentUuid().equals(nextSession.agentUuid())) proxy = null;
		session = nextSession;
		state = next;
		RemotePlayer existing = current();
		if (existing != null) apply(existing, next, true);
		else ensure(client);
	}

	static void tick(Minecraft client, Entity agent) {
		RemotePlayer target = ensure(client);
		if (target == null) return;
		if (target.invulnerableTime > 0) target.invulnerableTime--;
		// Item models such as compasses read the holder's position.
		if (agent != null) target.snapTo(agent.getX(), agent.getY(), agent.getZ(), agent.getYRot(), agent.getXRot());
	}

	static void clear() {
		session = null;
		state = null;
		proxy = null;
	}

	// Built lazily so state that arrives before a level exists is applied once one does.
	private static RemotePlayer ensure(Minecraft client) {
		ClientLevel level = client.level;
		if (session == null || state == null || level == null) return null;
		if (proxy != null && proxy.level() == level) return proxy;
		Player known = level.getPlayerByUUID(session.agentUuid());
		GameProfile profile = known != null ? known.getGameProfile() : new GameProfile(session.agentUuid(), session.agentName());
		RemotePlayer created = new RemotePlayer(level, profile);
		apply(created, state, false);
		proxy = created;
		return created;
	}

	private static void apply(RemotePlayer target, AgentPovStatePayload value, boolean blinkOnDamage) {
		PovVitals vitals = value.vitals();
		setBase(target, Attributes.MAX_HEALTH, Math.max(1.0F, vitals.maxHealth()));
		setBase(target, Attributes.ARMOR, Math.max(0, vitals.armor()));
		// Absorption is clamped to MAX_ABSORPTION, which the agent's effects raise server-side.
		setBase(target, Attributes.MAX_ABSORPTION, Math.max(0.0F, vitals.absorption()));
		float previousHealth = target.getHealth();
		target.setHealth(vitals.health());
		// Vanilla blinks the hearts while invulnerableTime is positive after a drop.
		if (blinkOnDamage && target.getHealth() < previousHealth) target.invulnerableTime = HURT_BLINK_TICKS;
		target.setAbsorptionAmount(vitals.absorption());
		target.getFoodData().setFoodLevel(vitals.food());
		target.getFoodData().setSaturation(vitals.saturation());
		target.setAirSupply(vitals.air());
		target.experienceLevel = Math.max(0, vitals.xpLevel());
		target.experienceProgress = Math.clamp(vitals.xpProgress(), 0.0F, 1.0F);
		PovInventory inventory = value.inventory();
		Inventory items = target.getInventory();
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) items.setItem(slot, copy(inventory.hotbar(), slot));
		items.setSelectedSlot(Math.clamp(inventory.selectedSlot(), 0, Inventory.SELECTION_SIZE - 1));
		items.setItem(Inventory.SLOT_OFFHAND, copy(inventory.offhand()));
		for (int slot = 0; slot < ARMOR_SLOTS; slot++) items.setItem(ARMOR_SLOT_START + slot, copy(inventory.armor(), slot));
		// The map is written directly: removeAllEffects is a no-op client-side and addEffect has side effects.
		target.getActiveEffectsMap().clear();
		for (MobEffectInstance effect : value.effects()) target.getActiveEffectsMap().put(effect.getEffect(), new MobEffectInstance(effect));
	}

	private static void setBase(Player target, Holder<Attribute> attribute, double value) {
		AttributeInstance instance = target.getAttribute(attribute);
		if (instance != null) instance.setBaseValue(value);
	}

	private static ItemStack copy(List<ItemStack> stacks, int index) {
		return stacks != null && index < stacks.size() ? copy(stacks.get(index)) : ItemStack.EMPTY;
	}

	private static ItemStack copy(ItemStack stack) {
		return stack == null ? ItemStack.EMPTY : stack.copy();
	}
}
