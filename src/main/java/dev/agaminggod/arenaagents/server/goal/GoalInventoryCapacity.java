package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/** One authoritative carrying-capacity policy for direct and translated inventory goals. */
public final class GoalInventoryCapacity {
	private GoalInventoryCapacity() {
	}

	public static boolean exceeds(String itemId, int count, RegistryAccess registries) {
		Objects.requireNonNull(registries, "registries must not be null");
		Registry<Item> itemRegistry = registries.lookup(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
		Identifier id = Identifier.tryParse(itemId);
		Item item = id == null ? null : itemRegistry.getValue(id);
		if (item == null) {
			throw new AgentDomainException("INVALID_GOAL_PREDICATE", "Inventory item does not exist on this server");
		}
		int maxStackSize = item.getDefaultMaxStackSize();
		int capacity = maxStackSize * Inventory.INVENTORY_SIZE;
		Equippable equippable = item.components().get(DataComponents.EQUIPPABLE);
		for (EquipmentSlot slot : Inventory.EQUIPMENT_SLOT_MAPPING.values()) {
			if (slot == EquipmentSlot.OFFHAND || equippable != null
					&& equippable.slot() == slot
					&& equippable.canBeEquippedBy(EntityType.PLAYER.builtInRegistryHolder())) {
				capacity += slot.limit(new ItemStack(item.builtInRegistryHolder(), maxStackSize)).getCount();
			}
		}
		return count > capacity;
	}

	public static void validateTranslated(GoalPredicate predicate, RegistryAccess registries) {
		for (Map.Entry<String, Integer> requirement : maximumRequiredCounts(predicate).entrySet()) {
			if (exceeds(requirement.getKey(), requirement.getValue(), registries)) {
				throw new AgentDomainException(
						"INVALID_GOAL_PREDICATE",
						"Inventory count exceeds the player's capacity for " + requirement.getKey()
				);
			}
		}
	}

	private static Map<String, Integer> maximumRequiredCounts(GoalPredicate predicate) {
		return switch (predicate) {
			case GoalPredicate.InventoryContains value -> Map.of(value.itemId(), value.count());
			case GoalPredicate.AllOf value -> combineAll(value.predicates());
			case GoalPredicate.AnyOf value -> combineAlternatives(value.predicates());
			default -> Map.of();
		};
	}

	private static Map<String, Integer> combineAll(Iterable<GoalPredicate> predicates) {
		HashMap<String, Integer> combined = new HashMap<>();
		for (GoalPredicate predicate : predicates) {
			for (Map.Entry<String, Integer> requirement : maximumRequiredCounts(predicate).entrySet()) {
				try {
					combined.merge(requirement.getKey(), requirement.getValue(), Math::addExact);
				} catch (ArithmeticException exception) {
					throw new AgentDomainException("INVALID_GOAL_PREDICATE", "Combined inventory count is outside the supported range");
				}
			}
		}
		return Map.copyOf(combined);
	}

	private static Map<String, Integer> combineAlternatives(Iterable<GoalPredicate> predicates) {
		HashMap<String, Integer> maximums = new HashMap<>();
		for (GoalPredicate predicate : predicates) {
			maximumRequiredCounts(predicate).forEach((itemId, count) -> maximums.merge(itemId, count, Math::max));
		}
		return Map.copyOf(maximums);
	}
}
