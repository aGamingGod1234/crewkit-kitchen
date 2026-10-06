package dev.agaminggod.arenaagents.pov;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server-owned end of a POV session; {@code reason} is shown to the operator as written. */
public record PovStopPayload(long sessionId, String reason) implements CustomPacketPayload {
	public static final int MAX_REASON_LENGTH = 256;
	public static final Type<PovStopPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "pov_stop")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, PovStopPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, PovStopPayload::sessionId,
			ByteBufCodecs.stringUtf8(MAX_REASON_LENGTH), PovStopPayload::reason,
			PovStopPayload::new
	);

	public PovStopPayload {
		reason = Objects.requireNonNull(reason, "reason must not be null");
		if (reason.length() > MAX_REASON_LENGTH) {
			throw new IllegalArgumentException("reason exceeds " + MAX_REASON_LENGTH + " characters");
		}
	}

	@Override
	public Type<PovStopPayload> type() {
		return TYPE;
	}
}
