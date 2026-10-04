package dev.agaminggod.arenaagents.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.agaminggod.arenaagents.server.bridge.BridgeProtocolException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Receiver boundary regression; registered separately from the shared bridge suite. */
public final class AgentGoalVerification {
    private AgentGoalVerification() {}

    public static void main(String[] args) throws Exception {
        System.out.println("AgentGoalVerification passed " + verify() + " assertions");
    }

    public static int verify() throws Exception {
        Method summary = Class.forName("dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge")
                .getDeclaredMethod("goalSpecSummary", JsonObject.class);
        summary.setAccessible(true);
        int assertions = 0;
        Method planner = Class.forName("dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge")
                .getDeclaredMethod("plannerGoal", AgentGoal.class);
        planner.setAccessible(true);
        AgentGoal original = AgentGoal.create("Get a diamond pickaxe", 1000);
        require(planner.invoke(null, original).equals(original.prompt()), "Unsteered goal unchanged"); assertions++;
        AgentGoal steered = original.steer("Leave the village intact", 1001).steer("Avoid lava", 1002);
        AgentGoal restored = new AgentGoalCodec().decode(new AgentGoalCodec().encode(steered), steered.status());
        String prompt = (String) planner.invoke(null, restored);
        require(prompt.contains(original.prompt()) && prompt.contains("Leave the village intact") && prompt.contains("Avoid lava"), "Fresh projection retains every persisted constraint"); assertions++;
        require(prompt.indexOf("Leave the village intact") < prompt.indexOf("Avoid lava"), "Steering order preserves supersession meaning"); assertions++;
        require(restored.spec().equals(original.spec()), "Immutable goal specification unchanged"); assertions++;
        List<String> guidance = new ArrayList<>();
        for (int i = 0; i < AgentConstants.MAX_STEERING_INSTRUCTIONS; i++) guidance.add(("constraint-" + i + " ").repeat(500).substring(0, AgentConstants.MAX_PROMPT_LENGTH));
        AgentGoal largest = new AgentGoal(UUID.randomUUID(), "P".repeat(AgentConstants.MAX_PROMPT_LENGTH), guidance, 1000, 1001);
        String largestPrompt = (String) planner.invoke(null, largest);
        require(largestPrompt.startsWith(largest.prompt()), "Maximum original prompt is not truncated"); assertions++;
        for (String instruction : guidance) {
            require(largestPrompt.contains(instruction), "Maximum persisted history is not truncated"); assertions++;
        }
        require(largestPrompt.length() <= AgentGoal.MAX_PLANNER_PROMPT_LENGTH, "Projection is bounded by persisted schema"); assertions++;
        for (String value : new String[]{"x".repeat(256), "x".repeat(257), "x".repeat(300), "x".repeat(512), "\uD83D\uDE00".repeat(256), "  Valid summary  "}) {
            JsonObject payload = new JsonObject();
            payload.addProperty("summary", value);
            require(summary.invoke(null, payload).equals(value.strip()), "Legal summary must survive unchanged except surrounding space"); assertions++;
        }
        for (JsonElement value : new JsonElement[]{new JsonPrimitive("x".repeat(513)), new JsonPrimitive("\uD83D\uDE00".repeat(257)), new JsonPrimitive(" \t\n"), new JsonPrimitive(42), JsonNull.INSTANCE, new JsonObject(), null}) {
            JsonObject payload = new JsonObject();
            if (value != null) payload.add("summary", value);
            try {
                summary.invoke(null, payload);
                throw new AssertionError("Malformed summary accepted");
            } catch (InvocationTargetException error) {
                require(error.getCause() instanceof BridgeProtocolException protocol
                        && protocol.code().equals("INVALID_GOAL_SPEC_SUMMARY"), "Malformed summary has stable correlated rejection code"); assertions++;
            }
        }
        // The bridge must publish the revised DEAD snapshot, so the next respawn
        // uses the updated revision without changing physical state prematurely.
        Method registered = Class.forName("dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge")
                .getDeclaredMethod("registeredPayload", AgentRecord.class);
        registered.setAccessible(true);
        AgentRecord initial = AgentRecord.create(AgentId.parse("00000000-0000-4000-8000-000000000013"),
                new AgentProfile("codex", "gpt-6-astra", "high", Optional.empty(), 0), 1000);
        AgentRecord active = AgentLifecycleReducer.start(initial, "Find diamonds", 1001).after();
        AgentDeathSnapshot death = new AgentDeathSnapshot("fall", "minecraft:overworld", 1, 2, 3,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "survival", 1002);
        AgentRecord dead = AgentLifecycleReducer.die(active, death, 1002).after();
        for (AgentRecord revised : new AgentRecord[]{AgentLifecycleReducer.stop(dead, 1003).after(), AgentLifecycleReducer.steer(dead, "Use safe tunnel", 1003).after()}) {
            JsonObject wire = (JsonObject) registered.invoke(null, revised);
            require(wire.get("state").getAsString().equals("DEAD"), "Registration preserves physical death"); assertions++;
            require(wire.get("goalRevision").getAsLong() == revised.goalRevision(), "Registration publishes revised intent fence"); assertions++;
            require(wire.getAsJsonObject("death").get("diedAtEpochMs").getAsLong() == 1002, "Registration keeps death facts"); assertions++;
        }
        return assertions;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
