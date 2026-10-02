package dev.agaminggod.arenaagents.control;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Selected-agent view data travels separately from the roster snapshot. */
public final class LiveTaskViewPayload {
 public static final int MAX_BYTES = 28_672;
 private LiveTaskViewPayload() { }
 public record Request(UUID agentId) implements CustomPacketPayload {
  public Request { Objects.requireNonNull(agentId); }
  public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "task_view_request"));
  public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
   public Request decode(RegistryFriendlyByteBuf b) { return new Request(b.readUUID()); }
   public void encode(RegistryFriendlyByteBuf b, Request v) { b.writeUUID(v.agentId()); }
  };
  @Override public Type<Request> type() { return TYPE; }
 }
 public record Snapshot(UUID agentId, boolean online, String status, String json) implements CustomPacketPayload {
  public Snapshot {
   Objects.requireNonNull(agentId); Objects.requireNonNull(status); Objects.requireNonNull(json);
   if (status.length() > 160 || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Task view exceeds wire limits");
  }
  public static final Type<Snapshot> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "task_view"));
  public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = new StreamCodec<>() {
   public Snapshot decode(RegistryFriendlyByteBuf b) { return new Snapshot(b.readUUID(), b.readBoolean(), b.readUtf(160), b.readUtf(MAX_BYTES)); }
   public void encode(RegistryFriendlyByteBuf b, Snapshot v) { b.writeUUID(v.agentId()); b.writeBoolean(v.online()); b.writeUtf(v.status(),160); b.writeUtf(v.json(),MAX_BYTES); }
  };
  @Override public Type<Snapshot> type() { return TYPE; }
 }
}
