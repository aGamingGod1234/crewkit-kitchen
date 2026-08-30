package dev.agaminggod.arenaagents.server.runtime;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Java-side protocol seam that keeps a terminal result and its authoritative
 * post-action player facts in one immutable publication unit.
 *
 * <p>The v2 wire schema still publishes only {@link #result()}. A future protocol
 * revision can add an optional {@code postActionFacts} object beside the result,
 * then let the coordinator seed its next fact set from the same terminal event.
 * The field must be negotiated by protocol version before it is written; v2 peers
 * reject unknown fields. This avoids recollecting mutable world state after the
 * server-tick publication boundary.</p>
 */
public record ActionTerminalPublication(
		ServerActionResult result,
		Optional<PostActionFacts> postActionFacts
) {
	public ActionTerminalPublication {
		Objects.requireNonNull(result, "result must not be null");
		postActionFacts = Objects.requireNonNull(postActionFacts, "postActionFacts must not be null");
		postActionFacts.ifPresent(facts -> {
			if (!facts.agentId().equals(result.agentId())) {
				throw new IllegalArgumentException("post-action facts must belong to the result agent");
			}
		});
	}

	public static ActionTerminalPublication capture(
			ServerActionResult result,
			Optional<ServerPlayer> player,
			long capturedAtEpochMs
	) {
		Objects.requireNonNull(player, "player must not be null");
		return new ActionTerminalPublication(
				result,
				player.map(value -> PostActionFacts.capture(result.agentId(), value, capturedAtEpochMs))
		);
	}

	public record PostActionFacts(
			AgentId agentId,
			String dimensionId,
			double x,
			double y,
			double z,
			float health,
			int foodLevel,
			int selectedSlot,
			String selectedItemId,
			Map<String, Integer> inventoryCounts,
			boolean alive,
			long capturedAtEpochMs
	) {
		public PostActionFacts {
			Objects.requireNonNull(agentId, "agentId must not be null");
			Objects.requireNonNull(dimensionId, "dimensionId must not be null");
			Objects.requireNonNull(selectedItemId, "selectedItemId must not be null");
			inventoryCounts = Map.copyOf(Objects.requireNonNull(inventoryCounts, "inventoryCounts must not be null"));
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
					|| !Float.isFinite(health) || capturedAtEpochMs <= 0L) {
				throw new IllegalArgumentException("post-action facts are invalid");
			}
		}

		private static PostActionFacts capture(AgentId agentId, ServerPlayer player, long capturedAtEpochMs) {
			ItemStack selected = player.getInventory().getSelectedItem();
			String selectedItemId = selected.isEmpty()
					? "minecraft:air"
					: BuiltInRegistries.ITEM.getKey(selected.getItem()).toString();
			Map<String, Integer> inventoryCounts = new LinkedHashMap<>();
			for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				if (stack.isEmpty()) continue;
				inventoryCounts.merge(
						BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
						stack.getCount(),
						Math::addExact
				);
			}
			return new PostActionFacts(
					agentId,
					player.level().dimension().identifier().toString(),
					player.getX(), player.getY(), player.getZ(),
					player.getHealth(), player.getFoodData().getFoodLevel(),
					player.getInventory().getSelectedSlot(), selectedItemId, inventoryCounts,
					player.isAlive(), capturedAtEpochMs
			);
		}
	}
}
