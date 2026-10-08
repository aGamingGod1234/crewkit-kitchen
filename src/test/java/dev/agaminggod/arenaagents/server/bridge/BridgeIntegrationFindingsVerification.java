package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.*;
import dev.agaminggod.arenaagents.agent.*;
import dev.agaminggod.arenaagents.agent.goal.GoalPredicate;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioPreflight;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.goal.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Headless integration checks against complete production classes and an inert session. */
public final class BridgeIntegrationFindingsVerification {
    private static int assertions;
    private static final List<BridgeEnvelope> EXPORTED = new ArrayList<>();
    private BridgeIntegrationFindingsVerification() {}

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        if (args.length > 1) {
            assertions = 0;
            switch (args[1]) {
                case "dead" -> deadControls();
                case "publication" -> publication();
                default -> throw new IllegalArgumentException("Unknown focused verification " + args[1]);
            }
            System.out.println("BridgeIntegrationFindingsVerification " + args[1] + " assertions=" + assertions);
        } else System.out.println("BridgeIntegrationFindingsVerification assertions=" + verify());
        if (args.length > 0) {
            JsonArray wire = new JsonArray();
            BridgeEnvelopeCodec codec = new BridgeEnvelopeCodec();
            for (BridgeEnvelope envelope : EXPORTED) wire.add(JsonParser.parseString(codec.encode(envelope)));
            Files.writeString(Path.of(args[0]), wire.toString());
        }
    }

    public static int verify() throws Exception {
        assertions = 0;
        EXPORTED.clear();
        preflight();
        summaries();
        deadControls();
        publication();
        return assertions;
    }

    private static void preflight() {
        var closedCreate = health("codex", "gpt-6-astra", "create_agent", "closed");
        var closedDecide = health("codex", "gpt-6-astra", "decide", "closed");
        var closedNative = health("codex", "gpt-6-astra", "native_turn", "closed");
        checkPreflight(List.of(closedCreate, closedNative), "READY", "native closed without decide");
        for (String state : List.of("open", "half_open")) {
            checkPreflight(List.of(closedCreate, closedDecide, health("codex", "gpt-6-astra", "native_turn", state)),
                    "PROVIDER_CIRCUIT_" + state.toUpperCase(java.util.Locale.ROOT), "native " + state + " overrides stale decide");
        }
        checkPreflight(List.of(closedCreate, closedDecide), "READY", "legacy decide");
        checkPreflight(List.of(closedNative), "PROVIDER_HEALTH_UNKNOWN", "missing create");
        checkPreflight(List.of(closedCreate), "PROVIDER_HEALTH_UNKNOWN", "missing turn");
        checkPreflight(List.of(closedCreate, health("gemini", "gpt-6-astra", "native_turn", "closed")),
                "PROVIDER_HEALTH_UNKNOWN", "wrong provider cannot satisfy native");
        checkPreflight(List.of(closedCreate, health("codex", "other", "native_turn", "closed")),
                "PROVIDER_HEALTH_UNKNOWN", "wrong model cannot satisfy native");
        checkPreflight(List.of(closedCreate, closedDecide, health("codex", "other", "native_turn", "open")),
                "READY", "unrelated native cannot override legacy");
        System.out.println("PASS native and legacy preflight controls");
    }

    private static CoordinatorStatusSnapshot.CircuitHealth health(String provider, String model, String op, String state) {
        return new CoordinatorStatusSnapshot.CircuitHealth(provider, model, op, 0, 0, 0, 0, state);
    }

    private static void checkPreflight(List<CoordinatorStatusSnapshot.CircuitHealth> circuits, String code, String label) {
        var required = new ScenarioPreflight.RequiredProfile("agent", "codex", "gpt-6-astra", "high");
        var status = new CoordinatorStatusSnapshot(true,
                List.of(new CoordinatorStatusSnapshot.SupportedProfile("agent", "codex", "gpt-6-astra", "high", "priority")),
                1, 1, 1, new CoordinatorStatusSnapshot.SchedulerStatus(0, 0, 4, 12, false), circuits, 100_000L);
        var input = new ScenarioPreflight.Input(Optional.of(status), List.of(required), "minecraft:overworld",
                "minecraft:overworld", "hash", "hash", true, 99_000L, 100_000L);
        eq(code, ScenarioPreflight.assess(input).code(), label);
    }

    private static void summaries() throws Exception {
        try (ItemBinding components = new ItemBinding(); Fixture f = fixture()) {
            AgentRecord idle = f.manager.registry().create("gpt-6-astra", "high", Optional.empty(), 1_000L);
            for (int length : new int[]{256, 257, 300, 512}) {
                UUID request = stage(f.manager, idle);
                JsonObject payload = proposal(request);
                payload.addProperty("summary", "x".repeat(length));
                invoke(f.bridge, "acceptGoalSpecProposal", new Class<?>[]{BridgeEnvelope.class},
                        new BridgeEnvelope(2, "fixture", idle.agentId().toString(), "goal_spec_proposal", "m", payload));
                BridgeEnvelope result = f.take("goal_spec_result");
                eq(request.toString(), result.payload().get("requestId").getAsString(), "accepted correlation " + length);
                eq("accepted", result.payload().get("status").getAsString(), "accepted summary " + length);
                eq("PROPOSAL_STAGED", result.payload().get("reasonCode").getAsString(), "summary stages draft " + length);
                eq(true, f.manager.goalDraft(request).orElseThrow().proposedPredicate().isPresent(), "real manager staged " + length);
                EXPORTED.add(result);
            }
            for (JsonElement summary : List.of(new JsonPrimitive("   "), new JsonPrimitive(42), new JsonPrimitive("x".repeat(513)))) {
                UUID request = stage(f.manager, idle);
                JsonObject payload = proposal(request);
                payload.add("summary", summary);
                invoke(f.bridge, "acceptGoalSpecProposal", new Class<?>[]{BridgeEnvelope.class},
                        new BridgeEnvelope(2, "fixture", idle.agentId().toString(), "goal_spec_proposal", "m", payload));
                BridgeEnvelope result = f.take("goal_spec_result");
                eq(request.toString(), result.payload().get("requestId").getAsString(), "rejected correlation");
                eq("rejected", result.payload().get("status").getAsString(), "malformed summary rejected");
                eq("INVALID_GOAL_SPEC_SUMMARY", result.payload().get("reasonCode").getAsString(), "summary reason");
                eq(false, f.manager.goalDraft(request).orElseThrow().proposedPredicate().isPresent(), "rejection leaves draft unchanged");
                EXPORTED.add(result);
            }
            for (boolean extra : new boolean[]{false, true}) {
                UUID request = stage(f.manager, idle);
                JsonObject payload = proposal(request);
                if (extra) payload.addProperty("unexpected", true); else payload.remove("summary");
                invoke(f.bridge, "acceptGoalSpecProposal", new Class<?>[]{BridgeEnvelope.class},
                        new BridgeEnvelope(2, "fixture", idle.agentId().toString(), "goal_spec_proposal", "m", payload));
                BridgeEnvelope result = f.take("goal_spec_result");
                eq(request.toString(), result.payload().get("requestId").getAsString(), "key rejection correlation");
                eq("INVALID_FIELD", result.payload().get("reasonCode").getAsString(), "key rejection reason");
            }
        }
        System.out.println("PASS actual proposal handler and correlated results");
    }

    private static UUID stage(CodexAgentManager manager, AgentRecord idle) {
        UUID id = UUID.randomUUID();
        manager.stageGoalDraft(new PendingGoalDraft(id, idle.agentId(), UUID.randomUUID(),
                "Get a good pickaxe", List.of("minecraft:iron_pickaxe"), Optional.empty(),
                DraftIntent.CONFIRM_TRANSLATION, 1_001L, idle.goalRevision(), Optional.empty()));
        return id;
    }

    private static JsonObject proposal(UUID request) {
        JsonObject payload = new JsonObject();
        payload.addProperty("requestId", request.toString());
        payload.addProperty("summary", "Get iron pickaxe");
        payload.add("predicate", new GoalSpecWireCodec().encodePredicate(new GoalPredicate.InventoryContains("minecraft:iron_pickaxe", 1)));
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static void deadControls() throws Exception {
        for (boolean steer : new boolean[]{false, true}) try (Fixture f = fixture()) {
            AgentRecord idle = f.manager.registry().create("gpt-6-astra", "high", Optional.empty(), 1_000L);
            AgentId id = idle.agentId();
            f.manager.registry().start(id, "Find diamonds", 1_001L);
            var death = new AgentDeathSnapshot("fall", "minecraft:the_end", 240.5, 42, -170.5,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), "survival", 1_002L);
            var dead = f.manager.registry().die(id, death, 1_002L);
            var collector = (dev.agaminggod.arenaagents.server.perception.ServerObservationCollector) field(f.bridge, "observations");
            eq(List.of(), collector.changedActiveAgentSamples(f.manager.coordinatorVisibleRecords()), "bodyless DEAD record produces no raw sample");
            ((Set<AgentId>) field(f.bridge, "protocolKnownAgentIds")).add(id);
            EXPORTED.add(new BridgeEnvelope(2, "fixture", id.toString(), "agent_registered", "initial",
                    (JsonObject) invoke(null, MultiplexedServerBridge.class, "registeredPayload", new Class<?>[]{AgentRecord.class}, dead.after())));
            var changed = steer ? f.manager.registry().steer(id, "Use safe tunnel", 1_003L) : f.manager.registry().stop(id, 1_003L);
            f.bridge.onTransition(changed);
            BridgeEnvelope result = f.take("agent_registered");
            eq("DEAD", result.payload().get("state").getAsString(), "physical death retained on wire");
            eq(changed.after().goalRevision(), result.payload().get("goalRevision").getAsLong(), "new revision republished");
            eq(true, result.payload().has("death"), "death facts republished");
            eq(true, f.outbound.isEmpty(), "no synthetic live goal control");
            EXPORTED.add(result);
            var respawn = f.manager.registry().respawn(id, UUID.randomUUID(), 1_004L);
            f.bridge.onTransition(respawn);
            BridgeEnvelope resumed = f.take("goal_control");
            eq("respawn", resumed.payload().get("operation").getAsString(), "next control is respawn");
            eq(changed.after().goalRevision(), resumed.payload().get("goalRevision").getAsLong(), "respawn matches revised fence");
            eq(steer, resumed.payload().has("resumeGoal") && resumed.payload().get("resumeGoal").getAsBoolean(), "respawn continuation intent");
            EXPORTED.add(resumed);
        }
        System.out.println("PASS actual DEAD transition publication and next respawn fence");
    }

    private static void publication() throws Exception {
        try (Fixture f = fixture()) {
            var id = f.manager.registry().create("gpt-6-astra", "high", Optional.empty(), 1_000L).agentId();
            var publication = f.bridge.observationPublicationForVerification();
            @SuppressWarnings("unchecked") Set<AgentId> pending = (Set<AgentId>) field(f.manager, "pendingAgentRegistrations");
            pending.add(id);
            invoke(f.bridge, "queueObservation", new Class<?>[]{AgentId.class}, id);
            invoke(f.bridge, "queueUrgentObservation", new Class<?>[]{AgentId.class}, id);
            eq(0, publication.pendingCount(), "unregistered agent cannot queue either publication lane");
            eq(List.of(), f.manager.coordinatorVisibleRecords(), "pending registration excluded from roster");
            pending.remove(id);
            JsonObject observation = JsonParser.parseString("""
                    {"ready":true,"status":"ACTING","observedAtEpochMs":1000,"player":{"health":20,"foodLevel":20,
                    "saturation":5,"onFire":false,"inWater":true,"air":299,"suffocating":false,
                    "onGround":true,"fallDistance":0},"currentAction":{"active":true},
                    "inventory":{"selectedItem":"minecraft:air","items":[]},"entities":[],"blocks":[],
                    "world":{"dimension":"minecraft:overworld"}}
                    """).getAsJsonObject();
            var deliveries = new ArrayList<JsonObject>();
            MultiplexedServerBridge.ObservationPublication.Writer writer = (agent, payload) -> { deliveries.add(payload.deepCopy()); return true; };
            eq(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
                    publication.publish(id, f.session, observation, writer, false), "fresh baseline");
            int interval = (Integer) field(publication, "heartbeatMinimumIntervalTicks");
            int suppressed = 0;
            int due = 0;
            for (int tick = 1; tick <= interval * 2; tick++) {
                eq(false, rawForce(299, 298, 20, 20, false), "actual raw classifier keeps safe air quiet");
                observation.getAsJsonObject("player").addProperty("air", 299 - (tick % 100));
                observation.addProperty("observedAtEpochMs", 1_000L + tick);
                // Inside the heartbeat window the coming heartbeat answers a routine request, so nothing is queued.
                invoke(f.bridge, "queueObservation", new Class<?>[]{AgentId.class}, id);
                publication.scheduleIdleHeartbeat(List.of(id));
                final List<MultiplexedServerBridge.ObservationPublication.Result> results = new ArrayList<>();
                publication.drain(agent -> results.add(publication.publish(agent, f.session, observation, writer, publication.takeHeartbeat(agent))));
                eq(tick % interval == 0 ? 1 : 0, results.size(), "only the due heartbeat is published at tick " + tick);
                for (var result : results) {
                    if (result == MultiplexedServerBridge.ObservationPublication.Result.SUPPRESSED) suppressed++;
                    else due++;
                }
            }
            eq(2, due, "sustained quiet air retains two due heartbeats");
            eq(0, suppressed, "quiet predeadline requests never reach the queue");
            for (String control : List.of("damage", "critical_air", "inventory", "explicit")) {
                if (!control.equals("explicit")) eq(true, rawForce(control.equals("critical_air") ? 61 : 299,
                        control.equals("critical_air") ? 60 : 298, 20, control.equals("damage") ? 19 : 20,
                        control.equals("inventory")), control + " actual raw classifier forces attention");
                if (control.equals("damage")) observation.getAsJsonObject("player").addProperty("health", 19);
                if (control.equals("critical_air")) observation.getAsJsonObject("player").addProperty("air", 60);
                publication.markAttention(id);
                invoke(f.bridge, "queueUrgentObservation", new Class<?>[]{AgentId.class}, id);
                // An ordinary sample cannot clear separately pending explicit/action attention.
                invoke(f.bridge, "queueObservation", new Class<?>[]{AgentId.class}, id);
                publication.scheduleIdleHeartbeat(List.of(id));
                publication.drain(agent -> eq(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
                        publication.publish(agent, f.session, observation, writer, publication.takeHeartbeat(agent)), control));
                eq(true, deliveries.getLast().get("attention").getAsBoolean(), control + " attention delivered");
            }
            publication.remove(id);
            eq(0, publication.retainedCount(), "roster removal clears baseline");
            f.manager.registry().remove(id);
            invoke(f.bridge, "queueObservation", new Class<?>[]{AgentId.class}, id);
            invoke(f.bridge, "queueUrgentObservation", new Class<?>[]{AgentId.class}, id);
            eq(0, publication.pendingCount(), "removed agent cannot queue either publication lane");
            Object replacement = new Object();
            MultiplexedServerBridge.onSessionAccepted(publication, replacement);
            eq(MultiplexedServerBridge.ObservationPublication.Result.STALE_SESSION,
                    publication.publish(id, f.session, observation, writer), "stale session blocked");
            eq(MultiplexedServerBridge.ObservationPublication.Result.COMMITTED,
                    publication.publish(id, replacement, observation, writer), "new session establishes own baseline");
        }
        System.out.println("PASS complete bridge normal/urgent queue and publication heartbeat controls");
    }

    private static boolean rawForce(int beforeAir, int afterAir, double beforeHealth, double afterHealth, boolean inventory) throws Exception {
        Class<?> type = Class.forName("dev.agaminggod.arenaagents.server.perception.ServerObservationCollector$RawPlayerState");
        Constructor<?> constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object before = constructor.newInstance(beforeHealth, 20, 5.0, false, true, beforeAir, false, true, 0.0, null, 0L);
        Object after = constructor.newInstance(afterHealth, 20, 5.0, false, true, afterAir, false, true, 0.0, null, 0L);
        return (boolean) invoke(after, "requiresForcedAttention", new Class<?>[]{type, boolean.class}, before, inventory);
    }

    private static Fixture fixture() throws Exception {
        // Reuse the established lifecycle-only fixture; no server/world constructor is run.
        CodexAgentManager manager = (CodexAgentManager) invoke(null, MultiplexedServerBridgeVerification.class,
                "uninitializedManager", new Class<?>[]{});
        MultiplexedServerBridge bridge = MultiplexedServerBridge.withPreparedSecret(manager, 0, "0123456789abcdef0123456789abcdef");
        Class<?> type = Class.forName(MultiplexedServerBridge.class.getName() + "$Session");
        Constructor<?> constructor = type.getDeclaredConstructor(MultiplexedServerBridge.class, Socket.class);
        constructor.setAccessible(true);
        Socket socket = new Socket() {
            @Override public void setTcpNoDelay(boolean on) {}
            @Override public void setSoTimeout(int timeout) {}
        };
        Object session = constructor.newInstance(bridge, socket);
        ((AtomicBoolean) field(session, "authenticated")).set(true);
        Field sessionField = MultiplexedServerBridge.class.getDeclaredField("session");
        sessionField.setAccessible(true);
        sessionField.set(bridge, session);
        MultiplexedServerBridge.onSessionAccepted(bridge.observationPublicationForVerification(), session);
        @SuppressWarnings("unchecked") var outbound = (BlockingQueue<BridgeEnvelope>) field(session, "outbound");
        return new Fixture(manager, bridge, session, socket, outbound);
    }

    /** The headless bootstrap has no datapack item components; restore this single fixture binding. */
    private static final class ItemBinding implements AutoCloseable {
        private final net.minecraft.core.Holder.Reference<net.minecraft.world.item.Item> holder = net.minecraft.world.item.Items.IRON_PICKAXE.builtInRegistryHolder();
        private final Object original;
        ItemBinding() throws Exception {
            original = field(holder, "components");
            var builder = net.minecraft.core.component.DataComponentMap.builder();
            if (original != null) builder.addAll((net.minecraft.core.component.DataComponentMap) original);
            holder.bindComponents(builder.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 1).build());
        }
        public void close() throws Exception {
            Field field = holder.getClass().getDeclaredField("components"); field.setAccessible(true); field.set(holder, original);
        }
    }

    private record Fixture(CodexAgentManager manager, MultiplexedServerBridge bridge, Object session,
                           Socket socket, BlockingQueue<BridgeEnvelope> outbound) implements AutoCloseable {
        BridgeEnvelope take(String type) {
            BridgeEnvelope result = outbound.poll();
            if (result == null || !type.equals(result.type())) throw new AssertionError("Expected " + type + ", got " + result);
            return result;
        }
        public void close() throws Exception { bridge.close(); socket.close(); }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return invoke(target, target.getClass(), name, types, args);
    }
    private static Object invoke(Object target, Class<?> owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = owner.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw new AssertionError("Production " + name + " failed", error.getCause()); }
    }
    private static void eq(Object expected, Object actual, String label) {
        assertions++;
        if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }
}
