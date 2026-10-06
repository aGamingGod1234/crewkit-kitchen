package dev.agaminggod.arenaagents.pov;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Death screen state; {@code canRespawn} is only offered to the takeover operator. */
public record PovDeath(Component message, boolean canRespawn) {
	public static final int MAX_MESSAGE_LENGTH = 1024;
	public static final StreamCodec<RegistryFriendlyByteBuf, PovDeath> CODEC = StreamCodec.composite(
			ComponentSerialization.TRUSTED_STREAM_CODEC, PovDeath::message,
			ByteBufCodecs.BOOL, PovDeath::canRespawn,
			PovDeath::new
	);

	public PovDeath {
		message = Objects.requireNonNull(message, "message must not be null");
		if (message.getString().length() > MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("message exceeds " + MAX_MESSAGE_LENGTH + " characters");
		}
	}
}
