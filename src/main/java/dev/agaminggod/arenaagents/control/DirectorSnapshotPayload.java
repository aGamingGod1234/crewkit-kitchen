package dev.agaminggod.arenaagents.control;

import java.util.List;
import dev.agaminggod.arenaagents.server.voice.VoiceProfile;
import java.util.ArrayList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Director state has its own roster and does not enter the agent-control schema. */
public record DirectorSnapshotPayload(boolean enabled, boolean canControl, List<Actor> actors) implements CustomPacketPayload {
	public record Actor(String id, String name, String playerName, String appearance, boolean dead, boolean present, String speechStatus, VoiceProfile voiceProfile) {
		public Actor { java.util.Objects.requireNonNull(voiceProfile, "voiceProfile"); }
		public Actor(String id, String name, String playerName, String appearance, boolean dead, boolean present, String speechStatus) {
			this(id, name, playerName, appearance, dead, present, speechStatus, VoiceProfile.defaults());
		}
		public Actor(String id, String name, String playerName, String appearance, boolean dead, boolean present) {
			this(id, name, playerName, appearance, dead, present, "");
		}
	}
	public DirectorSnapshotPayload { actors = List.copyOf(actors); if (actors.size() > 32) throw new IllegalArgumentException("Cast too large"); }
	public static final Type<DirectorSnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "director_snapshot_v3"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DirectorSnapshotPayload> CODEC = new StreamCodec<>() {
		public DirectorSnapshotPayload decode(RegistryFriendlyByteBuf buf) {
			boolean enabled = buf.readBoolean(), canControl = buf.readBoolean();
			int size = buf.readVarInt();
			if (size < 0 || size > 32) throw new IllegalArgumentException("Invalid cast size");
			List<Actor> actors = new ArrayList<>();
			for (int i = 0; i < size; i++) actors.add(new Actor(buf.readUtf(36), buf.readUtf(64), buf.readUtf(16), buf.readUtf(16), buf.readBoolean(), buf.readBoolean(), buf.readUtf(512),
					new VoiceProfile(buf.readUtf(128), buf.readUtf(32), buf.readDouble(), buf.readVarInt())));
			return new DirectorSnapshotPayload(enabled, canControl, actors);
		}
		public void encode(RegistryFriendlyByteBuf buf, DirectorSnapshotPayload value) {
			buf.writeBoolean(value.enabled()); buf.writeBoolean(value.canControl()); buf.writeVarInt(value.actors().size());
			for (Actor actor : value.actors()) {
				buf.writeUtf(actor.id(), 36); buf.writeUtf(actor.name(), 64); buf.writeUtf(actor.playerName(), 16); buf.writeUtf(actor.appearance(), 16);
				buf.writeBoolean(actor.dead()); buf.writeBoolean(actor.present());
				buf.writeUtf(actor.speechStatus().substring(0, Math.min(512, actor.speechStatus().length())), 512);
				buf.writeUtf(actor.voiceProfile().profileId(), 128); buf.writeUtf(actor.voiceProfile().tone(), 32);
				buf.writeDouble(actor.voiceProfile().speed()); buf.writeVarInt(actor.voiceProfile().radius());
			}
		}
	};
	@Override public Type<DirectorSnapshotPayload> type() { return TYPE; }
}
