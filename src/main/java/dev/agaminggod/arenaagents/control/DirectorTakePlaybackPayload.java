package dev.agaminggod.arenaagents.control;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Negative start time ends the owner's take and restores its camera. */
public record DirectorTakePlaybackPayload(String name, String camera, long startGameTime, String message) implements CustomPacketPayload {
	public DirectorTakePlaybackPayload { if (message.length() > 512) message = message.substring(0,512); }
	public static final Type<DirectorTakePlaybackPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "director_take_playback"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DirectorTakePlaybackPayload> CODEC = new StreamCodec<>() {
		public DirectorTakePlaybackPayload decode(RegistryFriendlyByteBuf b) { return new DirectorTakePlaybackPayload(b.readUtf(64), b.readUtf(64), b.readLong(), b.readUtf(512)); }
		public void encode(RegistryFriendlyByteBuf b, DirectorTakePlaybackPayload v) { b.writeUtf(v.name(),64); b.writeUtf(v.camera(),64); b.writeLong(v.startGameTime()); b.writeUtf(v.message(),512); }
	};
	@Override public Type<DirectorTakePlaybackPayload> type() { return TYPE; }
}
