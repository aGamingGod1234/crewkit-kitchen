package dev.agaminggod.arenaagents.control;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AgentControlSnapshotPayload(String encodedSnapshot) implements CustomPacketPayload {
	public static final Type<AgentControlSnapshotPayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "agent_control_snapshot")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentControlSnapshotPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(AgentControlSnapshotCodec.MAX_ENCODED_LENGTH),
			AgentControlSnapshotPayload::encodedSnapshot,
			AgentControlSnapshotPayload::new
	);

	public AgentControlSnapshotPayload {
		encodedSnapshot = Objects.requireNonNull(encodedSnapshot, "encodedSnapshot must not be null");
		AgentControlSnapshotCodec.decode(encodedSnapshot);
	}

	public static AgentControlSnapshotPayload fromSnapshot(AgentControlSnapshot snapshot) {
		return new AgentControlSnapshotPayload(AgentControlSnapshotCodec.encode(snapshot));
	}

	public AgentControlSnapshot snapshot() {
		return AgentControlSnapshotCodec.decode(encodedSnapshot);
	}

	@Override
	public Type<AgentControlSnapshotPayload> type() {
		return TYPE;
	}
}
