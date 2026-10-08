package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.protocol.ActionType;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * What an agent with no task may do to get food, checked against the live world like a player's eyes would: break a
 * fully grown crop or a melon, right-click ripe sweet berries or glow berries (as vanilla harvests them), and pick up a
 * dropped stack that is food. Everything else that touches blocks or items still needs a task.
 */
public final class DetachedFoodTargets {
	/** Crops at full age, keyed by block id; melons drop slices whenever broken. */
	private static final Map<String, Integer> CROP_MAX_AGE = Map.of("minecraft:wheat", 7, "minecraft:carrots", 7,
			"minecraft:potatoes", 7, "minecraft:beetroots", 3);
	public static final Set<String> BREAKABLE_FOOD = Set.of("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes",
			"minecraft:beetroots", "minecraft:melon");
	public static final Set<String> RIGHT_CLICK_FOOD = Set.of("minecraft:sweet_berry_bush", "minecraft:cave_vines",
			"minecraft:cave_vines_plant");

	private DetachedFoodTargets() {
	}

	/** A block that breaking yields food from now: a crop at its maximum age, or a melon. */
	public static boolean harvestableByBreaking(String blockId, Map<String, String> properties) {
		if ("minecraft:melon".equals(blockId)) return true;
		Integer maxAge = CROP_MAX_AGE.get(blockId);
		return maxAge != null && maxAge.toString().equals(properties.get("age"));
	}

	/** A plant whose berries a right-click picks: a sweet berry bush aged 2+, or cave vines bearing glow berries. */
	public static boolean harvestableByUse(String blockId, Map<String, String> properties) {
		if ("minecraft:sweet_berry_bush".equals(blockId)) {
			try {
				return Integer.parseInt(properties.getOrDefault("age", "0")) >= 2;
			} catch (NumberFormatException malformed) {
				return false;
			}
		}
		return RIGHT_CLICK_FOOD.contains(blockId) && "true".equals(properties.get("berries"));
	}

	/** Only a chosen, visible item entity (its UUID) can be checked for food; "nearest_item" and the like cannot. */
	public static UUID explicitItem(String selector) {
		if (selector == null) return null;
		try {
			return UUID.fromString(selector);
		} catch (IllegalArgumentException notUuid) {
			return null;
		}
	}

	/** Refuses a detached block or item action whose live target is not food. Other action types pass through. */
	public static void require(ServerPlayer player, ActionType type, JsonObject arguments) {
		if (type != ActionType.BREAK_BLOCK && type != ActionType.INTERACT_BLOCK && type != ActionType.PICK_UP_ITEM) return;
		if (player == null) throw new AgentDomainException("PLAYER_UNAVAILABLE", "No player body to act with");
		if (type == ActionType.PICK_UP_ITEM) {
			String selector = string(arguments, "targetSelector");
			UUID itemId = explicitItem(selector);
			Entity entity = itemId == null ? null : player.level().getEntity(itemId);
			if (!(entity instanceof ItemEntity item) || item.getItem().get(DataComponents.FOOD) == null) {
				throw new AgentDomainException("NO_TASK_NOT_FOOD", "With no task you may only pick up a visible food item; other work needs takeTask");
			}
			return;
		}
		BlockPos position = new BlockPos(integer(arguments, "x"), integer(arguments, "y"), integer(arguments, "z"));
		BlockState state = player.level().getBlockState(position);
		String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		Map<String, String> properties = properties(state);
		boolean food = type == ActionType.BREAK_BLOCK ? harvestableByBreaking(blockId, properties) : harvestableByUse(blockId, properties);
		if (!food) {
			throw new AgentDomainException("NO_TASK_NOT_FOOD", type == ActionType.BREAK_BLOCK
					? "With no task you may only break a fully grown crop or a melon; other work needs takeTask"
					: "With no task you may only right-click ripe berries or glow berries; other work needs takeTask");
		}
	}

	private static Map<String, String> properties(BlockState state) {
		Map<String, String> values = new HashMap<>();
		for (Property<?> property : state.getProperties()) values.put(property.getName(), valueName(state, property));
		return values;
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	private static String string(JsonObject arguments, String field) {
		JsonElement value = arguments == null ? null : arguments.get(field);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
	}

	private static int integer(JsonObject arguments, String field) {
		JsonElement value = arguments == null ? null : arguments.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new AgentDomainException("INVALID_ARGUMENTS", "Block position is required");
		}
		return value.getAsInt();
	}
}
