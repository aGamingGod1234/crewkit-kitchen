package dev.agaminggod.arenaagents.server.pov;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shows a takeover operator the system and action-bar messages addressed to the agent body it drives, which a
 * player in that body would see: "Respawn point set", "You can sleep only at night", "You may not rest now; there
 * are monsters nearby", "This bed is occupied" and the like. They used to reach only the agent's Carpet connection,
 * so using a bed looked like it did nothing.
 *
 * <p>Broadcasts (death and join messages, advancements) already reach the operator directly. Vanilla sends a
 * broadcast to every player in one call, so a message the operator also received in the same tick is dropped here;
 * everything else is relayed at the end of the tick. Action-bar text is shown as the operator's own action bar,
 * chat lines are tagged with the agent's name.
 */
public final class PovMessageRelay {
	private static final Map<UUID, Target> TARGETS = new ConcurrentHashMap<>();
	private static final List<Pending> PENDING = new ArrayList<>();
	private static final Map<UUID, List<Component>> RECEIVED = new HashMap<>();

	private record Target(UUID operatorId, String agentName) {
	}

	private record Pending(UUID operatorId, String agentName, Component message, boolean overlay) {
	}

	/** Addressed text in a packet; null for every other packet. */
	record Text(Component message, boolean overlay) {
	}

	private PovMessageRelay() {
	}

	static void start(UUID agentPlayerUuid, UUID operatorId, String agentName) {
		TARGETS.put(Objects.requireNonNull(agentPlayerUuid, "agentPlayerUuid must not be null"),
				new Target(Objects.requireNonNull(operatorId, "operatorId must not be null"), agentName));
	}

	static void stop(UUID agentPlayerUuid, UUID operatorId) {
		if (agentPlayerUuid == null) return;
		TARGETS.computeIfPresent(agentPlayerUuid, (ignored, target) -> target.operatorId().equals(operatorId) ? null : target);
	}

	/** Called for every packet a server game connection sends; costs one map check while nobody is taken over. */
	public static void observe(ServerPlayer player, Packet<?> packet) {
		if (TARGETS.isEmpty() || player == null) return;
		Text text = text(packet);
		if (text == null) return;
		MinecraftServer server = player.level().getServer();
		if (!server.isSameThread()) {
			server.execute(() -> observe(player, packet));
			return;
		}
		Target target = TARGETS.get(player.getUUID());
		if (target != null) {
			PENDING.add(new Pending(target.operatorId(), target.agentName(), text.message(), text.overlay()));
			return;
		}
		for (Target candidate : TARGETS.values()) {
			if (candidate.operatorId().equals(player.getUUID())) {
				RECEIVED.computeIfAbsent(player.getUUID(), ignored -> new ArrayList<>()).add(text.message());
				return;
			}
		}
	}

	/** End of the server tick: relays what only the agent received, then forgets the tick. */
	static void flush(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			RECEIVED.clear();
			return;
		}
		List<Pending> pending = List.copyOf(PENDING);
		Map<UUID, List<Component>> received = Map.copyOf(RECEIVED);
		PENDING.clear();
		RECEIVED.clear();
		for (Pending message : pending) {
			if (!relay(message.message(), received.getOrDefault(message.operatorId(), List.of()))) continue;
			ServerPlayer operator = server.getPlayerList().getPlayer(message.operatorId());
			if (operator == null || operator.hasDisconnected()) continue;
			operator.sendSystemMessage(message.overlay() ? message.message() : tagged(message.agentName(), message.message()),
					message.overlay());
		}
		// The relayed sends above were recorded as received by the operator; they belong to no tick.
		RECEIVED.clear();
	}

	static void clearAll() {
		TARGETS.clear();
		PENDING.clear();
		RECEIVED.clear();
	}

	/** False when the operator got the same message itself this tick (a broadcast). */
	static boolean relay(Component message, List<Component> receivedByOperator) {
		return !receivedByOperator.contains(message);
	}

	static Text text(Packet<?> packet) {
		if (packet instanceof ClientboundSystemChatPacket chat) return new Text(chat.content(), chat.overlay());
		if (packet instanceof ClientboundSetActionBarTextPacket actionBar) return new Text(actionBar.text(), true);
		return null;
	}

	static Component tagged(String agentName, Component message) {
		String name = agentName == null || agentName.isBlank() ? "Agent" : agentName;
		return Component.empty()
				.append(Component.literal("[" + name + "] ").withStyle(ChatFormatting.GRAY))
				.append(message);
	}
}
