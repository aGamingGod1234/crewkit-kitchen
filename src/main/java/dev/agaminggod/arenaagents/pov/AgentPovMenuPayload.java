package dev.agaminggod.arenaagents.pov;

import java.util.List;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Contents of the agent's open menu for a client-only mirrored screen. It must never feed vanilla container
 * packets: the agent's inventory menu shares container id 0 with the operator's own inventory.
 */
public record AgentPovMenuPayload(
		long sessionId,
		int containerId,
		int stateId,
		List<ItemStack> slots,
		ItemStack carried,
		List<Integer> dataSlots
) implements CustomPacketPayload {
	public static final int MAX_SLOTS = 256;
	public static final int MAX_DATA_SLOTS = 32;
	public static final Type<AgentPovMenuPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "pov_menu")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentPovMenuPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, AgentPovMenuPayload::sessionId,
			ByteBufCodecs.VAR_INT, AgentPovMenuPayload::containerId,
			ByteBufCodecs.VAR_INT, AgentPovMenuPayload::stateId,
			ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SLOTS)), AgentPovMenuPayload::slots,
			ItemStack.OPTIONAL_STREAM_CODEC, AgentPovMenuPayload::carried,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_DATA_SLOTS)), AgentPovMenuPayload::dataSlots,
			AgentPovMenuPayload::new
	);

	public AgentPovMenuPayload {
		if (containerId < 0) throw new IllegalArgumentException("containerId must not be negative");
		if (stateId < 0) throw new IllegalArgumentException("stateId must not be negative");
		slots = PovPayloads.copyStacks(slots, MAX_SLOTS, "slots");
		carried = Objects.requireNonNull(carried, "carried must not be null; use ItemStack.EMPTY").copy();
		Objects.requireNonNull(dataSlots, "dataSlots must not be null");
		if (dataSlots.size() > MAX_DATA_SLOTS) throw new IllegalArgumentException("dataSlots exceed " + MAX_DATA_SLOTS);
		dataSlots = List.copyOf(dataSlots);
	}

	@Override
	public Type<AgentPovMenuPayload> type() {
		return TYPE;
	}
}
