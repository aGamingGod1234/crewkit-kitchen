package dev.agaminggod.arenaagents.server.runtime.controller;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Reads the hotbar into {@link CombatPlanning} weapon candidates (sword > axe/spear > other tool). */
public final class HotbarWeapons {
	private HotbarWeapons() {
	}

	public static int rank(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		return CombatPlanning.weaponRank(
				stack.is(ItemTags.SWORDS),
				stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS),
				stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
						|| stack.is(Items.MACE) || stack.is(Items.TRIDENT));
	}

	public static List<CombatPlanning.WeaponCandidate> candidates(ServerPlayer player) {
		List<CombatPlanning.WeaponCandidate> hotbar = new ArrayList<>(9);
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			hotbar.add(new CombatPlanning.WeaponCandidate(slot, rank(stack), stack.getMaxDamage()));
		}
		return hotbar;
	}

	/** Best hotbar weapon slot, or -1 when the hotbar holds no weapon at all. */
	public static int bestSlotOrNone(ServerPlayer player) {
		List<CombatPlanning.WeaponCandidate> hotbar = candidates(player);
		int current = player.getInventory().getSelectedSlot();
		int best = CombatPlanning.bestWeaponSlot(hotbar, current);
		return hotbar.get(best).rank() > 0 ? best : -1;
	}
}
