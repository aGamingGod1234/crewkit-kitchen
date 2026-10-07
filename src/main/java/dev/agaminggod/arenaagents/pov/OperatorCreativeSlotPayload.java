package dev.agaminggod.arenaagents.pov;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * A creative inventory change the operator made for a creative agent: ServerboundSetCreativeModeSlotPacket's slot
 * and stack, decoded with the same validated untrusted item codec. The server hands it to the agent's connection,
 * so vanilla's creative checks (infinite materials, enabled features, slot range, stack size, drop throttle) apply.
 */
public record OperatorCreativeSlotPayload(long sessionId, int sequence, short slot, ItemStack stack) implements CustomPacketPayload {
	/** One stack with components; generous next to vanilla's own packet, still bounded before decoding. */
	public static final int MAX_ENCODED_BYTES = 2 * 1024 * 1024;

	public static final Type<OperatorCreativeSlotPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "operator_creative_slot")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, OperatorCreativeSlotPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, OperatorCreativeSlotPayload::sessionId,
			ByteBufCodecs.VAR_INT, OperatorCreativeSlotPayload::sequence,
			ByteBufCodecs.SHORT, OperatorCreativeSlotPayload::slot,
			ItemStack.validatedStreamCodec(ItemStack.OPTIONAL_UNTRUSTED_STREAM_CODEC), OperatorCreativeSlotPayload::stack,
			OperatorCreativeSlotPayload::new
	);

	public OperatorCreativeSlotPayload {
		if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
		stack = Objects.requireNonNull(stack, "stack must not be null; use ItemStack.EMPTY").copy();
	}

	@Override
	public Type<OperatorCreativeSlotPayload> type() {
		return TYPE;
	}
}
