package dev.agaminggod.arenaagents.server;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.control.LiveTaskViewData;
import dev.agaminggod.arenaagents.control.LiveTaskViewPayload;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Operator-only, on-demand views. No subscription creates provider work. */
public final class LiveTaskViewSync {
 private static final Map<MinecraftServer, State> STATES=new WeakHashMap<>();
 private static final class State {
  final Map<UUID,JsonObject> cached=new HashMap<>();
  final Map<UUID,Set<UUID>> waiting=new HashMap<>();
  final Map<UUID,Integer> requested=new HashMap<>();
 }
 private LiveTaskViewSync() { }
 public static void request(ServerPlayer player,UUID id) {
  var server=player.level().getServer();
  if(!GoalControl.mayControl(player.createCommandSourceStack())) { send(player,id,false,"Operator permission is required","{}"); return; }
  var record=CodexAgentManager.get(server).records().stream().filter(r->r.agentId().toString().equals(id.toString())).findFirst();
  if(record.isEmpty()) { send(player,id,false,"Agent is no longer available","{}"); return; }
  var state=STATES.computeIfAbsent(server,s->new State());
  boolean online=CodexAgentServerRuntime.automationAvailable(server);
  JsonObject cached=state.cached.get(id);
  if(cached!=null && cached.get("goalRevision").getAsLong()!=record.get().goalRevision()) { state.cached.remove(id); cached=null; }
  send(player,id,online,online?(cached==null?"Waiting for live task data":"Connected"):"Coordinator offline; displayed data may be stale",cached==null?"{}":cached.toString());
  if(!online) { state.waiting.remove(id); return; }
  state.waiting.computeIfAbsent(id,k->new HashSet<>()).add(player.getUUID());
  if(server.getTickCount()-state.requested.getOrDefault(id,-100)>=10) {
   state.requested.put(id,server.getTickCount()); CodexAgentServerRuntime.requestTaskView(server,AgentId.parse(id.toString()));
  }
 }
 public static void accept(MinecraftServer server,AgentId id,JsonObject payload) {
  JsonObject checked;
  try { checked=LiveTaskViewData.parse(payload.toString()); } catch(RuntimeException invalid) { return; }
  if(checked.size()==0) return;
  var record=CodexAgentManager.get(server).records().stream().filter(r->r.agentId().equals(id)).findFirst();
  if(record.isEmpty() || checked.get("goalRevision").getAsLong()!=record.get().goalRevision()) return;
  var state=STATES.computeIfAbsent(server,s->new State()); UUID uuid=UUID.fromString(id.toString());
  state.cached.put(uuid,checked);
  for(UUID playerId:state.waiting.getOrDefault(uuid,Set.of())) {
   var player=server.getPlayerList().getPlayer(playerId);
   if(player!=null && GoalControl.mayControl(player.createCommandSourceStack())) send(player,uuid,true,"Connected",checked.toString());
  }
  state.waiting.remove(uuid);
  Set<UUID> present=new HashSet<>(); for(var agent:CodexAgentManager.get(server).records()) present.add(UUID.fromString(agent.agentId().toString()));
  state.cached.keySet().retainAll(present); state.requested.keySet().retainAll(present); state.waiting.keySet().retainAll(present);
 }
 private static void send(ServerPlayer player,UUID id,boolean online,String status,String json) {
  if(ServerPlayNetworking.canSend(player,LiveTaskViewPayload.Snapshot.TYPE)) ServerPlayNetworking.send(player,new LiveTaskViewPayload.Snapshot(id,online,status,json));
 }
}
