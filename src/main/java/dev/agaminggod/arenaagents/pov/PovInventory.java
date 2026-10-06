package dev.agaminggod.arenaagents.pov;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** The agent's hotbar, offhand and worn armor; stacks are copied, so the record never aliases live inventory. */
public record PovInventory(int selectedSlot, List<ItemStack> hotbar, ItemStack offhand, List<ItemStack> armor) {
	public static final int HOTBAR_SIZE = 9;
	/** Order of {@link #armor()}, matching the Inventory equipment slots 36..39: boots, leggings, chestplate, helmet. */
	public static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
			EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);
	public static final StreamCodec<RegistryFriendlyByteBuf, PovInventory> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, PovInventory::selectedSlot,
			ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(HOTBAR_SIZE)), PovInventory::hotbar,
			ItemStack.OPTIONAL_STREAM_CODEC, PovInventory::offhand,
			ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(ARMOR_SLOTS.size())), PovInventory::armor,
			PovInventory::new
	);

	public PovInventory {
		if (selectedSlot < 0 || selectedSlot >= HOTBAR_SIZE) {
			throw new IllegalArgumentException("selectedSlot must be within 0.." + (HOTBAR_SIZE - 1));
		}
		hotbar = PovPayloads.copyStacks(hotbar, HOTBAR_SIZE, "hotbar");
		if (hotbar.size() != HOTBAR_SIZE) throw new IllegalArgumentException("hotbar must have exactly 9 stacks");
		offhand = Objects.requireNonNull(offhand, "offhand must not be null; use ItemStack.EMPTY").copy();
		armor = PovPayloads.copyStacks(armor, ARMOR_SLOTS.size(), "armor");
		if (armor.size() != ARMOR_SLOTS.size()) throw new IllegalArgumentException("armor must have exactly 4 stacks");
	}

	public static PovInventory empty() {
		return new PovInventory(0, Collections.nCopies(HOTBAR_SIZE, ItemStack.EMPTY), ItemStack.EMPTY,
				Collections.nCopies(ARMOR_SLOTS.size(), ItemStack.EMPTY));
	}

	/** Content equality for change detection; record equality compares ItemStack identity and never matches. */
	public boolean matches(PovInventory other) {
		return other != null
				&& selectedSlot == other.selectedSlot
				&& ItemStack.listMatches(hotbar, other.hotbar)
				&& ItemStack.matches(offhand, other.offhand)
				&& ItemStack.listMatches(armor, other.armor);
	}
}
