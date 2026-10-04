package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
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
		List<Requirements> alternatives = requirementAlternatives(predicate);
		Map<String, ItemCapacity> capacities = resolveCapacities(alternatives, registries);
		BlockCapacity blockCapacity = alternatives.stream().anyMatch(requirements -> requirements.blockCount() > 0)
				? resolveBlockCapacity(registries) : new BlockCapacity(Set.of(), 64, 64, 0);
		return alternatives.stream().noneMatch(requirements -> possiblyFits(requirements, capacities, blockCapacity));
	}

	public static void validateTranslated(GoalPredicate predicate, RegistryAccess registries) {
		if (exceeds(predicate, registries)) {
			throw new AgentDomainException(
					"INVALID_GOAL_PREDICATE",
					"Inventory requirements exceed the player's shared carrying capacity"
			);
		}
	}

	private static List<Requirements> requirementAlternatives(GoalPredicate predicate) {
		return switch (predicate) {
			case GoalPredicate.InventoryContains value -> List.of(new Requirements(Map.of(value.itemId(), value.count()), List.of(), 0));
			case GoalPredicate.InventoryContainsAny value -> List.of(new Requirements(Map.of(), List.of(value), 0));
			case GoalPredicate.InventoryContainsBlock value -> List.of(new Requirements(Map.of(), List.of(), value.count()));
			case GoalPredicate.AllOf value -> combineAll(value.predicates());
			case GoalPredicate.AnyOf value -> combineAlternatives(value.predicates());
			default -> List.of(new Requirements(Map.of(), List.of(), 0));
		};
	}

	private static List<Requirements> combineAll(Iterable<GoalPredicate> predicates) {
		List<Requirements> combined = List.of(new Requirements(Map.of(), List.of(), 0));
		for (GoalPredicate predicate : predicates) {
			ArrayList<Requirements> next = new ArrayList<>();
			for (Requirements existing : combined) {
				for (Requirements addition : requirementAlternatives(predicate)) {
					next.add(merge(existing, addition));
				}
			}
			combined = List.copyOf(next);
		}
		return combined;
	}

	private static List<Requirements> combineAlternatives(Iterable<GoalPredicate> predicates) {
		ArrayList<Requirements> alternatives = new ArrayList<>();
		for (GoalPredicate predicate : predicates) {
			alternatives.addAll(requirementAlternatives(predicate));
		}
		return List.copyOf(alternatives);
	}

	private static Requirements merge(Requirements first, Requirements second) {
		HashMap<String, Integer> combined = new HashMap<>(first.fixed());
		// Each factual threshold reads the same inventory; only distinct item IDs consume separate space.
		second.fixed().forEach((itemId, count) -> combined.merge(itemId, count, Math::max));
		ArrayList<GoalPredicate.InventoryContainsAny> categories = new ArrayList<>(first.categories());
		categories.addAll(second.categories());
		// Repeated generic block facts test the same inventory, just like overlapping item categories.
		return new Requirements(Map.copyOf(combined), List.copyOf(categories), Math.max(first.blockCount(), second.blockCount()));
	}

	private static BlockCapacity resolveBlockCapacity(RegistryAccess registries) {
		Registry<Item> itemRegistry = registries.lookup(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
		HashSet<String> result = new HashSet<>();
		int maxStackSize = 1;
		int offhandCapacity = 0;
		EnumMap<EquipmentSlot, Integer> equipment = new EnumMap<>(EquipmentSlot.class);
		for (Identifier id : itemRegistry.keySet()) {
			Item item = itemRegistry.getValue(id);
			if (!(item instanceof BlockItem)) continue;
			result.add(id.toString());
			// Bootstrap-only registries may not yet have loaded item components. Their
			// generic blocks retain the optimistic stack bound until live data is available.
			ItemCapacity capacity = item.builtInRegistryHolder().areComponentsBound()
					? resolveCapacity(id.toString(), itemRegistry) : new ItemCapacity(64, 64, null, 0);
			maxStackSize = Math.max(maxStackSize, capacity.maxStackSize());
			offhandCapacity = Math.max(offhandCapacity, capacity.offhandCapacity());
			if (capacity.equipmentSlot() != null) equipment.merge(capacity.equipmentSlot(), capacity.equipmentCapacity(), Math::max);
		}
		return new BlockCapacity(Set.copyOf(result), maxStackSize, offhandCapacity,
				equipment.values().stream().mapToInt(Integer::intValue).sum());
	}

	private static Map<String, ItemCapacity> resolveCapacities(
			Iterable<Requirements> alternatives,
			RegistryAccess registries
	) {
		Registry<Item> itemRegistry = registries.lookup(Registries.ITEM).orElse(BuiltInRegistries.ITEM);
		HashMap<String, ItemCapacity> capacities = new HashMap<>();
		for (Requirements requirements : alternatives) {
			HashSet<String> itemIds = new HashSet<>(requirements.fixed().keySet());
			for (GoalPredicate.InventoryContainsAny category : requirements.categories()) itemIds.addAll(category.itemIds());
			for (String itemId : itemIds) {
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

	private static boolean possiblyFits(Requirements requirements, Map<String, ItemCapacity> capacities, BlockCapacity blockCapacity) {
		if (minimumGeneralSlots(requirements.fixed(), capacities) > Inventory.INVENTORY_SIZE) return false;
		if (blockMinimumGeneralSlots(requirements.blockCount(), blockCapacity, true) > Inventory.INVENTORY_SIZE) return false;
		if (requirements.categories().isEmpty() && requirements.blockCount() == 0) return true;
		ArrayList<RequirementGroup> groups = new ArrayList<>();
		for (GoalPredicate.InventoryContainsAny category : requirements.categories()) {
			addGroup(groups, new RequirementGroup(new HashSet<>(category.itemIds()), new HashMap<>(), new ArrayList<>(List.of(category)), 0));
		}
		requirements.fixed().forEach((itemId, count) -> addGroup(groups,
				new RequirementGroup(new HashSet<>(Set.of(itemId)), new HashMap<>(Map.of(itemId, count)), new ArrayList<>(), 0)));
		if (requirements.blockCount() > 0) {
			if (blockCapacity.itemIds().isEmpty()) return false;
			addGroup(groups, new RequirementGroup(new HashSet<>(blockCapacity.itemIds()), new HashMap<>(), new ArrayList<>(), requirements.blockCount()));
		}
		long lowerBound = 0L;
		long offhandSaving = 0L;
		for (RequirementGroup group : groups) {
			long withoutOffhand = minimumGeneralSlots(group.fixed, capacities, false);
			long withOffhand = minimumGeneralSlots(group.fixed, capacities, true);
			for (GoalPredicate.InventoryContainsAny category : group.categories) {
				withoutOffhand = Math.max(withoutOffhand, categoryMinimumGeneralSlots(category, capacities, false));
				withOffhand = Math.max(withOffhand, categoryMinimumGeneralSlots(category, capacities, true));
			}
			withoutOffhand = Math.max(withoutOffhand, blockMinimumGeneralSlots(group.blockCount, blockCapacity, false));
			withOffhand = Math.max(withOffhand, blockMinimumGeneralSlots(group.blockCount, blockCapacity, true));
			lowerBound += withoutOffhand;
			offhandSaving = Math.max(offhandSaving, withoutOffhand - withOffhand);
		}
		// Overlapping facts may share items. Disjoint groups can use only one offhand between them.
		// Equipment remains an optimistic bound for categories, so this cannot reject a feasible assignment.
		return lowerBound - offhandSaving <= Inventory.INVENTORY_SIZE;
	}

	private static long blockMinimumGeneralSlots(int count, BlockCapacity capacity, boolean allowOffhand) {
		long remaining = (long) count - capacity.equipmentCapacity() - (allowOffhand ? capacity.offhandCapacity() : 0);
		return divideRoundUp(Math.max(0L, remaining), capacity.maxStackSize());
	}

	private static void addGroup(List<RequirementGroup> groups, RequirementGroup addition) {
		for (int index = 0; index < groups.size();) {
			RequirementGroup existing = groups.get(index);
			if (java.util.Collections.disjoint(existing.itemIds, addition.itemIds)) {
				index++;
				continue;
			}
			addition.itemIds.addAll(existing.itemIds);
			addition.fixed.putAll(existing.fixed);
			addition.categories.addAll(existing.categories);
			addition.blockCount = Math.max(addition.blockCount, existing.blockCount);
			groups.remove(index);
			index = 0;
		}
		groups.add(addition);
	}

	private static long categoryMinimumGeneralSlots(
			GoalPredicate.InventoryContainsAny category, Map<String, ItemCapacity> capacities, boolean allowOffhand
	) {
		int maxStackSize = 1;
		int offhandCapacity = 0;
		EnumMap<EquipmentSlot, Integer> equipment = new EnumMap<>(EquipmentSlot.class);
		for (String itemId : category.itemIds()) {
			ItemCapacity capacity = capacities.get(itemId);
			maxStackSize = Math.max(maxStackSize, capacity.maxStackSize());
			offhandCapacity = Math.max(offhandCapacity, capacity.offhandCapacity());
			if (capacity.equipmentSlot() != null) equipment.merge(capacity.equipmentSlot(), capacity.equipmentCapacity(), Math::max);
		}
		long remaining = (long) category.count() - (allowOffhand ? offhandCapacity : 0);
		for (int capacity : equipment.values()) remaining -= capacity;
		return divideRoundUp(Math.max(0L, remaining), maxStackSize);
	}

	private static long minimumGeneralSlots(Map<String, Integer> requirements, Map<String, ItemCapacity> capacities) {
		return minimumGeneralSlots(requirements, capacities, true);
	}

	private static long minimumGeneralSlots(
			Map<String, Integer> requirements, Map<String, ItemCapacity> capacities, boolean allowOffhand
	) {
		List<Map.Entry<String, Integer>> items = List.copyOf(requirements.entrySet());
		long minimum = Long.MAX_VALUE;
		for (int offhandItem = -1; offhandItem < (allowOffhand ? items.size() : 0); offhandItem++) {
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
			minimum = Math.min(minimum, generalSlots);
		}
		return minimum;
	}

	private static long divideRoundUp(long count, int stackSize) {
		return count == 0L ? 0L : 1L + (count - 1L) / stackSize;
	}

	private record Requirements(Map<String, Integer> fixed, List<GoalPredicate.InventoryContainsAny> categories, int blockCount) {
	}

	private record BlockCapacity(Set<String> itemIds, int maxStackSize, int offhandCapacity, int equipmentCapacity) {
	}

	private static final class RequirementGroup {
		private final Set<String> itemIds;
		private final Map<String, Integer> fixed;
		private final List<GoalPredicate.InventoryContainsAny> categories;
		private int blockCount;

		private RequirementGroup(Set<String> itemIds, Map<String, Integer> fixed,
				List<GoalPredicate.InventoryContainsAny> categories, int blockCount) {
			this.itemIds = itemIds;
			this.fixed = fixed;
			this.categories = categories;
			this.blockCount = blockCount;
		}
	}

	private record ItemCapacity(
			int maxStackSize,
			int offhandCapacity,
			EquipmentSlot equipmentSlot,
			int equipmentCapacity
	) {
	}
}
