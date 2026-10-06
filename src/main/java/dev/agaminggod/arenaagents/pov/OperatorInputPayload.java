package dev.agaminggod.arenaagents.pov;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Continuous takeover input, sent every client tick. {@code sequence} increases with every frame of a session so
 * the server can ignore stale or reordered frames.
 */
public record OperatorInputPayload(
		long sessionId,
		int sequence,
		float forward,
		float strafe,
		float yaw,
		float pitch,
		int heldFlags,
		int selectedSlot
) implements CustomPacketPayload {
	public static final int HELD_JUMP = 1;
	public static final int HELD_SNEAK = 2;
	public static final int HELD_SPRINT = 4;
	public static final int HELD_ATTACK = 8;
	public static final int HELD_USE = 16;
	private static final int KNOWN_HELD_FLAGS = 31;
	public static final Type<OperatorInputPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "operator_input")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, OperatorInputPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, OperatorInputPayload::sessionId,
			ByteBufCodecs.VAR_INT, OperatorInputPayload::sequence,
			ByteBufCodecs.FLOAT, OperatorInputPayload::forward,
			ByteBufCodecs.FLOAT, OperatorInputPayload::strafe,
			ByteBufCodecs.FLOAT, OperatorInputPayload::yaw,
			ByteBufCodecs.FLOAT, OperatorInputPayload::pitch,
			ByteBufCodecs.VAR_INT, OperatorInputPayload::heldFlags,
			ByteBufCodecs.VAR_INT, OperatorInputPayload::selectedSlot,
			OperatorInputPayload::new
	);

	public OperatorInputPayload {
		if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
		PovPayloads.requireRange(forward, -1f, 1f, "forward");
		PovPayloads.requireRange(strafe, -1f, 1f, "strafe");
		PovPayloads.requireFinite(yaw, "yaw");
		PovPayloads.requireRange(pitch, -90f, 90f, "pitch");
		if ((heldFlags & ~KNOWN_HELD_FLAGS) != 0) throw new IllegalArgumentException("heldFlags contain unknown bits");
		if (selectedSlot < 0 || selectedSlot >= PovInventory.HOTBAR_SIZE) {
			throw new IllegalArgumentException("selectedSlot must be within 0.." + (PovInventory.HOTBAR_SIZE - 1));
		}
	}

	public boolean isHeld(int heldFlag) {
		return (heldFlags & heldFlag) != 0;
	}

	@Override
	public Type<OperatorInputPayload> type() {
		return TYPE;
	}
}
