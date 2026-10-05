package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.InteractionHand;

/** Offline phase regression: real sequence, leases and use driver; simulated player physics/effects. */
public final class UseTickPhaseVerification {
	private static final AgentId AGENT = AgentId.random();
	private static int assertions;

	private UseTickPhaseVerification() { }

	public static void main(String[] args) throws IOException {
		System.out.println("PASS: " + verify() + " use tick phase assertions");
	}

	public static int verify() throws IOException {
		assertions = 0;
		verifyHookOrdering();
		for (int ticks : new int[]{1, 20, 32}) {
			for (InteractionHand hand : InteractionHand.values()) {
				verifyDuration(ticks, hand, false);
				verifyDuration(ticks, hand, true);
			}
		}
		verifyReleaseAndHandChange();
		verifyArbitrationAndDeduplication();
		verifyCancellationBeforeUse();
		return assertions;
	}

	private static void verifyHookOrdering() throws IOException {
		Path sources = Path.of("src/main/java/dev/agaminggod/arenaagents");
		String runtime = Files.readString(sources.resolve("server/CodexAgentServerRuntime.java"));
		check(runtime.contains("START_SERVER_TICK.register(CodexAgentServerRuntime::startTick)"), "START hook is registered");
		check(runtime.contains("END_SERVER_TICK.register(CodexAgentServerRuntime::endTick)"), "END hook is registered");
		String start = method(runtime, "private static void startTick(");
		check(start.indexOf("if (!RESTORED_SERVERS.contains(server)) return;") >= 0,
				"restoration still fences input and admission");
		int admission = start.indexOf("if (bridge != null) bridge.startTick();");
		int input = start.indexOf("AgentInputRuntime.tick(server);");
		check(admission >= 0 && input > admission, "retained use executes at START after action admission and release");
		String end = method(runtime, "private static void endTick(");
		check(end.contains("activeBridge.endTick()") && !end.contains("AgentInputRuntime.tick("),
				"END publishes observations without a post-observation use interaction");
		check(runtime.indexOf("AgentInputRuntime.tick(server);") == runtime.lastIndexOf("AgentInputRuntime.tick(server);"),
				"input deadman and use advance only once per server tick");
		String sink = Files.readString(sources.resolve("server/runtime/input/CarpetInputStateSink.java"));
		String tick = method(sink, "public void tick(");
		check(tick.indexOf("if (state.attack())") < tick.indexOf("useDriver.tick("),
				"combined use still reserves execution for Carpet arbitration");
		check(tick.contains("CarpetActionArbitration.bind(player, agentId, state.hand(), useDriver);\n\t\t\treturn;")
				|| tick.contains("CarpetActionArbitration.bind(player, agentId, state.hand(), useDriver);\r\n\t\t\treturn;"),
				"START does not claim the combined attack/use slot");
		String mixin = Files.readString(sources.resolve("mixin/EntityPlayerActionPackMixin.java"));
		check(mixin.contains("method = \"onUpdate\"") && mixin.contains("CarpetActionArbitration.arbitrate(player,"),
				"Carpet action update retains the use-before-attack redirect");
	}

	private static void verifyDuration(int ticks, InteractionHand hand, boolean attack) {
		Fixture f = new Fixture();
		AgentInputState input = state(hand, true, attack);
		ControlSequence sequence = new ControlSequence(List.of(new ControlSequence.Frame(input, ticks, List.of())), ticks);
		InputLease lease = f.controller.acquire(AGENT, InputOwner.DIRECT_CONTROL, 250);
		for (int tick = 1; tick <= ticks; tick++) {
			f.tick = tick;
			ControlSequence.Step step = sequence.next(f.facts());
			check(step.status() == ControlSequence.Status.RUNNING, "authored frame remains running for tick " + tick);
			f.controller.apply(lease, step.input()); // bridge START admission
			f.controller.tick(); // runtime START input application
			f.carpetTick(); // Carpet's ServerPlayer.tick HEAD, before item-use physics
			f.physics();
			check(f.using && f.hand == hand && f.physicsTicks == tick,
					"END observes exact-hand use after all " + tick + " authored physics ticks");
		}
		check(f.useCalls == 1 && f.attacks == 0, "held use starts once and suppresses combined attack");
		check(f.controller.acceptedUses(AGENT) == 1, "accepted-use evidence counts the actual operation once");
		f.tick++;
		check(sequence.next(f.facts()).status() == ControlSequence.Status.COMPLETED, "completion follows final physics tick");
		f.controller.release(lease); // next START completes the authored frame
		f.controller.tick();
		f.carpetTick();
		f.physics();
		check(!f.using && f.releases == 1 && f.physicsTicks == ticks,
				"1/20/32 tick frame releases before the following physics tick");
		check(f.useCalls == 1, "END and release do not generate another interaction");
	}

	private static void verifyReleaseAndHandChange() {
		Fixture f = new Fixture();
		InputLease lease = f.controller.acquire(AGENT, InputOwner.DIRECT_CONTROL, 250);
		for (InteractionHand hand : new InteractionHand[]{InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND}) {
			f.tick++;
			f.controller.apply(lease, state(hand, true, false));
			f.controller.tick();
			f.physics();
			check(f.using && f.hand == hand, "hand changes apply before that tick's physics");
		}
		check(f.useCalls == 2 && f.releases == 1, "hand change releases old hand and starts exact new hand once");
		f.tick++;
		f.controller.apply(lease, state(InteractionHand.OFF_HAND, false, true));
		f.controller.tick();
		f.carpetTick();
		f.physics();
		check(!f.using && f.releases == 2 && f.attacks == 1 && f.physicsTicks == 2,
				"explicit use release allows attack and prevents an extra use physics tick");
	}

	private static void verifyArbitrationAndDeduplication() {
		Fixture f = new Fixture();
		f.consume = false;
		f.hold = false;
		f.driver.arbitrate(AGENT, InteractionHand.OFF_HAND, f, () -> {
			f.events.add("attack");
			f.attacks++;
			f.consume = true;
			return true;
		}, 1);
		check(f.events.equals(List.of("use:OFF_HAND", "attack", "use:OFF_HAND")),
				"passing use permits attack then the intentional successful-attack use retry");
		f.driver.tick(AGENT, InteractionHand.OFF_HAND, f, 1);
		f.driver.arbitrate(AGENT, InteractionHand.OFF_HAND, f, () -> { throw new AssertionError("duplicate attack"); }, 1);
		check(f.useCalls == 2 && f.attacks == 1 && f.driver.acceptedUses(AGENT) == 1,
				"same tick callbacks cannot double accepted interactions or attacks");
		for (int tick = 2; tick <= 5; tick++) {
			f.driver.tick(AGENT, InteractionHand.OFF_HAND, f, tick);
			f.driver.tick(AGENT, InteractionHand.OFF_HAND, f, tick);
		}
		check(f.useCalls == 3, "repeat cooldown advances once per tick despite duplicate callbacks");
		Fixture passing = new Fixture();
		passing.consume = false;
		passing.driver.arbitrate(AGENT, InteractionHand.MAIN_HAND, passing, () -> false, 1);
		check(passing.useCalls == 1, "failed attack does not add a use retry");
	}

	private static void verifyCancellationBeforeUse() {
		Fixture f = new Fixture();
		InputLease lease = f.controller.acquire(AGENT, InputOwner.DIRECT_CONTROL, 250);
		f.controller.apply(lease, state(InteractionHand.OFF_HAND, true, false));
		f.controller.clear(AGENT); // cancellation admitted before input application
		f.controller.tick();
		f.carpetTick();
		f.physics();
		check(f.useCalls == 0 && f.physicsTicks == 0, "cancelled armed input never executes");
		try {
			f.controller.apply(lease, state(InteractionHand.OFF_HAND, true, false));
			throw new AssertionError("cancelled lease was reusable");
		} catch (LeasedServerInputController.StaleInputLeaseException expected) {
			check(f.useCalls == 0, "stale lease cannot restore cancelled use");
		}
		Fixture active = new Fixture();
		InputLease activeLease = active.controller.acquire(AGENT, InputOwner.DIRECT_CONTROL, 250);
		active.controller.apply(activeLease, state(InteractionHand.OFF_HAND, true, true));
		active.controller.tick();
		active.carpetTick();
		active.physics();
		active.tick++;
		active.controller.clear(AGENT);
		active.controller.tick();
		active.carpetTick();
		active.physics();
		check(!active.using && active.releases == 1 && active.physicsTicks == 1 && active.useCalls == 1 && active.attacks == 0,
				"cancellation of active combined use releases before physics and cannot replay use or attack");
	}

	private static AgentInputState state(InteractionHand hand, boolean use, boolean attack) {
		return new AgentInputState(0, 0, false, false, false, attack, use, 0, 0, 0, hand);
	}

	private static String method(String source, String signature) {
		int start = source.indexOf(signature);
		if (start < 0) throw new AssertionError("missing hook " + signature);
		int brace = source.indexOf('{', start);
		int depth = 1;
		for (int end = brace + 1; end < source.length(); end++) {
			if (source.charAt(end) == '{') depth++;
			if (source.charAt(end) == '}' && --depth == 0) return source.substring(start, end + 1);
		}
		throw new AssertionError("unterminated hook " + signature);
	}

	private static void check(boolean condition, String reason) {
		if (!condition) throw new AssertionError(reason);
		assertions++;
	}

	/** Mirrors only sink dispatch glue; real driver owns cooldown, exact hand, arbitration and dedupe. */
	private static final class Fixture implements InputStateSink, ExactHandUseDriver.PlayerUseAccess {
		private final ExactHandUseDriver driver = new ExactHandUseDriver();
		private final LeasedServerInputController controller = new LeasedServerInputController(this);
		private final List<String> events = new ArrayList<>();
		private AgentInputState input;
		private InteractionHand hand;
		private long tick;
		private boolean using, consume = true, hold = true;
		private int physicsTicks, useCalls, releases, attacks;

		@Override public void apply(AgentId id, AgentInputState previous, AgentInputState state) {
			if (previous != null && previous.use() && (!state.use() || previous.hand() != state.hand())) driver.stop(id, this);
			input = state;
			if (state.use()) driver.start(id, state.hand(), this);
		}
		@Override public void tick(AgentId id, AgentInputState state) {
			if (state.use() && !state.attack()) driver.tick(id, state.hand(), this, tick);
		}
		@Override public long acceptedUses(AgentId id) { return driver.acceptedUses(id); }
		@Override public void clear(AgentId id, AgentInputState previous) {
			input = null;
			driver.stop(id, this);
			driver.discard(id);
		}
		private void carpetTick() {
			if (input == null || !input.attack()) return;
			if (input.use()) driver.arbitrate(AGENT, input.hand(), this, () -> { attacks++; return true; }, tick);
			else attacks++;
		}
		private void physics() { if (using) physicsTicks++; }
		private ControlSequence.Facts facts() { return new ControlSequence.Facts(20, 20, 300, false, false, true, false, false, using); }
		@Override public boolean isUsingItem() { return using; }
		@Override public InteractionHand usedHand() { return hand; }
		@Override public void releaseUsingItem() { using = false; releases++; }
		@Override public ExactHandUseDriver.TargetKind target() { return ExactHandUseDriver.TargetKind.MISS; }
		@Override public ExactHandUseDriver.TargetAttempt useBlock(InteractionHand hand) { throw new AssertionError("unexpected block target"); }
		@Override public ExactHandUseDriver.TargetAttempt useEntity(InteractionHand hand) { throw new AssertionError("unexpected entity target"); }
		@Override public boolean useItem(InteractionHand hand) {
			useCalls++;
			events.add("use:" + hand);
			this.hand = hand;
			using = consume && hold;
			return consume;
		}
		@Override public void swing(InteractionHand hand) { throw new AssertionError("unexpected swing"); }
	}
}
