package dev.agaminggod.arenaagents.pov;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ContainerInput;

/** One-shot takeover input, sent per click; see {@link OperatorAction} for the meaning of {@code a}, {@code b}, {@code c}. */
public record OperatorActionPayload(long sessionId, int sequence, OperatorAction action, int a, int b, int c)
		implements CustomPacketPayload {
	public static final Type<OperatorActionPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "operator_action")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, OperatorActionPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, OperatorActionPayload::sessionId,
			ByteBufCodecs.VAR_INT, OperatorActionPayload::sequence,
			PovPayloads.enumCodec(OperatorAction.values()), OperatorActionPayload::action,
			ByteBufCodecs.VAR_INT, OperatorActionPayload::a,
			ByteBufCodecs.VAR_INT, OperatorActionPayload::b,
			ByteBufCodecs.VAR_INT, OperatorActionPayload::c,
			OperatorActionPayload::new
	);

	public OperatorActionPayload {
		if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
		action = Objects.requireNonNull(action, "action must not be null");
		if (action == OperatorAction.MENU_CLICK && (c < 0 || c >= ContainerInput.values().length)) {
			throw new IllegalArgumentException("MENU_CLICK carries an unknown container input ordinal");
		}
	}

	/** The click type of a {@link OperatorAction#MENU_CLICK}. */
	public ContainerInput containerInput() {
		if (action != OperatorAction.MENU_CLICK) throw new IllegalStateException(action + " carries no container input");
		return ContainerInput.values()[c];
	}

	@Override
	public Type<OperatorActionPayload> type() {
		return TYPE;
	}
}
