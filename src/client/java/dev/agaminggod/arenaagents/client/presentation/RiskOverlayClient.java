package dev.agaminggod.arenaagents.client.presentation;

import dev.agaminggod.arenaagents.risk.RiskOverlayPayload;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Latest risk view of the agent this player spectates or takes over, shown as a compact suffix on name tags
 * ("⚠ 42" in red while active, a dim "potential 18" otherwise). Presentation only.
 */
public final class RiskOverlayClient {
	private static volatile Map<Integer, RiskOverlayPayload.Row> rows = Map.of();

	private RiskOverlayClient() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RiskOverlayPayload.TYPE, (payload, context) -> {
			Map<Integer, RiskOverlayPayload.Row> next = new HashMap<>();
			for (RiskOverlayPayload.Row row : payload.rows()) next.put(row.entityId(), row);
			rows = Map.copyOf(next);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> rows = Map.of());
	}

	public static boolean has(int entityId) {
		Map<Integer, RiskOverlayPayload.Row> current = rows;
		// Fast path: nothing to draw (no boxing per rendered entity) unless this player watches an agent.
		return !current.isEmpty() && current.containsKey(entityId);
	}

	/** The name tag with the risk label appended (or the label alone), or {@code current} when no row exists. */
	public static Component decorate(int entityId, Component current) {
		RiskOverlayPayload.Row row = rows.get(entityId);
		if (row == null) return current;
		MutableComponent label = Component.literal(RiskOverlayPayload.label(row.risk(), row.active()))
				.withStyle(row.active() ? ChatFormatting.RED : ChatFormatting.GRAY);
		return current == null ? label : Component.empty().append(current).append(" ").append(label);
	}
}
