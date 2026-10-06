package dev.agaminggod.arenaagents.pov;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Session header. {@code entityId} is only a hint (it changes on respawn); clients bind the camera by
 * {@code agentUuid}. {@code lookResetSeq} increases when the server forces the agent's look, so a takeover client
 * drops its locally predicted yaw and pitch.
 */
public record PovIdentity(
		long sessionId,
		int revision,
		PovMode mode,
		UUID agentUuid,
		int entityId,
		ResourceKey<Level> dimension,
		String agentName,
		int lookResetSeq
) {
	public static final int MAX_AGENT_NAME_LENGTH = 64;
	public static final StreamCodec<RegistryFriendlyByteBuf, PovIdentity> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, PovIdentity::sessionId,
			ByteBufCodecs.VAR_INT, PovIdentity::revision,
			PovPayloads.enumCodec(PovMode.values()), PovIdentity::mode,
			UUIDUtil.STREAM_CODEC, PovIdentity::agentUuid,
			ByteBufCodecs.VAR_INT, PovIdentity::entityId,
			ResourceKey.streamCodec(Registries.DIMENSION), PovIdentity::dimension,
			ByteBufCodecs.stringUtf8(MAX_AGENT_NAME_LENGTH), PovIdentity::agentName,
			ByteBufCodecs.VAR_INT, PovIdentity::lookResetSeq,
			PovIdentity::new
	);

	public PovIdentity {
		if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
		mode = Objects.requireNonNull(mode, "mode must not be null");
		agentUuid = Objects.requireNonNull(agentUuid, "agentUuid must not be null");
		dimension = Objects.requireNonNull(dimension, "dimension must not be null");
		agentName = Objects.requireNonNull(agentName, "agentName must not be null");
		if (agentName.isBlank() || agentName.length() > MAX_AGENT_NAME_LENGTH) {
			throw new IllegalArgumentException(
					"agentName must be non-blank and at most " + MAX_AGENT_NAME_LENGTH + " characters");
		}
		if (lookResetSeq < 0) throw new IllegalArgumentException("lookResetSeq must not be negative");
	}
}
