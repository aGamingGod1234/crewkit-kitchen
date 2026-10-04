package dev.agaminggod.arenaagents.agent;

import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.agent.goal.GoalStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Regression for controls issued after persisted death, before physical respawn. */
public final class AgentLifecycleVerification {
    private static final long TIME = 1_800_000_000_000L;
    private static final AgentId ID = AgentId.parse("00000000-0000-4000-8000-000000000013");
    private static final UUID BODY = UUID.fromString("00000000-0000-4000-8000-000000000014");
    private static final AgentDeathSnapshot DEATH = new AgentDeathSnapshot(
            "fall", "minecraft:the_end", 240.5, 42, -170.5,
            Optional.of("minecraft:the_nether"), Optional.of(830.5), Optional.of(72.0), Optional.of(-400.5),
            Optional.of(37.5f), Optional.of(-12.25f), Optional.of(true), "spectator", TIME + 3);

    private AgentLifecycleVerification() {}

    public static void main(String[] args) {
        System.out.println("AgentLifecycleVerification passed " + verify() + " assertions");
    }

    public static int verify() {
        int assertions = 0;
        AgentRecord initial = AgentRecord.create(ID,
                new AgentProfile("codex", "gpt-6-astra", "high", Optional.empty(), 0), TIME);
        AgentRecord live = AgentLifecycleReducer.start(initial, "Find diamonds", TIME + 1).after();
        live = AgentLifecycleReducer.queue(live, "Return to base", 10, TIME + 2).after();
        AgentRecord dead = AgentLifecycleReducer.die(live, DEATH, TIME + 4).after();
        for (boolean steer : new boolean[]{false, true}) {
            AgentRegistry registry = reload(dead);
            AgentTransition change = steer ? registry.steer(ID, "Use the safe tunnel", TIME + 11)
                    : registry.stop(ID, TIME + 11);
            AgentRecord after = registry.require(ID);
            require(after.state() == AgentLifecycleState.DEAD, "Control must preserve physical DEAD"); assertions++;
            require(after.deathSnapshot().orElseThrow().equals(DEATH), "Exact respawn facts must survive"); assertions++;
            require(after.entityUuid().isEmpty() && after.entityLocation().isEmpty(), "Control cannot invent a body"); assertions++;
            require(after.resumeAfterRespawn() == steer, "Control updates continuation intent"); assertions++;
            require(after.goalRevision() == dead.goalRevision() + 1, "Control fences old revision"); assertions++;
            require(after.currentGoal().orElseThrow().goalId().equals(dead.currentGoal().orElseThrow().goalId()), "Same goal"); assertions++;
            require(after.queuedGoals().equals(dead.queuedGoals()), "Queue survives"); assertions++;
            require(change.cancelAction() && change.interruptPlanner(), "Pending actions are cancelled"); assertions++;
            require(reload(after).require(ID).equals(after), "Controlled death survives fresh persistence"); assertions++;
            if (steer) {
                require(after.currentGoal().orElseThrow().steeringInstructions().equals(List.of("Use the safe tunnel")), "Steering retained"); assertions++;
            }
            expect("FIXTURE_PUBLICATION_FAILURE", () -> registry.respawnAtomically(ID, BODY, TIME + 12,
                    (transition, commit) -> { commit.run(); throw new AgentDomainException("FIXTURE_PUBLICATION_FAILURE", "fixture"); })); assertions++;
            require(registry.require(ID).equals(after), "Failed respawn restores revised intent and exact death facts"); assertions++;
            AgentRecord respawned = registry.respawnAtomically(ID, BODY, TIME + 13,
                    (transition, commit) -> commit.run()).after();
            require(respawned.state() == (steer ? AgentLifecycleState.STARTING : AgentLifecycleState.PAUSED), "Respawn applies control intent"); assertions++;
            require(respawned.deathSnapshot().isEmpty() && !respawned.resumeAfterRespawn(), "Committed respawn clears death facts"); assertions++;
            require(respawned.entityUuid().orElseThrow().equals(BODY), "Committed respawn attaches body"); assertions++;
        }
        AgentRecord paused = AgentLifecycleReducer.stop(live, TIME + 5).after();
        require(paused.state() == AgentLifecycleState.PAUSED, "Live stop unchanged"); assertions++;
        require(AgentLifecycleReducer.steer(paused, "Stay safe", TIME + 6).after().state() == AgentLifecycleState.STARTING, "Live steer unchanged"); assertions++;
        AgentRecord pausedDead = AgentLifecycleReducer.die(paused, DEATH, TIME + 6).after();
        require(!pausedDead.resumeAfterRespawn(), "Stop before death remains paused"); assertions++;
        AgentRecord steeredDead = AgentLifecycleReducer.steer(pausedDead, "Use tunnel", TIME + 7).after();
        require(steeredDead.resumeAfterRespawn(), "Steer re-arms paused dead goal"); assertions++;
        require(!AgentLifecycleReducer.stop(steeredDead, TIME + 8).after().resumeAfterRespawn(), "Later stop overrides continuation"); assertions++;
        for (GoalStatus status : List.of(GoalStatus.SATISFIED, GoalStatus.CANCELLED)) {
            Optional<GoalEvidence> evidence = status == GoalStatus.SATISFIED
                    ? Optional.of(new GoalEvidence(4L, "fixture", List.of(new GoalEvidence.Fact("operator_confirmed", true, "yes", "yes"))))
                    : Optional.empty();
            AgentGoal goal = dead.currentGoal().orElseThrow().withStatus(status, evidence, TIME + 5);
            AgentRegistry registry = reload(dead.withLifecycle(AgentLifecycleState.DEAD, Optional.of(goal), dead.goalRevision(), dead.queuedGoals(), TIME + 6, ""));
            AgentRecord before = registry.require(ID);
            expect("TERMINAL_GOAL", () -> registry.stop(ID, TIME + 11)); assertions++;
            expect("TERMINAL_GOAL", () -> registry.steer(ID, "Wait", TIME + 11)); assertions++;
            require(registry.require(ID).equals(before), "Terminal rejection is unchanged"); assertions++;
        }
        AgentRegistry empty = reload(AgentLifecycleReducer.die(initial, DEATH, TIME + 4).after());
        expect("NO_CURRENT_GOAL", () -> empty.stop(ID, TIME + 11)); assertions++;
        expect("NO_CURRENT_GOAL", () -> empty.steer(ID, "Wait", TIME + 11)); assertions++;
        return assertions;
    }

    private static AgentRegistry reload(AgentRecord record) {
        AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
        AgentRegistry.Snapshot snapshot = new AgentRegistry.Snapshot(AgentConstants.SCHEMA_VERSION,
                AgentConstants.DEFAULT_AGENT_LIMIT, AgentConstants.DEFAULT_QUEUE_LIMIT, List.of(record));
        return AgentRegistry.restore(codec.decode(codec.encode(snapshot)), () -> {}, transition -> {}, TIME + 10);
    }

    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (AgentDomainException error) { require(code.equals(error.code()), "Unexpected error: " + error.code()); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
