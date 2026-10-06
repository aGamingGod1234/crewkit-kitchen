package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.pov.PovMode;
import dev.agaminggod.arenaagents.server.SkitModeRuntime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** Headless checks for the POV session rules: reservations, exits, body damage and lifecycle. */
public final class PovSessionVerification {
	private static int passed;

	private PovSessionVerification() {
	}

	public static int verify() {
		passed = 0;
		verifyReservationTable();
		verifySkitAndTakeoverExclusion();
		verifyStartDecisions();
		verifyExitSelection();
		verifyBodyDamage();
		verifyLifecycleTable();
		verifyExitLifecycle();
		verifyReportBounds();
		verifyPickupTally();
		return passed;
	}

	private static void verifyReservationTable() {
		AgentControlReservations.Table table = new AgentControlReservations.Table();
		AgentId agent = AgentId.random();
		AgentId other = AgentId.random();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		table.reserve(agent, first);
		check(table.owner(agent).equals(Optional.of(first)), "takeover reserves the agent for its operator");
		expectReserved(() -> table.requireUnreserved(agent), "reserved agent refuses normal control");
		table.requireUnreserved(other);
		pass("unreserved agents keep normal control");
		table.reserve(agent, first);
		pass("the owner may re-reserve its own agent");
		expectReserved(() -> table.reserve(agent, second), "second operator's takeover is rejected");
		check(table.owner(agent).equals(Optional.of(first)), "rejected takeover leaves the first owner in place");
		check(!table.release(agent, second), "another operator cannot release the reservation");
		check(table.release(agent, first) && table.isEmpty(), "owner release frees the agent");
		table.requireUnreserved(agent);
		pass("released agent accepts normal control again");
		AgentControlReservations.requireUnreserved(null, agent);
		check(!AgentControlReservations.isReserved(null, agent), "missing server never reports a reservation");
	}

	private static void verifySkitAndTakeoverExclusion() {
		MinecraftServer server = allocateServer();
		AgentId agent = AgentId.random();
		try {
			AgentControlReservations.reserve(server, agent, UUID.randomUUID());
			expectReserved(() -> SkitModeRuntime.requireNormalControlAllowed(server, agent),
					"skit and normal control gate refuses a taken-over agent");
			SkitModeRuntime.requireNormalControlAllowed(server, AgentId.random());
			pass("other agents stay controllable during a takeover");
		} finally {
			AgentControlReservations.releaseAll(server);
		}
		SkitModeRuntime.requireNormalControlAllowed(server, agent);
		pass("server release clears every takeover reservation");
		check(PovSessionRuntime.planStart(UUID.randomUUID(), agent, PovMode.TAKEOVER, Optional.empty(), Optional.empty(), true)
				== PovSessionRuntime.StartDecision.REJECT_SKIT, "takeover refuses an agent in skit playback");
		check(PovSessionRuntime.planStart(UUID.randomUUID(), agent, PovMode.SPECTATE, Optional.empty(), Optional.empty(), true)
				== PovSessionRuntime.StartDecision.START, "spectating a skit actor is allowed");
	}

	private static void verifyStartDecisions() {
		UUID operator = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		AgentId a = AgentId.random();
		AgentId b = AgentId.random();
		check(plan(operator, a, PovMode.TAKEOVER, null, null) == PovSessionRuntime.StartDecision.START,
				"first takeover starts");
		check(plan(operator, a, PovMode.TAKEOVER, null, other) == PovSessionRuntime.StartDecision.REJECT_RESERVED,
				"takeover of an agent owned by another operator is rejected");
		check(plan(operator, a, PovMode.SPECTATE, null, other) == PovSessionRuntime.StartDecision.START,
				"several operators may spectate a taken-over agent");
		check(plan(operator, a, PovMode.TAKEOVER, new PovSessionRuntime.ActiveView(a, PovMode.TAKEOVER), operator)
				== PovSessionRuntime.StartDecision.ALREADY_ACTIVE, "repeating the same takeover keeps the session");
		check(plan(operator, a, PovMode.TAKEOVER, new PovSessionRuntime.ActiveView(a, PovMode.SPECTATE), null)
				== PovSessionRuntime.StartDecision.REPLACE, "spectate to takeover replaces the session");
		check(plan(operator, b, PovMode.SPECTATE, new PovSessionRuntime.ActiveView(a, PovMode.TAKEOVER), operator)
				== PovSessionRuntime.StartDecision.REPLACE, "a new agent replaces the operator's takeover");
		check(plan(operator, b, PovMode.TAKEOVER, new PovSessionRuntime.ActiveView(a, PovMode.SPECTATE), other)
				== PovSessionRuntime.StartDecision.REJECT_RESERVED, "a rejected start leaves the current session alone");
	}

	private static PovSessionRuntime.StartDecision plan(UUID operator, AgentId agent, PovMode mode,
			PovSessionRuntime.ActiveView current, UUID owner) {
		return PovSessionRuntime.planStart(operator, agent, mode, Optional.ofNullable(current), Optional.ofNullable(owner), false);
	}

	private static void verifyExitSelection() {
		check(stays(observe(PovMode.TAKEOVER)), "healthy takeover keeps running");
		expectExit(observe(PovMode.TAKEOVER).offline(), PovExitReason.OPERATOR_DISCONNECTED, "disconnect ends the session");
		expectExit(observe(PovMode.TAKEOVER).offline().removed(), PovExitReason.OPERATOR_DISCONNECTED,
				"disconnect outranks every other reason");
		expectExit(observe(PovMode.SPECTATE).removed().noPermission(), PovExitReason.AGENT_REMOVED, "removed agent ends spectating");
		expectExit(observe(PovMode.SPECTATE).noPermission(), PovExitReason.PERMISSION_LOST, "lost permission ends the session");
		expectExit(observe(PovMode.TAKEOVER).dead().damaged(6.0D), PovExitReason.OPERATOR_DIED, "body death ends a takeover");
		expectExit(observe(PovMode.SPECTATE).dead(), PovExitReason.OPERATOR_DIED, "operator death ends spectating");
		expectExit(observe(PovMode.TAKEOVER).damaged(4.0D), PovExitReason.BODY_DAMAGED, "two hearts of damage end a takeover");
		check(stays(observe(PovMode.TAKEOVER).damaged(3.9D)), "3.9 damage keeps the takeover");
		check(stays(observe(PovMode.SPECTATE).damaged(12.0D)), "spectating never exits on body damage");
		expectExit(observe(PovMode.SPECTATE).operatorDimensionChanged(), PovExitReason.OPERATOR_DIMENSION_CHANGED,
				"operator dimension change ends the session");
		check(stays(observe(PovMode.TAKEOVER).moved(32.0D)), "32 blocks is still not a teleport");
		expectExit(observe(PovMode.TAKEOVER).moved(32.5D), PovExitReason.OPERATOR_TELEPORTED, "a long jump is a teleport");
		expectExit(observe(PovMode.TAKEOVER).claimed(), PovExitReason.CLAIMED_BY_SKIT, "a skit claim ends a takeover");
		check(stays(observe(PovMode.SPECTATE).claimed()), "a skit claim leaves spectators watching");
		expectExit(observe(PovMode.SPECTATE).agentElsewhere(), PovExitReason.AGENT_DIMENSION_CHANGED,
				"agent dimension change ends the session");
		check(stays(observe(PovMode.SPECTATE).agentElsewhere().agentAbsent()),
				"an absent agent is not judged by dimension");
		check(PovExitReason.agentDimensionMessage("minecraft:the_nether", PovMode.TAKEOVER, "Alex").equals(
				"Agent moved to minecraft:the_nether. Run /takeover Alex again once you are in the same dimension."),
				"dimension message names the command to re-run");
		check(PovExitReason.agentDimensionMessage("minecraft:the_end", PovMode.SPECTATE, "Alex").contains("/spectator Alex"),
				"spectator hint names /spectator");
		expectThrows(() -> new PovExitReason.Observation(PovMode.TAKEOVER, true, true, true, false, Double.NaN,
				true, true, false, false, 0.0D), "observation rejects non-finite movement");
	}

	private static void verifyBodyDamage() {
		float health = 20.0F;
		float absorption = 4.0F;
		PovSession.BodyDamage damage = new PovSession.BodyDamage();
		float afterFirstHit = absorption - 3.9F;
		damage.before(health + absorption);
		damage.after(health + afterFirstHit);
		check(!damage.exceeded(), "3.9 damage absorbed by absorption hearts does not end the takeover");
		damage.before(health + afterFirstHit);
		damage.after(health + Math.max(0.0F, afterFirstHit - 0.1F));
		check(damage.exceeded(), "absorption loss counts toward the 4.0 total");

		PovSession.BodyDamage exact = new PovSession.BodyDamage();
		exact.before(20.0F);
		exact.after(16.0F);
		check(exact.exceeded() && exact.total() == 4.0D, "exactly 4.0 HP ends the takeover");

		PovSession.BodyDamage healing = new PovSession.BodyDamage();
		healing.before(20.0F);
		healing.after(19.0F);
		healing.before(20.0F);
		healing.after(18.0F);
		check(healing.total() == 3.0D && !healing.exceeded(), "healing between hits never cancels earlier damage");
		healing.after(10.0F);
		check(healing.total() == 3.0D, "an after-damage event without its before event is ignored");
		healing.before(18.0F);
		healing.after(18.5F);
		check(healing.total() == 3.0D, "a hit that heals counts as zero damage");
		check(!PovSession.BodyDamage.reachesExit(3.9D) && PovSession.BodyDamage.reachesExit(4.0D),
				"threshold boundary sits at 4.0");
	}

	private static void verifyLifecycleTable() {
		for (AgentLifecycleState state : new AgentLifecycleState[] {
				AgentLifecycleState.STARTING, AgentLifecycleState.PLANNING, AgentLifecycleState.ACTING, AgentLifecycleState.DISCONNECTED }) {
			check(PovSession.LifecyclePlan.forTakeover(state, false).equals(new PovSession.LifecyclePlan(true, true)),
					state + " is stopped at start and resumed on exit");
		}
		for (AgentLifecycleState state : new AgentLifecycleState[] {
				AgentLifecycleState.PAUSED, AgentLifecycleState.IDLE, AgentLifecycleState.COMPLETED, AgentLifecycleState.ERROR }) {
			check(PovSession.LifecyclePlan.forTakeover(state, false).equals(new PovSession.LifecyclePlan(false, false)),
					state + " is only reserved");
		}
		check(PovSession.LifecyclePlan.forTakeover(AgentLifecycleState.DEAD, true).equals(new PovSession.LifecyclePlan(true, true)),
				"dead agent that meant to continue resumes after the takeover");
		check(PovSession.LifecyclePlan.forTakeover(AgentLifecycleState.DEAD, false).equals(new PovSession.LifecyclePlan(false, false)),
				"dead agent without continuation stays paused");
	}

	private static void verifyExitLifecycle() {
		check(PovSession.ExitLifecycle.decide(true, AgentLifecycleState.PAUSED, true, true) == PovSession.ExitLifecycle.RESUME,
				"stopped agent resumes its unfinished goal");
		check(PovSession.ExitLifecycle.decide(false, AgentLifecycleState.PAUSED, true, true) == PovSession.ExitLifecycle.NONE,
				"an agent paused before the takeover stays paused");
		check(PovSession.ExitLifecycle.decide(true, AgentLifecycleState.DEAD, true, true) == PovSession.ExitLifecycle.RESUME_AFTER_RESPAWN,
				"an agent left dead resumes once it respawns");
		check(PovSession.ExitLifecycle.decide(true, AgentLifecycleState.PAUSED, true, false) == PovSession.ExitLifecycle.NONE,
				"a goal changed by someone else during the takeover is not resumed");
		check(PovSession.ExitLifecycle.decide(true, AgentLifecycleState.PAUSED, false, true) == PovSession.ExitLifecycle.NONE,
				"a finished goal is never resumed");
		check(PovSession.ExitLifecycle.decide(true, AgentLifecycleState.IDLE, true, true) == PovSession.ExitLifecycle.NONE,
				"an idle agent is left idle");
	}

	private static void verifyReportBounds() {
		String shortReport = PovSessionRuntime.report("  moved 37 blocks.  ");
		check(shortReport.equals("Operator takeover report: moved 37 blocks."), "report carries the operator prefix");
		String longReport = PovSessionRuntime.report(Character.toString(0x1F600).repeat(600));
		check(longReport.codePointCount(0, longReport.length()) == PovSessionRuntime.MAX_REPORT_CODE_POINTS
				&& longReport.endsWith("..."), "long reports are cut to the 512 code point conversation limit");
		check(!Character.isHighSurrogate(longReport.charAt(longReport.length() - 4)), "truncation never splits a surrogate pair");
	}

	private static void verifyPickupTally() {
		PovSession.PickupTally tally = new PovSession.PickupTally();
		tally.observe(Map.of("oak_log", 10, "dirt", 4));
		check(tally.describe(4).isEmpty(), "the first sample is only a baseline");
		tally.observe(Map.of("oak_log", 13, "dirt", 4, "cobblestone", 5));
		tally.observe(Map.of("oak_log", 2, "cobblestone", 5));
		tally.observe(Map.of("oak_log", 3, "cobblestone", 5));
		check(tally.gained().equals(Map.of("oak_log", 4, "cobblestone", 5)), "only increases count as pickups");
		check(tally.describe(4).equals(Optional.of("picked up 5 cobblestone, 4 oak_log")), "pickups list the largest first");
		check(tally.describe(1).equals(Optional.of("picked up 5 cobblestone and 1 more")), "long pickup lists are summarized");
		check(PovSession.shortDimension("minecraft:the_nether").equals("the_nether"), "vanilla dimensions are shortened");
	}

	private static Observed observe(PovMode mode) {
		return new Observed(mode, true, true, true, false, 0.0D, true, true, false, false, 0.0D);
	}

	/** Fluent builder over the healthy baseline, changing one fact per call. */
	private record Observed(PovMode mode, boolean online, boolean alive, boolean mayControl, boolean dimensionChanged,
			double moved, boolean registered, boolean present, boolean elsewhere, boolean claim, double damage) {
		Observed offline() { return new Observed(mode, false, alive, mayControl, dimensionChanged, moved, registered, present, elsewhere, claim, damage); }
		Observed dead() { return new Observed(mode, online, false, mayControl, dimensionChanged, moved, registered, present, elsewhere, claim, damage); }
		Observed noPermission() { return new Observed(mode, online, alive, false, dimensionChanged, moved, registered, present, elsewhere, claim, damage); }
		Observed operatorDimensionChanged() { return new Observed(mode, online, alive, mayControl, true, moved, registered, present, elsewhere, claim, damage); }
		Observed moved(double blocks) { return new Observed(mode, online, alive, mayControl, dimensionChanged, blocks, registered, present, elsewhere, claim, damage); }
		Observed removed() { return new Observed(mode, online, alive, mayControl, dimensionChanged, moved, false, present, elsewhere, claim, damage); }
		Observed agentAbsent() { return new Observed(mode, online, alive, mayControl, dimensionChanged, moved, registered, false, elsewhere, claim, damage); }
		Observed agentElsewhere() { return new Observed(mode, online, alive, mayControl, dimensionChanged, moved, registered, present, true, claim, damage); }
		Observed claimed() { return new Observed(mode, online, alive, mayControl, dimensionChanged, moved, registered, present, elsewhere, true, damage); }
		Observed damaged(double amount) { return new Observed(mode, online, alive, mayControl, dimensionChanged, moved, registered, present, elsewhere, claim, amount); }

		PovExitReason.Observation build() {
			return new PovExitReason.Observation(mode, online, alive, mayControl, dimensionChanged, moved,
					registered, present, elsewhere, claim, damage);
		}
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
		pass(label);
	}

	private static boolean stays(Observed observed) {
		return PovExitReason.select(observed.build()).isEmpty();
	}

	private static void expectExit(Observed observed, PovExitReason expected, String label) {
		Optional<PovExitReason> selected = PovExitReason.select(observed.build());
		if (!selected.equals(Optional.of(expected))) {
			throw new AssertionError(label + ": expected " + expected + " but was " + selected);
		}
		pass(label);
	}

	private static void expectReserved(Runnable action, String label) {
		try {
			action.run();
		} catch (AgentDomainException exception) {
			if (!AgentControlReservations.RESERVED_CODE.equals(exception.code())) {
				throw new AssertionError(label + ": unexpected code " + exception.code(), exception);
			}
			pass(label);
			return;
		}
		throw new AssertionError(label + ": no reservation error");
	}

	private static void expectThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (IllegalArgumentException expected) {
			pass(label);
			return;
		}
		throw new AssertionError(label + ": no exception");
	}

	private static void pass(String label) {
		passed++;
		System.out.println("PASS: " + label);
	}

	private static MinecraftServer allocateServer() {
		try {
			var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return (MinecraftServer) ((sun.misc.Unsafe) field.get(null))
					.allocateInstance(net.minecraft.server.dedicated.DedicatedServer.class);
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(error);
		}
	}
}
