package dev.agaminggod.arenaagents.pov;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Exact look and pose of the watched agent, sent every tick so the view is not quantised to entity packets. */
public record AgentPovPosePayload(long sessionId, float yaw, float pitch, float attackStrength, int flags)
		implements CustomPacketPayload {
	public static final int FLAG_SNEAKING = 1;
	public static final int FLAG_SPRINTING = 2;
	public static final int FLAG_SWIMMING = 4;
	public static final int FLAG_FALL_FLYING = 8;
	public static final int FLAG_ON_GROUND = 16;
	public static final int FLAG_USING_ITEM = 32;
	public static final int FLAG_BLOCKING = 64;
	public static final int FLAG_DEAD = 128;
	private static final int KNOWN_FLAGS = 255;
	public static final Type<AgentPovPosePayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "pov_pose")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentPovPosePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, AgentPovPosePayload::sessionId,
			ByteBufCodecs.FLOAT, AgentPovPosePayload::yaw,
			ByteBufCodecs.FLOAT, AgentPovPosePayload::pitch,
			ByteBufCodecs.FLOAT, AgentPovPosePayload::attackStrength,
			ByteBufCodecs.VAR_INT, AgentPovPosePayload::flags,
			AgentPovPosePayload::new
	);

	public AgentPovPosePayload {
		PovPayloads.requireFinite(yaw, "yaw");
		PovPayloads.requireRange(pitch, -90f, 90f, "pitch");
		PovPayloads.requireRange(attackStrength, 0f, 1f, "attackStrength");
		if ((flags & ~KNOWN_FLAGS) != 0) throw new IllegalArgumentException("flags contain unknown bits");
	}

	public boolean hasFlag(int flag) {
		return (flags & flag) != 0;
	}

	@Override
	public Type<AgentPovPosePayload> type() {
		return TYPE;
	}
}
