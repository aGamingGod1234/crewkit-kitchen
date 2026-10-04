package dev.agaminggod.arenaagents.server.goal;

import dev.agaminggod.arenaagents.agent.*;
import dev.agaminggod.arenaagents.agent.goal.*;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.runtime.GoalCompletionVerifier;
import java.util.*;
import net.minecraft.core.RegistryAccess;

/** Exercises the real manager validation boundary without a server or provider. */
public final class GoalMixedConstraintVerification {
 private static final String PICK = "minecraft:diamond_pickaxe", NETHER = "minecraft:the_nether";
 private static final GoalPredicate ITEM = new GoalPredicate.InventoryContains(PICK, 1);
 private static final GoalPredicate GATE = new GoalPredicate.OperatorConfirmed();
 private static int checks;
 private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
 private static GoalPredicate all(GoalPredicate... values) { return new GoalPredicate.AllOf(List.of(values)); }
 private static GoalPredicate pos(String dim, double x, double radius, int stability) { return new GoalPredicate.PositionWithin(dim, x, 64, 100, radius, stability); }
 private static void reject(Runnable action, String message) { try { action.run(); } catch (AgentDomainException expected) { checks++; return; } throw new AssertionError(message); }
 private static PendingGoalDraft draft(String request, String dimension, DraftIntent intent) {
  var compiler = new GoalCompiler();
  return new PendingGoalDraft(UUID.randomUUID(), AgentId.parse(UUID.randomUUID().toString()), UUID.randomUUID(), request,
    dimension, compiler.candidateIdsFor(request, RegistryAccess.EMPTY), compiler.translationConstraintFor(request, RegistryAccess.EMPTY),
    Optional.empty(), intent, 100, 0, Optional.empty());
 }
 private static AgentRecord record(GoalPredicate predicate) {
  var registry = new AgentRegistry(16, 8, () -> {}, ignored -> {});
  var idle = registry.create("codex", "gpt-6-astra", "high", "priority", Optional.empty(), AgentGameMode.SURVIVAL, 1000);
  registry.start(idle.agentId(), GoalSpec.create("Mixed factual goal", predicate, 100), 1001);
  return registry.require(idle.agentId());
 }
 private static CodexAgentManager manager() {
  try {
   var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
   return (CodexAgentManager)((sun.misc.Unsafe)f.get(null)).allocateInstance(CodexAgentManager.class);
  } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
 }
 private static final class Facts implements GoalCompletionVerifier.FactSource {
  double x; String dimension = GoalPredicate.DEFAULT_DIMENSION;
  public int inventoryCount(String id) { return PICK.equals(id) ? 1 : 0; }
  public GoalCompletionVerifier.Position position() { return new GoalCompletionVerifier.Position(x,64,100); }
  public String dimensionId() { return dimension; }
  public GoalCompletionVerifier.BlockFact blockAt(int x,int y,int z) { return GoalCompletionVerifier.BlockFact.unavailable(); }
  public boolean advancementGranted(String id) { return false; }
  public boolean alive() { return true; }
 }
 public static int verify() {
  int before = checks;
  var manager = manager();
  var compiler = new GoalCompiler(); var codec = new PendingGoalDraftCodec();
  for (var intent : List.of(DraftIntent.TRANSLATE_START, DraftIntent.TRANSLATE_QUEUE, DraftIntent.TRANSLATE_REPLACE)) {
   for (String dim : List.of(GoalPredicate.DEFAULT_DIMENSION, NETHER)) {
    for (boolean spatial : List.of(true, false)) {
     String request = "Get a diamond pickaxe and " + (spatial ? "go to 100 64 100" : "survive for 200 ticks");
     var draft = draft(request, dim, intent);
     var obligation = spatial ? pos(dim,100,1,20) : new GoalPredicate.SurviveDuration(200);
     var faithful = all(ITEM, obligation);
     reject(() -> manager.validateGoalDraftTranslation(draft, all(ITEM,GATE)), "gate must not replace the factual obligation");
     manager.validateGoalDraftTranslation(draft, faithful); checks++;
     check(!compiler.translationRequiresOperatorConfirmation(request, RegistryAccess.EMPTY), "factual translation is automatic");
     var normalized = manager.normalizeGoalDraftTranslation(draft, all(ITEM,obligation,GATE));
     check(normalized.equals(faithful), "normalization retains factual obligation and removes unnecessary gate");
     reject(() -> manager.validateGoalDraftTranslation(draft, new GoalPredicate.AnyOf(List.of(faithful,all(ITEM,GATE)))), "alternative cannot bypass fact");
     manager.validateGoalDraftTranslation(draft, new GoalPredicate.AnyOf(List.of(faithful,all(ITEM,obligation,GATE)))); checks++;
     var weaker = spatial ? pos(dim,100,1,19) : new GoalPredicate.SurviveDuration(199);
     reject(() -> manager.validateGoalDraftTranslation(draft, all(ITEM,weaker,GATE)), "weakened duration/stability rejected");
     if (spatial) {
      reject(() -> manager.validateGoalDraftTranslation(draft, all(ITEM,pos(dim,99,1,20),GATE)), "wrong destination rejected");
      reject(() -> manager.validateGoalDraftTranslation(draft, all(ITEM,pos(dim,100,2,20),GATE)), "larger radius rejected");
      reject(() -> manager.validateGoalDraftTranslation(draft, all(ITEM,pos(dim.equals(NETHER)?GoalPredicate.DEFAULT_DIMENSION:NETHER,100,1,20),GATE)), "wrong dimension rejected");
     }
     var restored = codec.decode(codec.encode(draft));
     check(restored.equals(draft), "draft round trip preserves constraints");
     manager.validateGoalDraftTranslation(restored, faithful); checks++;
     reject(() -> manager.validateGoalDraftTranslation(restored, all(ITEM,GATE)), "restored obligation enforced");
     var legacy = com.google.gson.JsonParser.parseString(codec.encode(draft)).getAsJsonObject();
     legacy.getAsJsonObject("translation_constraint").remove("factual_predicates");
     var oldDraft = codec.decode(legacy.toString());
     manager.validateGoalDraftTranslation(oldDraft, faithful); checks++;
     reject(() -> manager.validateGoalDraftTranslation(oldDraft, all(ITEM,GATE)), "legacy draft reconstructs recognized requirements");
     legacy.remove("translation_constraint");
     var olderDraft = codec.decode(legacy.toString());
     manager.validateGoalDraftTranslation(olderDraft, faithful); checks++;
     reject(() -> manager.validateGoalDraftTranslation(olderDraft, all(ITEM,GATE)), "pre-constraint legacy draft reconstructs requirements");
     var facts = new Facts(); facts.dimension = dim;
     var verifier = new GoalCompletionVerifier(); var record = record(faithful);
     check(!verifier.verify(record,facts,null,100,true).verified(), "held pickaxe and confirmation cannot complete unmet facts");
     facts.x = 100;
     boolean complete = false;
     for (long tick=101; tick<=301; tick++) complete = verifier.verify(record,facts,null,tick,false).verified();
     check(complete, "fulfilled factual goal completes automatically");
    }
   }
  }
  for (String request : List.of("Get dirt and make a diamond pickaxe", "Get dirt and make a diamond pickaxe and a diamond shovel")) {
   var draft = draft(request,GoalPredicate.DEFAULT_DIMENSION,DraftIntent.TRANSLATE_START);
   var factual = all(ITEM,new GoalPredicate.InventoryContains("minecraft:dirt",1),new GoalPredicate.InventoryContains("minecraft:diamond_shovel",1));
   reject(() -> manager.validateGoalDraftTranslation(draft,factual), "later/inherited creation keeps gate");
   manager.validateGoalDraftTranslation(draft,all(factual,GATE)); checks++;
  }
  for (String request : List.of("Go to 100 64 100 and get a diamond pickaxe", "Get a diamond pickaxe and get to 100 64 100",
    "Get a diamond pickaxe and go to 100 64 100 and stop there")) {
   var constraint = compiler.translationConstraintFor(request, RegistryAccess.EMPTY);
   constraint.validate(all(ITEM,pos(GoalPredicate.DEFAULT_DIMENSION,100,1,20))); checks++;
   reject(() -> constraint.validate(all(ITEM,GATE)), "position wording and order preserve destination");
  }
  for (String duration : List.of("200 ticks", "10 seconds", "one minute")) {
   String request = "Get a diamond pickaxe and survive for " + duration;
   long ticks = duration.equals("one minute") ? 1200 : 200;
   var constraint = compiler.translationConstraintFor(request, RegistryAccess.EMPTY);
   constraint.validate(all(ITEM,new GoalPredicate.SurviveDuration(ticks))); checks++;
   reject(() -> constraint.validate(all(ITEM,new GoalPredicate.SurviveDuration(ticks-1),GATE)), "duration units preserve full time");
  }
  String combined = "Get a diamond pickaxe and go to 100 64 100 and survive for 200 ticks";
  var constraint = compiler.translationConstraintFor(combined, RegistryAccess.EMPTY);
  constraint.validate(all(ITEM,pos(GoalPredicate.DEFAULT_DIMENSION,100,1,20),new GoalPredicate.SurviveDuration(200))); checks++;
  reject(() -> constraint.validate(all(ITEM,new GoalPredicate.SurviveDuration(200),GATE)), "multiple factual requirements retained");
  var unresolved = compiler.supportedTranslationConstraintFor("Get a zzzunknownitem and go to 100 64 100",RegistryAccess.EMPTY);
  reject(() -> unresolved.validate(GATE), "unresolved item cannot discard recognized destination");
  String spatialTemporal = "Go to 100 64 100 and survive for 200 ticks";
  check(!compiler.translationRequiresOperatorConfirmation(spatialTemporal,RegistryAccess.EMPTY), "position plus survival needs no human gate");
  var factualDraft = draft(spatialTemporal,GoalPredicate.DEFAULT_DIMENSION,DraftIntent.TRANSLATE_START);
  manager.validateGoalDraftTranslation(factualDraft,all(pos(GoalPredicate.DEFAULT_DIMENSION,100,1,20),new GoalPredicate.SurviveDuration(200))); checks++;
  var single = compiler.translationConstraintFor("Get to 100 64 100",RegistryAccess.EMPTY);
  single.validate(pos(GoalPredicate.DEFAULT_DIMENSION,100,1,20)); checks++;
  return checks-before;
 }
 public static void main(String[] args) throws Exception {
  var out=System.out;
  try { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); out.println("PASS mixed constraints " + verify()); }
  catch(Throwable failure) { failure.printStackTrace(out); throw failure; }
 }
}
