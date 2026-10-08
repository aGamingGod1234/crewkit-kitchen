package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuStackIdentity;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Player-readable details. Raw components, entity AI state, and unopened storage stay private. */
public final class ObservationDetails {
	private ObservationDetails() { }

	public static JsonObject item(ServerPlayer player, ItemStack stack, boolean detailed) {
		JsonObject item = new JsonObject();
		item.addProperty("itemId", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		item.addProperty("count", stack.getCount());
		item.addProperty("damage", stack.getDamageValue());
		item.addProperty("maxDamage", stack.getMaxDamage());
		item.addProperty("maxStackSize", stack.getMaxStackSize());
		item.addProperty("displayName", bounded(stack.getHoverName().getString(), 256));
		item.addProperty("fingerprint", MenuStackIdentity.fingerprint(stack, player.registryAccess()));
		if (detailed && !stack.isEmpty()) {
			var lines = stack.getTooltipLines(Item.TooltipContext.of(player.level()), player, TooltipFlag.NORMAL);
			JsonArray tooltip = new JsonArray();
			int bytes = 0;
			for (var line : lines) {
				String text = bounded(line.getString(), 256);
				int size = text.getBytes(StandardCharsets.UTF_8).length;
				if (tooltip.size() >= 16 || bytes + size > 2_048) break;
				tooltip.add(text);
				bytes += size;
			}
			item.add("tooltip", tooltip);
			item.addProperty("tooltipTruncated", lines.size() > tooltip.size()
					|| lines.stream().limit(tooltip.size()).anyMatch(line -> line.getString().codePointCount(0, line.getString().length()) > 256));
		}
		return item;
	}

	static void entity(JsonObject target, ServerPlayer player, Entity entity) {
		target.addProperty("pickable", entity.isPickable());
		if (entity instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragonPart part) {
			target.addProperty("parentId", part.parentMob.getUUID().toString());
			target.addProperty("partName", part.name);
		}
		target.add("velocity", vector(entity.getDeltaMovement()));
		target.addProperty("yaw", entity.getYRot());
		target.addProperty("pitch", entity.getXRot());
		target.addProperty("pose", entity.getPose().name().toLowerCase(Locale.ROOT));
		target.add("bounds", bounds(entity.getBoundingBox()));
		if (entity instanceof LivingEntity living) {
			JsonArray equipment = new JsonArray();
			for (EquipmentSlot slot : EquipmentSlot.values()) {
				ItemStack stack = living.getItemBySlot(slot);
				if (stack.isEmpty()) continue;
				// Other players' tooltips and hidden item components are not visible equipment facts.
				JsonObject held = new JsonObject();
				held.addProperty("slot", slot.getName());
				held.addProperty("itemId", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
				held.addProperty("enchanted", stack.hasFoil());
				equipment.add(held);
			}
			target.add("equipment", equipment);
			target.addProperty("usingItem", living.isUsingItem());
			target.addProperty("onFire", living.isOnFire());
			combat(target, player, living);
		}
	}

	/** Fight-or-flee facts the model needs: hostility, whether it hunts this agent, health and creeper fuse. */
	static void combat(JsonObject target, ServerPlayer player, LivingEntity living) {
		target.addProperty("alive", living.isAlive());
		// A calm neutral mob (enderman, zombified piglin, piglin tolerating gold) is not hostile to this agent.
		// A player (or calm creature) counts as hostile only once it actually hurt this agent recently.
		target.addProperty("hostile", (living instanceof net.minecraft.world.entity.Mob mob
				? ThreatPerception.isHostileTo(mob, player) : living instanceof net.minecraft.world.entity.monster.Enemy)
				|| RiskAssessment.attackedRecently(player, living));
		if (RiskAssessment.carriesRisk(player, living)) {
			// potentialRisk is always shown; risk appears only while the creature or player actively engages this agent.
			RiskAssessment.Assessment assessment = RiskAssessment.assess(player, living);
			target.addProperty("potentialRisk", assessment.risk());
			if (assessment.active()) target.addProperty("risk", assessment.risk());
			target.addProperty("expectedHitDamage", assessment.hit().damage());
		}
		target.addProperty("health", Float.isFinite(living.getHealth()) ? living.getHealth() : 0.0F);
		target.addProperty("maxHealth", Float.isFinite(living.getMaxHealth()) ? living.getMaxHealth() : 0.0F);
		if (living instanceof net.minecraft.world.entity.Mob mob) target.addProperty("targetingAgent", mob.getTarget() == player);
		if (living instanceof net.minecraft.world.entity.monster.Creeper creeper) {
			target.addProperty("swelling", creeper.getSwellDir() > 0);
			// 0 idle .. 1 exploding; vanilla's fuse is 1.5 s from 0 to 1.
			target.addProperty("fuse", Math.round(creeper.getSwelling(1.0F) * 100.0F) / 100.0D);
		}
	}

	static void block(JsonObject target, ServerPlayer player, BlockPos position, BlockState state) {
		JsonObject properties = new JsonObject();
		for (Property<?> property : state.getProperties()) properties.addProperty(property.getName(), propertyValue(state, property));
		target.add("state", properties);
		var boxes = state.getShape(player.level(), position).toAabbs();
		JsonArray shape = new JsonArray();
		boxes.stream().limit(16).forEach(box -> shape.add(bounds(box)));
		target.add("bounds", shape);
		target.addProperty("boundsTruncated", boxes.size() > 16);
		target.addProperty("replaceable", state.canBeReplaced());
		if (!state.getFluidState().isEmpty()) {
			JsonObject fluid = new JsonObject();
			fluid.addProperty("type", BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString());
			fluid.addProperty("height", state.getFluidState().getHeight(player.level(), position));
			target.add("fluid", fluid);
		}
	}

	private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}

	static JsonObject vector(Vec3 value) {
		JsonObject point = new JsonObject();
		point.addProperty("x", value.x);
		point.addProperty("y", value.y);
		point.addProperty("z", value.z);
		return point;
	}

	static JsonObject bounds(AABB value) {
		JsonObject box = new JsonObject();
		box.addProperty("minX", value.minX);
		box.addProperty("minY", value.minY);
		box.addProperty("minZ", value.minZ);
		box.addProperty("maxX", value.maxX);
		box.addProperty("maxY", value.maxY);
		box.addProperty("maxZ", value.maxZ);
		return box;
	}

	static String bounded(String value, int maximum) {
		return value.codePointCount(0, value.length()) <= maximum
				? value : value.substring(0, value.offsetByCodePoints(0, maximum));
	}
}
