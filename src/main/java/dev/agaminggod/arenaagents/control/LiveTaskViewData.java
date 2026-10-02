package dev.agaminggod.arenaagents.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Validate display data before it crosses into the desktop window thread. */
public final class LiveTaskViewData {
 private LiveTaskViewData() { }
 public static JsonObject parse(String text) {
  if (text.getBytes(StandardCharsets.UTF_8).length > LiveTaskViewPayload.MAX_BYTES) throw new IllegalArgumentException("View too large");
  JsonObject root = JsonParser.parseString(text).getAsJsonObject();
  if (root.size() == 0) return root;
  bounded(root,"goal",512); number(root,"goalRevision"); number(root,"revision"); number(root,"generatedAt");
  for(String field:Set.of("active","verified")) if(!root.get(field).isJsonPrimitive() || !root.get(field).getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid "+field);
  if (!root.get("plan").isJsonNull()) {
   var steps=root.getAsJsonObject("plan").getAsJsonArray("steps");
   if(steps.size()>48) throw new IllegalArgumentException("Too many plan steps");
   Set<String> ids=new HashSet<>();
   for(var value:steps) {
    var s=value.getAsJsonObject(); bounded(s,"id",48); bounded(s,"label",100); bounded(s,"detail",200);
    if(!ids.add(s.get("id").getAsString()) || !s.get("id").getAsString().matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("Invalid step ID");
    if(!Set.of("inventory","world","milestone","manual").contains(s.get("kind").getAsString()) || !Set.of("pending","active","complete","lost").contains(s.get("status").getAsString())) throw new IllegalArgumentException("Invalid step state");
    if(s.getAsJsonArray("dependsOn").size()>12) throw new IllegalArgumentException("Too many dependencies");
   }
   for(var value:steps) for(var dependency:value.getAsJsonObject().getAsJsonArray("dependsOn")) if(!ids.contains(dependency.getAsString())) throw new IllegalArgumentException("Unknown dependency");
   Set<String> resolved=new HashSet<>();
   for(int pass=0;pass<steps.size();pass++) for(var value:steps){var s=value.getAsJsonObject();boolean ready=true;for(var dependency:s.getAsJsonArray("dependsOn"))if(!resolved.contains(dependency.getAsString()))ready=false;if(ready)resolved.add(s.get("id").getAsString());}
   if(resolved.size()!=steps.size())throw new IllegalArgumentException("Cyclic dependencies");
  }
  var events=root.getAsJsonArray("events"); if(events.size()>256) throw new IllegalArgumentException("Too many events");
  for(var value:events) { var e=value.getAsJsonObject(); bounded(e,"stage",40); bounded(e,"message",2048); number(e,"sequence"); number(e,"at"); }
  var seen=root.getAsJsonObject("lastObserved"); if(seen.size()>48)throw new IllegalArgumentException("Too many observation dates");for(var e:seen.entrySet())if(!e.getValue().isJsonNull())number(seen,e.getKey());
  if(!root.get("usage").isJsonNull()){var usage=root.getAsJsonObject("usage");for(var e:usage.entrySet())number(usage,e.getKey());}
  if(!root.get("allowance").isJsonNull()){var allowance=root.getAsJsonObject("allowance");for(var e:allowance.entrySet()){var window=e.getValue().getAsJsonObject();double used=window.get("usedPercent").getAsDouble();if(!Double.isFinite(used)||used<0)throw new IllegalArgumentException("Invalid allowance");if(!window.get("windowDurationMins").isJsonNull())number(window,"windowDurationMins");}}
  return root;
 }
 private static void bounded(JsonObject object,String key,int max) {
  var v=object.get(key); if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || v.getAsString().length()>max) throw new IllegalArgumentException("Invalid "+key);
 }
 private static void number(JsonObject object,String key) {
  var n=object.get(key).getAsBigDecimal(); if(n.signum()<0 || n.longValueExact()>9_007_199_254_740_991L) throw new IllegalArgumentException("Invalid "+key);
 }
}
