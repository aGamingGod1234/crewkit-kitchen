package dev.agaminggod.arenaagents.control;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record DirectorCommandResultPayload(UUID requestId, boolean success, String message) implements CustomPacketPayload {
	public static final int MAX_MESSAGE_LENGTH = 512;
	public DirectorCommandResultPayload {
		Objects.requireNonNull(requestId);
		Objects.requireNonNull(message);
		if (message.length() > MAX_MESSAGE_LENGTH) message = message.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
	}
	public static final Type<DirectorCommandResultPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "director_command_result"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DirectorCommandResultPayload> CODEC = new StreamCodec<>() {
		public DirectorCommandResultPayload decode(RegistryFriendlyByteBuf buf) {
			return new DirectorCommandResultPayload(buf.readUUID(), buf.readBoolean(), buf.readUtf(MAX_MESSAGE_LENGTH));
		}
		public void encode(RegistryFriendlyByteBuf buf, DirectorCommandResultPayload value) {
			buf.writeUUID(value.requestId());
			buf.writeBoolean(value.success());
			buf.writeUtf(value.message(), MAX_MESSAGE_LENGTH);
		}
	};
	@Override public Type<DirectorCommandResultPayload> type() { return TYPE; }
}
