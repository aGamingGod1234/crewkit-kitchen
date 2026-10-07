package dev.agaminggod.arenaagents.pov;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The taken-over agent opened a book. A vanilla client opens the editor (writable book) or the reader (written book)
 * from the item in its own hand, but the operator's hand holds something else, so the server sends the agent's
 * stack and the inventory slot a ServerboundEditBookPacket must name.
 */
public record AgentPovBookPayload(long sessionId, InteractionHand hand, int slot, ItemStack book) implements CustomPacketPayload {
	public static final Type<AgentPovBookPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "agent_pov_book")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentPovBookPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, AgentPovBookPayload::sessionId,
			PovPayloads.enumCodec(InteractionHand.values()), AgentPovBookPayload::hand,
			ByteBufCodecs.VAR_INT, AgentPovBookPayload::slot,
			ItemStack.OPTIONAL_STREAM_CODEC, AgentPovBookPayload::book,
			AgentPovBookPayload::new
	);

	public AgentPovBookPayload {
		hand = Objects.requireNonNull(hand, "hand must not be null");
		book = Objects.requireNonNull(book, "book must not be null").copy();
	}

	@Override
	public Type<AgentPovBookPayload> type() {
		return TYPE;
	}
}
