package dev.agaminggod.arenaagents.control;

import java.util.List;
import java.util.ArrayList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Director state has its own roster and does not enter the agent-control schema. */
public record DirectorSnapshotPayload(boolean enabled, boolean canControl, List<Actor> actors) implements CustomPacketPayload {
	public record Actor(String id, String name, String playerName, String appearance, boolean dead, boolean present) { }
	public DirectorSnapshotPayload { actors = List.copyOf(actors); if (actors.size() > 32) throw new IllegalArgumentException("Cast too large"); }
	public static final Type<DirectorSnapshotPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "director_snapshot"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DirectorSnapshotPayload> CODEC = new StreamCodec<>() {
		public DirectorSnapshotPayload decode(RegistryFriendlyByteBuf buf) {
			boolean enabled = buf.readBoolean(), canControl = buf.readBoolean();
			int size = buf.readVarInt();
			if (size < 0 || size > 32) throw new IllegalArgumentException("Invalid cast size");
			List<Actor> actors = new ArrayList<>();
			for (int i = 0; i < size; i++) actors.add(new Actor(buf.readUtf(36), buf.readUtf(64), buf.readUtf(16), buf.readUtf(16), buf.readBoolean(), buf.readBoolean()));
			return new DirectorSnapshotPayload(enabled, canControl, actors);
		}
		public void encode(RegistryFriendlyByteBuf buf, DirectorSnapshotPayload value) {
			buf.writeBoolean(value.enabled()); buf.writeBoolean(value.canControl()); buf.writeVarInt(value.actors().size());
			for (Actor actor : value.actors()) {
				buf.writeUtf(actor.id(), 36); buf.writeUtf(actor.name(), 64); buf.writeUtf(actor.playerName(), 16); buf.writeUtf(actor.appearance(), 16);
				buf.writeBoolean(actor.dead()); buf.writeBoolean(actor.present());
			}
		}
	};
	@Override public Type<DirectorSnapshotPayload> type() { return TYPE; }
}
