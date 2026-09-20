package dev.agaminggod.arenaagents.control;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Correlates a Director form submission with its server result. */
public record DirectorCommandRequestPayload(UUID requestId, String command) implements CustomPacketPayload {
	public static final int MAX_COMMAND_LENGTH = 1024;
	public DirectorCommandRequestPayload {
		Objects.requireNonNull(requestId);
		Objects.requireNonNull(command);
		if (command.length() > MAX_COMMAND_LENGTH) throw new IllegalArgumentException("Director command is too long");
	}
	public static final Type<DirectorCommandRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "director_command"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DirectorCommandRequestPayload> CODEC = new StreamCodec<>() {
		public DirectorCommandRequestPayload decode(RegistryFriendlyByteBuf buf) {
			return new DirectorCommandRequestPayload(buf.readUUID(), buf.readUtf(MAX_COMMAND_LENGTH));
		}
		public void encode(RegistryFriendlyByteBuf buf, DirectorCommandRequestPayload value) {
			buf.writeUUID(value.requestId());
			buf.writeUtf(value.command(), MAX_COMMAND_LENGTH);
		}
	};
	@Override public Type<DirectorCommandRequestPayload> type() { return TYPE; }
}
