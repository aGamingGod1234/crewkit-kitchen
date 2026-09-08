package dev.agaminggod.arenaagents.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Bounded pages keep large saved scripts out of the periodic cast snapshot. */
public final class DirectorEditorPayload {
	public static final int PAGE_SIZE = 8;
	public record Row(int index, String label, String action, String arguments) { }
	public record Request(UUID requestId, String kind, String name, String operation, String revision,
			int index, int offset, String action, String arguments) implements CustomPacketPayload {
		public Request {
			if (!Set.of("motion", "voice", "take").contains(kind) || !Set.of("read", "append", "replace", "remove", "undo", "delete").contains(operation))
				throw new IllegalArgumentException("Invalid editor operation");
			if (name.length() > 64 || revision.length() > 64 || action.length() > 36 || arguments.length() > 1024
					|| index < -1 || index >= 512 || offset < 0 || offset >= 512) throw new IllegalArgumentException("Invalid editor bounds");
		}
		public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "director_editor_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
			public Request decode(RegistryFriendlyByteBuf b) {
				return new Request(b.readUUID(), b.readUtf(8), b.readUtf(64), b.readUtf(8), b.readUtf(64), b.readVarInt(), b.readVarInt(), b.readUtf(36), b.readUtf(1024));
			}
			public void encode(RegistryFriendlyByteBuf b, Request v) {
				b.writeUUID(v.requestId()); b.writeUtf(v.kind(),8); b.writeUtf(v.name(),64); b.writeUtf(v.operation(),8); b.writeUtf(v.revision(),64);
				b.writeVarInt(v.index()); b.writeVarInt(v.offset()); b.writeUtf(v.action(),36); b.writeUtf(v.arguments(),1024);
			}
		};
		@Override public Type<Request> type() { return TYPE; }
	}
	public record Snapshot(UUID requestId, boolean success, String message, String kind, String name, String revision,
			List<String> names, int total, int offset, List<Row> rows) implements CustomPacketPayload {
		public Snapshot {
			names = List.copyOf(names); rows = List.copyOf(rows);
			if (names.size() > 128 || rows.size() > PAGE_SIZE || total < 0 || total > 512 || offset < 0 || offset > 511)
				throw new IllegalArgumentException("Invalid editor page");
			if (message.length() > 512) message = message.substring(0,512);
		}
		public static final Type<Snapshot> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "director_editor_snapshot"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
			public Snapshot decode(RegistryFriendlyByteBuf b) {
				UUID id=b.readUUID(); boolean success=b.readBoolean(); String message=b.readUtf(512), kind=b.readUtf(8), name=b.readUtf(64), revision=b.readUtf(64);
				int count=b.readVarInt(); if(count<0 || count>128) throw new IllegalArgumentException("Invalid script count");
				List<String> names=new ArrayList<>(); for(int i=0;i<count;i++) names.add(b.readUtf(64));
				int total=b.readVarInt(), offset=b.readVarInt(), size=b.readVarInt();
				if(size<0 || size>PAGE_SIZE) throw new IllegalArgumentException("Invalid row count");
				List<Row> rows=new ArrayList<>(); for(int i=0;i<size;i++) rows.add(new Row(b.readVarInt(),b.readUtf(256),b.readUtf(36),b.readUtf(1024)));
				return new Snapshot(id,success,message,kind,name,revision,names,total,offset,rows);
			}
			public void encode(RegistryFriendlyByteBuf b, Snapshot v) {
				b.writeUUID(v.requestId()); b.writeBoolean(v.success()); b.writeUtf(v.message(),512); b.writeUtf(v.kind(),8); b.writeUtf(v.name(),64); b.writeUtf(v.revision(),64);
				b.writeVarInt(v.names().size()); for(String name:v.names()) b.writeUtf(name,64);
				b.writeVarInt(v.total()); b.writeVarInt(v.offset()); b.writeVarInt(v.rows().size());
				for(Row row:v.rows()) { b.writeVarInt(row.index()); b.writeUtf(row.label(),256); b.writeUtf(row.action(),36); b.writeUtf(row.arguments(),1024); }
			}
		};
		@Override public Type<Snapshot> type() { return TYPE; }
	}
}
