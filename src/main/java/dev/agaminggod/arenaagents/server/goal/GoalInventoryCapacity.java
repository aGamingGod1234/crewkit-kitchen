package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
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
		return exceeds(new GoalPredicate.InventoryContains(itemId, count), registries);
	}

	public static boolean exceeds(GoalPredicate predicate, RegistryAccess registries) {
		Objects.requireNonNull(predicate, "predicate must not be null");
		Objects.requireNonNull(registries, "registries must not be null");
		List<Map<String, Integer>> alternatives = requirementAlternatives(predicate);
		Map<String, ItemCapacity> capacities = resolveCapacities(alternatives, registries);
		return alternatives.stream().noneMatch(requirements -> fits(requirements, capacities));
	}

	public static void validateTranslated(GoalPredicate predicate, RegistryAccess registries) {
		if (exceeds(predicate, registries)) {
			throw new AgentDomainException(
					"INVALID_GOAL_PREDICATE",
					"Inventory requirements exceed the player's shared carrying capacity"
			);
		}
	}

	private static List<Map<String, Integer>> requirementAlternatives(GoalPredicate predicate) {
		return switch (predicate) {
			case GoalPredicate.InventoryContains value -> List.of(Map.of(value.itemId(), value.count()));
			case GoalPredicate.AllOf value -> combineAll(value.predicates());
			case GoalPredicate.AnyOf value -> combineAlternatives(value.predicates());
			default -> List.of(Map.of());
		};
	}

	private static List<Map<String, Integer>> combineAll(Iterable<GoalPredicate> predicates) {
		List<Map<String, Integer>> combined = List.of(Map.of());
		for (GoalPredicate predicate : predicates) {
			ArrayList<Map<String, Integer>> next = new ArrayList<>();
			for (Map<String, Integer> existing : combined) {
				for (Map<String, Integer> addition : requirementAlternatives(predicate)) {
					next.add(merge(existing, addition));
				}
			}
			combined = List.copyOf(next);
		}
		return combined;
	}

	private static List<Map<String, Integer>> combineAlternatives(Iterable<GoalPredicate> predicates) {
		ArrayList<Map<String, Integer>> alternatives = new ArrayList<>();
		for (GoalPredicate predicate : predicates) {
			alternatives.addAll(requirementAlternatives(predicate));
		}
		return List.copyOf(alternatives);
	}

	private static Map<String, Integer> merge(Map<String, Integer> first, Map<String, Integer> second) {
		HashMap<String, Integer> combined = new HashMap<>(first);
		try {
			second.forEach((itemId, count) -> combined.merge(itemId, count, Math::addExact));
		} catch (ArithmeticException exception) {
			throw new AgentDomainException("INVALID_GOAL_PREDICATE", "Combined inventory count is outside the supported range");
		}
		return Map.copyOf(combined);
	}

	private static Map<String, ItemCapacity> resolveCapacities(
			Iterable<Map<String, Integer>> alternatives,
			RegistryAccess registries
	) {
		Registry<Item> itemRegistry = registries.lookup(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
		HashMap<String, ItemCapacity> capacities = new HashMap<>();
		for (Map<String, Integer> requirements : alternatives) {
			for (String itemId : requirements.keySet()) {
				capacities.computeIfAbsent(itemId, ignored -> resolveCapacity(itemId, itemRegistry));
			}
		}
		return Map.copyOf(capacities);
	}

	private static ItemCapacity resolveCapacity(String itemId, Registry<Item> itemRegistry) {
		Identifier id = Identifier.tryParse(itemId);
		Item item = id == null ? null : itemRegistry.getValue(id);
		if (item == null) {
			throw new AgentDomainException("INVALID_GOAL_PREDICATE", "Inventory item does not exist on this server");
		}
		int maxStackSize = item.getDefaultMaxStackSize();
		int offhandCapacity = EquipmentSlot.OFFHAND.limit(
				new ItemStack(item.builtInRegistryHolder(), maxStackSize)).getCount();
		EquipmentSlot equipmentSlot = null;
		int equipmentCapacity = 0;
		Equippable equippable = item.components().get(DataComponents.EQUIPPABLE);
		if (equippable != null
				&& equippable.slot() != EquipmentSlot.OFFHAND
				&& Inventory.EQUIPMENT_SLOT_MAPPING.containsValue(equippable.slot())
				&& equippable.canBeEquippedBy(EntityType.PLAYER.builtInRegistryHolder())) {
			equipmentSlot = equippable.slot();
			equipmentCapacity = equipmentSlot.limit(
					new ItemStack(item.builtInRegistryHolder(), maxStackSize)).getCount();
		}
		return new ItemCapacity(maxStackSize, offhandCapacity, equipmentSlot, equipmentCapacity);
	}

	private static boolean fits(Map<String, Integer> requirements, Map<String, ItemCapacity> capacities) {
		List<Map.Entry<String, Integer>> items = List.copyOf(requirements.entrySet());
		for (int offhandItem = -1; offhandItem < items.size(); offhandItem++) {
			long generalSlots = 0L;
			EnumMap<EquipmentSlot, Long> equipmentSavings = new EnumMap<>(EquipmentSlot.class);
			for (int index = 0; index < items.size(); index++) {
				Map.Entry<String, Integer> requirement = items.get(index);
				ItemCapacity capacity = capacities.get(requirement.getKey());
				long remaining = requirement.getValue();
				if (index == offhandItem) remaining = Math.max(0L, remaining - capacity.offhandCapacity());
				long requiredSlots = divideRoundUp(remaining, capacity.maxStackSize());
				generalSlots += requiredSlots;
				if (capacity.equipmentSlot() != null && capacity.equipmentCapacity() > 0) {
					long withEquipment = divideRoundUp(
							Math.max(0L, remaining - capacity.equipmentCapacity()),
							capacity.maxStackSize()
					);
					equipmentSavings.merge(capacity.equipmentSlot(), requiredSlots - withEquipment, Math::max);
				}
			}
			for (long saving : equipmentSavings.values()) generalSlots -= saving;
			if (generalSlots <= Inventory.INVENTORY_SIZE) return true;
		}
		return false;
	}

	private static long divideRoundUp(long count, int stackSize) {
		return count == 0L ? 0L : 1L + (count - 1L) / stackSize;
	}

	private record ItemCapacity(
			int maxStackSize,
			int offhandCapacity,
			EquipmentSlot equipmentSlot,
			int equipmentCapacity
	) {
	}
}
