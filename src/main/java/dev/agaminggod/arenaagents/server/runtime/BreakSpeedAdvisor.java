package dev.agaminggod.arenaagents.server.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tells the model what a player sees in the crack speed: when the inventory holds a tool that breaks the block
 * strictly faster than the one in hand, the break result names both times. It never picks or swaps a tool.
 * The times are vanilla's own formula (destroy speed over hardness over 30, or 100 without the right tool).
 */
final class BreakSpeedAdvisor {
	private BreakSpeedAdvisor() {
	}

	/** A tool's break time for one block: the item, its inventory slot (-1 for the hand), and the ticks. */
	record Option(String name, int slot, int ticks) {
	}

	/** Vanilla ticks to break: progress per tick is speed / hardness / (30 with the right tool, else 100). */
	static int breakTicks(float destroySpeed, float hardness, boolean correctTool) {
		if (hardness < 0.0F || hardness > 0.0F && destroySpeed <= 0.0F) return -1;
		if (hardness == 0.0F) return 1;
		float progress = destroySpeed / hardness / (correctTool ? 30.0F : 100.0F);
		return progress >= 1.0F ? 1 : (int) Math.ceil(1.0D / progress - 1.0E-4D);
	}

	/** The short result suffix, or null when nothing in the inventory strictly beats the held tool. */
	static String note(Option held, Option best) {
		if (held == null || best == null || held.ticks() < 1 || best.ticks() < 1 || best.ticks() >= held.ticks()) return null;
		return "faster tool in inventory: " + held.name() + " (held) " + held.ticks() + " ticks, "
				+ best.name() + " slot " + best.slot() + ": " + best.ticks() + " ticks";
	}

	static String advise(ServerPlayer player, BlockPos position, BlockState state) {
		if (player.isCreative()) return null;
		return advise(player.getInventory(), state, state.getDestroySpeed(player.level(), position), player.getDestroySpeed(state));
	}

	/** {@code heldNow} is the player's own destroy speed with the held item, effects and surroundings included. */
	static String advise(Inventory inventory, BlockState state, float hardness, float heldNow) {
		if (hardness <= 0.0F) return null;
		ItemStack heldStack = inventory.getSelectedItem();
		float heldBase = baseSpeed(heldStack, state);
		// Water, flying, haste and fatigue scale every tool alike, so take them from the player as measured.
		float environment = heldBase > 0.0F ? heldNow / heldBase : 1.0F;
		Option held = new Option(name(heldStack), inventory.getSelectedSlot(),
				breakTicks(heldNow, hardness, correctTool(heldStack, state)));
		Option best = null;
		for (int slot = 0; slot < 36; slot++) {
			if (slot == inventory.getSelectedSlot()) continue;
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) continue;
			int ticks = breakTicks(baseSpeed(stack, state) * environment, hardness, correctTool(stack, state));
			if (ticks > 0 && (best == null || ticks < best.ticks())) best = new Option(name(stack), slot, ticks);
		}
		return note(held, best);
	}

	private static boolean correctTool(ItemStack stack, BlockState state) {
		return !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
	}

	/** Tool speed on the block plus the efficiency the stack would add in the main hand, before any player effect. */
	private static float baseSpeed(ItemStack stack, BlockState state) {
		float speed = stack.getDestroySpeed(state);
		if (speed > 1.0F) {
			float[] efficiency = {0.0F};
			stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
				if (attribute.equals(Attributes.MINING_EFFICIENCY) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
					efficiency[0] += (float) modifier.amount();
				}
			});
			speed += efficiency[0];
		}
		return speed;
	}

	private static String name(ItemStack stack) {
		if (stack.isEmpty()) return "hand";
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}
}
