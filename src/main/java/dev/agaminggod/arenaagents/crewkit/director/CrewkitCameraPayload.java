package dev.agaminggod.arenaagents.crewkit.director;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server tells clients to glide the CrewKit camera to a mark over moveTicks, then drift in over holdTicks. Origin is the set origin. */
public record CrewkitCameraPayload(String mark, int moveTicks, int holdTicks, int originX, int originY, int originZ) implements CustomPacketPayload {
	public static final Type<CrewkitCameraPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "crewkit_camera"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CrewkitCameraPayload> CODEC = new StreamCodec<>() {
		public CrewkitCameraPayload decode(RegistryFriendlyByteBuf b) {
			return new CrewkitCameraPayload(b.readUtf(32), b.readVarInt(), b.readVarInt(), b.readInt(), b.readInt(), b.readInt());
		}
		public void encode(RegistryFriendlyByteBuf b, CrewkitCameraPayload v) {
			b.writeUtf(v.mark(), 32); b.writeVarInt(v.moveTicks()); b.writeVarInt(v.holdTicks()); b.writeInt(v.originX()); b.writeInt(v.originY()); b.writeInt(v.originZ());
		}
	};

	private static boolean registered;

	/** Idempotent; called by DirectorFeature and the client initializer, whichever runs first. */
	public static synchronized void register() {
		if (registered) return;
		registered = true;
		try {
			PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
		} catch (IllegalArgumentException alreadyRegistered) {
			// Registered elsewhere already.
		}
	}

	@Override public Type<CrewkitCameraPayload> type() { return TYPE; }
}
