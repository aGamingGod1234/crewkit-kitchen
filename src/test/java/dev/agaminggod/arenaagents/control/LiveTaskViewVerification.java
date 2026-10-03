package dev.agaminggod.arenaagents.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;

public final class LiveTaskViewVerification {
 private LiveTaskViewVerification() { }
 public static int verify() {
  String fixture="""
   {"goalRevision":1,"goal":"Get a pickaxe","active":true,"verified":false,"revision":2,"generatedAt":1000,
    "plan":{"steps":[{"id":"tool","label":"Carry an iron pickaxe","kind":"inventory","status":"lost","dependsOn":[],"detail":"Recover or replace it","evidence":null}]},
    "lastObserved":{},"events":[{"stage":"tool","message":"minecraft.observe","sequence":1,"at":1000}],"usage":null,"allowance":null}
   """;
  var root=LiveTaskViewData.parse(fixture);if(!root.get("active").getAsBoolean())throw new AssertionError("Active view lost");
  new LiveTaskViewPayload.Snapshot(UUID.randomUUID(),false,"Offline",fixture);
  int assertions=2;
  for(String field:new String[]{"goalRevision","generatedAt"}){var bad=root.deepCopy();bad.addProperty(field,-1);reject(bad.toString());assertions++;}
  var cycle=root.deepCopy();var step=cycle.getAsJsonObject("plan").getAsJsonArray("steps").get(0).getAsJsonObject();var deps=new JsonArray();deps.add("tool");step.add("dependsOn",deps);reject(cycle.toString());assertions++;
  var unknown=root.deepCopy();var udeps=new JsonArray();udeps.add("missing");unknown.getAsJsonObject("plan").getAsJsonArray("steps").get(0).getAsJsonObject().add("dependsOn",udeps);reject(unknown.toString());assertions++;
  var usage=root.deepCopy();var tokens=new JsonObject();tokens.addProperty("totalTokens",-1);usage.add("usage",tokens);reject(usage.toString());assertions++;
  reject("x".repeat(LiveTaskViewPayload.MAX_BYTES+1));assertions++;
  try {new LiveTaskViewPayload.Snapshot(UUID.randomUUID(),true,"Online","界".repeat(10000));throw new AssertionError("Oversized UTF-8 accepted");}catch(IllegalArgumentException expected){assertions++;}
  if(LiveTaskViewData.parse("{}").size()!=0)throw new AssertionError("Loading state rejected");assertions++;
  return assertions;
 }
 private static void reject(String json){try{LiveTaskViewData.parse(json);throw new AssertionError("Malformed task view accepted");}catch(RuntimeException expected){}}
}
