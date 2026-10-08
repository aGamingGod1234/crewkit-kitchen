package dev.agaminggod.arenaagents.pov;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Exact look, pose and position of the watched agent, sent at the end of every server tick (after physics) so the
 * view is not quantised to entity packets. Vanilla entity tracking broadcasts players every second tick, before
 * that tick's physics, and the client then lerps over three more ticks; a takeover camera that waited for that
 * trailed the operator's input by several hundred milliseconds. {@code inputSequence} is the last operator frame
 * applied before this pose (0 when none), so the client can measure its input round trip.
 */
public record AgentPovPosePayload(long sessionId, float yaw, float pitch, float attackStrength, int flags,
		double x, double y, double z, int inputSequence)
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
			ByteBufCodecs.DOUBLE, AgentPovPosePayload::x,
			ByteBufCodecs.DOUBLE, AgentPovPosePayload::y,
			ByteBufCodecs.DOUBLE, AgentPovPosePayload::z,
			ByteBufCodecs.VAR_INT, AgentPovPosePayload::inputSequence,
			AgentPovPosePayload::new
	);

	public AgentPovPosePayload {
		PovPayloads.requireFinite(yaw, "yaw");
		PovPayloads.requireRange(pitch, -90f, 90f, "pitch");
		PovPayloads.requireRange(attackStrength, 0f, 1f, "attackStrength");
		if ((flags & ~KNOWN_FLAGS) != 0) throw new IllegalArgumentException("flags contain unknown bits");
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
			throw new IllegalArgumentException("position must be finite");
		}
		if (inputSequence < 0) throw new IllegalArgumentException("inputSequence must not be negative");
	}

	public boolean hasFlag(int flag) {
		return (flags & flag) != 0;
	}

	@Override
	public Type<AgentPovPosePayload> type() {
		return TYPE;
	}
}
