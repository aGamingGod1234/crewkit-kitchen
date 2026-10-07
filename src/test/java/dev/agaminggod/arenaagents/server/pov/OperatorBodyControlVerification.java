package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.pov.OperatorInputPayload;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.InputStateSink;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;

/** Dependency-free checks for operator takeover input: frame decoding, key timing, actions and leases. */
public final class OperatorBodyControlVerification {
	private static final AgentId AGENT = new AgentId(UUID.fromString("00000000-0000-0000-0000-0000000000a3"));
	private static final int ALL_HELD = OperatorInputPayload.HELD_JUMP | OperatorInputPayload.HELD_SNEAK
			| OperatorInputPayload.HELD_SPRINT | OperatorInputPayload.HELD_ATTACK | OperatorInputPayload.HELD_USE;

	private OperatorBodyControlVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " operator body control assertions");
	}

	public static int verify() {
		return verifyFrameDecoding()
				+ verifyInputStateConversion()
				+ verifySprintLatch()
				+ verifyMinorCollision()
				+ verifyAttackKeyTiming()
				+ verifyUseKeyTiming()
				+ verifyServerSelectedSlot()
				+ verifyActionDecoding()
				+ verifyLeasePriority()
				+ verifyStaleLeaseReacquire()
				+ verifyNeutralRelease()
				+ verifyLeaseRenewalOutlivesDeadman();
	}

	private static int verifyFrameDecoding() {
		// Only in-range values go through the payload record, whose constructor may bound its own fields.
		CarpetOperatorBodyController.Frame frame = CarpetOperatorBodyController.Frame.decode(
				new OperatorInputPayload(7L, 1, 0.5F, -0.25F, 45.0F, 10.0F, ALL_HELD, 4), null);
		check(frame.forward() == 0.5F && frame.strafe() == -0.25F, "axes inside [-1, 1] pass through");
		check(frame.jump() && frame.sneak() && frame.sprint() && frame.attack() && frame.use(), "every held flag decodes");
		check(frame.selectedSlot() == 4 && frame.yaw() == 45.0F && frame.pitch() == 10.0F, "look and slot decode");
		for (int bit : new int[]{OperatorInputPayload.HELD_JUMP, OperatorInputPayload.HELD_SNEAK,
				OperatorInputPayload.HELD_SPRINT, OperatorInputPayload.HELD_ATTACK, OperatorInputPayload.HELD_USE}) {
			CarpetOperatorBodyController.Frame single = decode(0, 0, 0, 0, bit | 1 << 20, 0, null);
			int decoded = (single.jump() ? OperatorInputPayload.HELD_JUMP : 0)
					| (single.sneak() ? OperatorInputPayload.HELD_SNEAK : 0)
					| (single.sprint() ? OperatorInputPayload.HELD_SPRINT : 0)
					| (single.attack() ? OperatorInputPayload.HELD_ATTACK : 0)
					| (single.use() ? OperatorInputPayload.HELD_USE : 0);
			check(decoded == bit, "flag " + bit + " decodes alone and unknown bits are ignored");
		}
		CarpetOperatorBodyController.Frame clamped = decode(2.0F, -3.0F, 370.0F, 120.0F, 0, 9, frame);
		check(clamped.forward() == 1.0F && clamped.strafe() == -1.0F, "axes clamp to [-1, 1]");
		check(clamped.yaw() == 10.0F, "yaw wraps like vanilla move packets");
		check(clamped.pitch() == 90.0F, "pitch clamps to the vanilla range");
		check(clamped.selectedSlot() == 4, "an invalid hotbar slot keeps the previous selection");
		CarpetOperatorBodyController.Frame broken = decode(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, 0, -1, frame);
		check(broken.forward() == 0.0F && broken.strafe() == 0.0F, "non-finite axes are released");
		check(broken.yaw() == 45.0F && broken.pitch() == 10.0F && broken.selectedSlot() == 4,
				"non-finite look and bad slot keep the previous frame");
		CarpetOperatorBodyController.Frame first = decode(0, 0, Float.NaN, -100.0F, 0, 12, null);
		check(first.yaw() == 0.0F && first.pitch() == -90.0F && first.selectedSlot() == 0,
				"first frame falls back to a neutral look and slot");
		return 10 + 5;
	}

	private static int verifyInputStateConversion() {
		CarpetOperatorBodyController.Keys keys = new CarpetOperatorBodyController.Keys();
		AgentInputState all = decode(1.0F, 0.5F, 30.0F, -20.0F, ALL_HELD, 3, null).toInputState(keys, false, true);
		check(all.forward() == 1.0F && all.strafe() == 0.5F && all.jump() && all.attack(), "held keys reach the sink");
		check(all.sneak() && !all.sprint(), "sneak wins when sneak and sprint are both held");
		check(!all.use(), "use never rides the sink; the dispatcher's two-hand use key owns it");
		check(all.hand() == InteractionHand.MAIN_HAND && all.selectedSlot() == 3
				&& all.yaw() == 30.0F && all.pitch() == -20.0F, "hand, slot and look are carried");
		int sprintOnly = OperatorInputPayload.HELD_SPRINT;
		check(decode(1, 0, 0, 0, 0, 0, null).toInputState(keys, false, true).sprint(),
				"the latched sprint decision reaches the sink without the key held");
		check(!decode(1, 0, 0, 0, sprintOnly, 0, null).toInputState(keys, false, false).sprint(),
				"a held sprint key alone never bypasses the latch decision");
		int attackOnly = OperatorInputPayload.HELD_ATTACK;
		check(!decode(0, 0, 0, 0, attackOnly, 0, null).toInputState(keys, true, true).attack(),
				"using an item pauses held mining like vanilla");
		return 7;
	}

	private static int verifySprintLatch() {
		CarpetOperatorBodyController.Keys keys = new CarpetOperatorBodyController.Keys();
		CarpetOperatorBodyController.Frame walk = decode(1, 0, 0, 0, 0, 0, null);
		CarpetOperatorBodyController.Frame sprintKey = decode(1, 0, 0, 0, OperatorInputPayload.HELD_SPRINT, 0, null);
		CarpetOperatorBodyController.Frame stand = decode(0, 0, 0, 0, 0, 0, null);
		check(!keys.sprinting(walk, body(false)), "walking forward alone never sprints");
		keys.onFrame(sprintKey);
		check(keys.sprinting(sprintKey, body(false)), "the sprint key starts sprint while moving forward");
		keys.onFrame(walk);
		keys.sprintTickEnded(walk);
		check(keys.sprinting(walk, body(true)), "sprint stays latched after the sprint key is let go");
		check(!keys.sprinting(stand, body(true)), "letting go of forward ends sprint");
		check(!keys.sprinting(decode(-1, 0, 0, 0, 0, 0, null), body(true)), "walking backwards ends sprint");
		check(!keys.sprinting(walk, body(true, Body.MAJOR_COLLISION)), "running into a wall head-on ends sprint");
		check(keys.sprinting(decode(0.70710677F, 0.70710677F, 0, 0, 0, 0, null), body(true)),
				"a diagonal keeps forward impulse and keeps sprinting");
		check(!keys.sprinting(walk, body(true, Body.HUNGRY)), "running out of food ends sprint");
		check(!keys.sprinting(walk, body(true, Body.BLIND)), "blindness ends sprint");
		check(!keys.sprinting(decode(1, 0, 0, 0, OperatorInputPayload.HELD_SNEAK, 0, null), body(true)),
				"sneaking ends sprint");

		CarpetOperatorBodyController.Keys held = new CarpetOperatorBodyController.Keys();
		held.onFrame(sprintKey);
		held.sprintTickEnded(sprintKey);
		check(!held.sprinting(stand, body(false)), "no sprint starts without forward input");
		check(!held.sprinting(decode(1, 0, 0, 0, OperatorInputPayload.HELD_SPRINT | OperatorInputPayload.HELD_SNEAK, 0, null),
				body(false)), "no sprint starts while sneaking");
		check(!held.sprinting(sprintKey, body(false, Body.HUNGRY)), "no sprint starts at 6 food or less");
		check(!held.sprinting(sprintKey, body(false, Body.BLIND)), "no sprint starts while blind");
		check(!held.sprinting(sprintKey, body(false, Body.SLOW_ITEM)), "no sprint starts while an item slows the body");
		check(!held.sprinting(sprintKey, body(false, Body.CRAWLING)), "no sprint starts while crouching or crawling");
		check(held.sprinting(sprintKey, body(false, Body.CRAWLING, Body.UNDER_WATER)), "crawling under water may sprint");
		check(!held.sprinting(sprintKey, body(false, Body.FALL_FLYING)), "no sprint starts while gliding");
		check(!held.sprinting(sprintKey, body(false, Body.SHALLOW_WATER)), "no sprint starts in shallow water");
		check(held.sprinting(sprintKey, body(false, Body.SHALLOW_WATER, Body.FLYING)), "flying ignores shallow water");
		check(!held.sprinting(sprintKey, body(false, Body.PASSENGER)), "a vehicle that cannot sprint blocks sprint");
		check(held.sprinting(sprintKey, body(false, Body.PASSENGER, Body.VEHICLE_SPRINTS, Body.HUNGRY)),
				"a sprinting vehicle ignores the rider's hunger");
		check(held.sprinting(sprintKey, body(false)), "a held sprint key restarts sprint after a server-side stop");

		CarpetOperatorBodyController.Keys tapped = new CarpetOperatorBodyController.Keys();
		tapped.onFrame(sprintKey);
		tapped.onFrame(walk);
		check(tapped.sprinting(walk, body(false)), "a one-frame double-tap still counts when the next frame lands first");
		tapped.sprintTickEnded(walk);
		check(!tapped.sprinting(walk, body(false)), "a consumed double-tap does not restart sprint later");
		tapped.onFrame(sprintKey);
		tapped.newBody();
		check(!tapped.sprinting(walk, body(false)), "a new body forgets a pending request");

		CarpetOperatorBodyController.Keys swimmer = new CarpetOperatorBodyController.Keys();
		check(swimmer.sprinting(walk, body(true, Body.SWIMMING, Body.IN_WATER, Body.MAJOR_COLLISION)),
				"swim-sprinting ignores wall bumps like vanilla");
		check(!swimmer.sprinting(walk, body(true, Body.SWIMMING)), "leaving the water ends swim-sprinting");
		check(!swimmer.sprinting(stand, body(true, Body.SWIMMING, Body.IN_WATER)),
				"letting go of forward while swimming in open water ends sprint");
		check(swimmer.sprinting(stand, body(true, Body.SWIMMING, Body.IN_WATER, Body.ON_GROUND)),
				"swim-sprinting on the bottom survives letting go of forward");
		check(swimmer.sprinting(walk, body(true, Body.SWIMMING, Body.IN_WATER, Body.SHALLOW_WATER)),
				"swim-sprinting is allowed in shallow water");
		return 31;
	}

	private static int verifyMinorCollision() {
		check(CarpetOperatorBodyController.SprintBody.minorCollision(0.0F, 1.0F, 0.0F, 0.0D, 0.2D),
				"motion along the intended direction is a minor collision");
		check(CarpetOperatorBodyController.SprintBody.minorCollision(0.0F, 1.0F, 0.0F, 0.01D, 0.2D),
				"a few degrees off is still minor");
		check(!CarpetOperatorBodyController.SprintBody.minorCollision(0.0F, 1.0F, 0.0F, 0.2D, 0.0D),
				"motion sideways to the intent is a real collision");
		check(!CarpetOperatorBodyController.SprintBody.minorCollision(0.0F, 1.0F, 0.0F, 0.0D, 0.0D),
				"no motion at all is a head-on collision");
		check(CarpetOperatorBodyController.SprintBody.minorCollision(90.0F, 1.0F, 0.0F, -0.2D, 0.0D),
				"yaw rotates the intent like vanilla (facing -X at 90 degrees)");
		check(!CarpetOperatorBodyController.SprintBody.minorCollision(0.0F, 0.0F, 0.0F, 0.0D, 0.2D),
				"no intent is never minor");
		return 6;
	}

	private enum Body {
		BLIND, PASSENGER, VEHICLE_SPRINTS, HUNGRY, SHALLOW_WATER, FLYING, SLOW_ITEM, FALL_FLYING, UNDER_WATER,
		CRAWLING, SWIMMING, IN_WATER, ON_GROUND, MAJOR_COLLISION
	}

	/** A healthy body standing on dry land with the given sprint flag, changed by the listed facts. */
	private static CarpetOperatorBodyController.SprintBody body(boolean sprinting, Body... facts) {
		EnumSet<Body> set = EnumSet.noneOf(Body.class);
		set.addAll(Arrays.asList(facts));
		boolean swimming = set.contains(Body.SWIMMING);
		return new CarpetOperatorBodyController.SprintBody(
				sprinting,
				set.contains(Body.BLIND),
				set.contains(Body.PASSENGER),
				set.contains(Body.VEHICLE_SPRINTS),
				!set.contains(Body.HUNGRY),
				set.contains(Body.SHALLOW_WATER),
				set.contains(Body.FLYING),
				set.contains(Body.SLOW_ITEM),
				set.contains(Body.FALL_FLYING),
				set.contains(Body.UNDER_WATER),
				set.contains(Body.CRAWLING),
				swimming,
				set.contains(Body.IN_WATER),
				set.contains(Body.ON_GROUND) || !swimming,
				set.contains(Body.MAJOR_COLLISION));
	}

	private static int verifyAttackKeyTiming() {
		CarpetOperatorBodyController.Keys keys = new CarpetOperatorBodyController.Keys();
		CarpetOperatorBodyController.Frame held = decode(0, 0, 0, 0, OperatorInputPayload.HELD_ATTACK, 0, null);
		CarpetOperatorBodyController.Frame idle = decode(0, 0, 0, 0, 0, 0, null);
		check(keys.attackClickAllowed(false, false), "a fresh body accepts attack clicks");
		check(!keys.attackClickAllowed(true, false) && !keys.attackClickAllowed(false, true),
				"spectators and item use drop attack clicks");
		keys.missed(false);
		check(!keys.attackClickAllowed(false, false), "a survival miss starts vanilla's miss cooldown");
		check(!keys.attackHeld(held), "the miss cooldown also pauses held mining");
		for (int tick = 1; tick < CarpetOperatorBodyController.Keys.MISS_TIME; tick++) keys.tick();
		check(!keys.attackClickAllowed(false, false), "cooldown lasts the full vanilla miss time");
		keys.tick();
		check(keys.attackClickAllowed(false, false) && keys.attackHeld(held), "cooldown expires after ten ticks");
		keys.missed(false);
		keys.onFrame(idle);
		check(keys.attackClickAllowed(false, false), "releasing attack clears the miss cooldown");
		keys.missed(true);
		check(keys.attackClickAllowed(false, false), "creative misses have no cooldown");
		keys.blockClicked();
		check(keys.attackHeld(idle), "a block click holds attack even when no held frame arrived");
		keys.tick();
		check(keys.attackHeld(idle), "the click pulse survives the first tick boundary");
		keys.tick();
		check(!keys.attackHeld(idle), "the click pulse ends after two ticks");
		return 11;
	}

	private static int verifyUseKeyTiming() {
		CarpetOperatorBodyController.Keys keys = new CarpetOperatorBodyController.Keys();
		CarpetOperatorBodyController.Frame held = decode(0, 0, 0, 0, OperatorInputPayload.HELD_USE, 0, null);
		CarpetOperatorBodyController.Frame idle = decode(0, 0, 0, 0, 0, 0, null);
		check(keys.useStep(false, held) == CarpetOperatorBodyController.Keys.UseStep.START, "held use starts at once");
		check(keys.useStep(false, idle) == CarpetOperatorBodyController.Keys.UseStep.NONE, "idle use does nothing");
		keys.useStarted();
		for (int tick = 1; tick < CarpetOperatorBodyController.Keys.RIGHT_CLICK_DELAY; tick++) {
			keys.tick();
			check(keys.useStep(false, held) == CarpetOperatorBodyController.Keys.UseStep.NONE,
					"held use waits for vanilla's right-click delay (tick " + tick + ")");
		}
		keys.tick();
		check(keys.useStep(false, held) == CarpetOperatorBodyController.Keys.UseStep.START, "held use repeats every four ticks");
		check(keys.useStep(true, held) == CarpetOperatorBodyController.Keys.UseStep.NONE, "a held key keeps using the item");
		check(keys.useStep(true, idle) == CarpetOperatorBodyController.Keys.UseStep.RELEASE, "releasing the key releases the item");
		check(!keys.useClickAllowed(true) && keys.useClickAllowed(false), "use clicks only start while no item is in use");
		keys.useClicked();
		keys.useStarted();
		check(keys.useStep(true, idle) == CarpetOperatorBodyController.Keys.UseStep.NONE,
				"a click keeps use held until its frame can arrive");
		keys.tick();
		keys.tick();
		check(keys.useStep(true, idle) == CarpetOperatorBodyController.Keys.UseStep.RELEASE,
				"a click without a held frame releases after the pulse");
		check(keys.useStep(false, held) == CarpetOperatorBodyController.Keys.UseStep.NONE,
				"a click and its held frame never use twice inside the delay");
		return 9 + CarpetOperatorBodyController.Keys.RIGHT_CLICK_DELAY - 1;
	}

	private static int verifyServerSelectedSlot() {
		CarpetOperatorBodyController.Keys keys = new CarpetOperatorBodyController.Keys();
		CarpetOperatorBodyController.Frame stale = decode(0, 0, 0, 0, 0, 2, null);
		keys.serverSelectedSlot(2, 2, 2);
		check(keys.slot(stale) == 2, "an unchanged pick keeps the operator's slot");
		keys.serverSelectedSlot(2, 5, 2);
		check(keys.slot(stale) == 5, "a pick that moved the selection overrides the stale frame slot");
		keys.onFrame(stale);
		check(keys.slot(stale) == 5, "frames still showing the old slot do not undo the pick");
		CarpetOperatorBodyController.Frame synced = decode(0, 0, 0, 0, 0, 5, null);
		keys.onFrame(synced);
		check(keys.slot(synced) == 5 && keys.slot(stale) == 2, "the override ends once the client shows the new slot");
		keys.serverSelectedSlot(2, 7, 2);
		CarpetOperatorBodyController.Frame scrolled = decode(0, 0, 0, 0, 0, 3, null);
		keys.onFrame(scrolled);
		check(keys.slot(scrolled) == 3, "an operator scroll after the pick wins");
		CarpetOperatorBodyController.Frame idle = decode(0, 0, 12.0F, -8.0F, ALL_HELD, 6, null);
		keys.serverSelectedSlot(6, 1, 6);
		AgentInputState released = idle.releasedState(keys);
		check(!released.jump() && !released.sneak() && !released.sprint() && !released.attack() && !released.use()
				&& released.forward() == 0.0F && released.strafe() == 0.0F, "the end frame releases every key");
		check(released.yaw() == 12.0F && released.pitch() == -8.0F && released.selectedSlot() == 1,
				"the end frame keeps look and the server-chosen slot");
		return 7;
	}

	private static int verifyActionDecoding() {
		ContainerInput[] contract = {ContainerInput.PICKUP, ContainerInput.QUICK_MOVE, ContainerInput.SWAP,
				ContainerInput.CLONE, ContainerInput.THROW, ContainerInput.QUICK_CRAFT, ContainerInput.PICKUP_ALL};
		check(java.util.Arrays.equals(ContainerInput.values(), contract), "click type ordinals match the payload contract");
		OperatorActionDispatcher.MenuClick click = OperatorActionDispatcher.decodeMenuClick(36, 1, 1).orElseThrow();
		check(click.slot() == 36 && click.button() == 1 && click.input() == ContainerInput.QUICK_MOVE,
				"menu click decodes slot, button and click type");
		check(OperatorActionDispatcher.decodeMenuClick(-999, 0, 0).isPresent(), "outside-window clicks are allowed");
		check(OperatorActionDispatcher.decodeMenuClick(0, 40, 2).orElseThrow().input() == ContainerInput.SWAP,
				"off-hand swap button decodes");
		check(OperatorActionDispatcher.decodeMenuClick(0, -1, 0).isEmpty()
				&& OperatorActionDispatcher.decodeMenuClick(0, 128, 0).isEmpty(), "buttons outside a byte are rejected");
		check(OperatorActionDispatcher.decodeMenuClick(0, 0, -1).isEmpty()
				&& OperatorActionDispatcher.decodeMenuClick(0, 0, contract.length).isEmpty(), "unknown click types are rejected");
		check(OperatorActionDispatcher.decodeMenuClick(Short.MAX_VALUE + 1, 0, 0).isEmpty()
				&& OperatorActionDispatcher.decodeMenuClick(Short.MIN_VALUE - 1, 0, 0).isEmpty(), "slots outside a short are rejected");
		check(OperatorActionDispatcher.decodeMenuButton(3).getAsInt() == 3
				&& OperatorActionDispatcher.decodeMenuButton(-1).isEmpty(), "menu buttons must be non-negative");
		check(OperatorActionDispatcher.decodeIncludeData(1) && !OperatorActionDispatcher.decodeIncludeData(0),
				"pick-block data flag decodes");
		return 9;
	}

	private static int verifyLeasePriority() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController input = new LeasedServerInputController(sink);
		AgentInputState navigation = state(1.0F, false);
		AgentInputState control = state(-1.0F, false);
		AgentInputState interaction = state(0.0F, true);
		AgentInputState operator = state(0.5F, false);
		input.apply(input.acquire(AGENT, InputOwner.NAVIGATION, 100), navigation);
		input.apply(input.acquire(AGENT, InputOwner.DIRECT_CONTROL, 250), control);
		InputLease model = input.acquire(AGENT, InputOwner.INTERACTION, 300);
		input.apply(model, interaction);
		check(input.currentState(AGENT).orElseThrow().equals(interaction), "interaction leads the model owners");
		CarpetOperatorBodyController.OperatorLease takeover = new CarpetOperatorBodyController.OperatorLease(AGENT);
		takeover.apply(input, operator, null);
		check(input.currentState(AGENT).orElseThrow().equals(operator), "the operator beats interaction");
		input.apply(model, state(0.25F, true));
		check(input.currentState(AGENT).orElseThrow().equals(operator) && sink.lastApplied().equals(operator),
				"model input during a takeover never reaches the body");
		check(CarpetOperatorBodyController.OPERATOR_PRIORITY > 300, "operator priority is above every model owner");
		return 4;
	}

	private static int verifyStaleLeaseReacquire() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController input = new LeasedServerInputController(sink);
		CarpetOperatorBodyController.OperatorLease takeover = new CarpetOperatorBodyController.OperatorLease(AGENT);
		int[] acquisitions = {0};
		takeover.apply(input, state(1.0F, false), () -> acquisitions[0]++);
		input.clear(AGENT); // death capture, respawn and stop clear every lease
		check(input.currentState(AGENT).isEmpty(), "a lifecycle clear neutralises the operator too");
		AgentInputState next = state(0.5F, true);
		takeover.apply(input, next, () -> acquisitions[0]++);
		check(acquisitions[0] == 2, "a revoked lease is re-acquired once and re-marked");
		check(input.currentState(AGENT).orElseThrow().equals(next) && sink.lastApplied().equals(next),
				"the re-acquired lease re-applies the latest frame");
		return 3;
	}

	private static int verifyNeutralRelease() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController input = new LeasedServerInputController(sink);
		CarpetOperatorBodyController.OperatorLease takeover = new CarpetOperatorBodyController.OperatorLease(AGENT);
		takeover.apply(input, state(1.0F, true), null);
		AgentInputState neutral = AgentInputState.idle(33.0F, 7.0F, 2);
		takeover.release(input, neutral);
		check(sink.applied.contains(neutral), "ending applies one all-released frame first");
		check(sink.clears == 1 && input.currentState(AGENT).isEmpty(), "with no other owner the body is cleared");
		check(!takeover.held(), "the operator holds no lease after ending");
		takeover.release(input, neutral);
		check(sink.clears == 1, "ending twice is a no-op");

		RecordingSink shared = new RecordingSink();
		LeasedServerInputController sharedInput = new LeasedServerInputController(shared);
		AgentInputState navigation = state(1.0F, false);
		sharedInput.apply(sharedInput.acquire(AGENT, InputOwner.NAVIGATION, 100), navigation);
		CarpetOperatorBodyController.OperatorLease second = new CarpetOperatorBodyController.OperatorLease(AGENT);
		second.apply(sharedInput, state(0.5F, true), null);
		second.release(sharedInput, neutral);
		check(sharedInput.currentState(AGENT).orElseThrow().equals(navigation) && shared.clears == 0,
				"another owner's lease survives the takeover ending");

		RecordingSink revokedSink = new RecordingSink();
		LeasedServerInputController revokedInput = new LeasedServerInputController(revokedSink);
		CarpetOperatorBodyController.OperatorLease revoked = new CarpetOperatorBodyController.OperatorLease(AGENT);
		revoked.apply(revokedInput, state(1.0F, false), null);
		revokedInput.clear(AGENT);
		revoked.release(revokedInput, neutral);
		check(!revoked.held() && revokedInput.currentState(AGENT).isEmpty(), "ending after a lifecycle clear is safe");
		return 6;
	}

	private static int verifyLeaseRenewalOutlivesDeadman() {
		RecordingSink sink = new RecordingSink();
		LeasedServerInputController input = new LeasedServerInputController(sink);
		CarpetOperatorBodyController.OperatorLease takeover = new CarpetOperatorBodyController.OperatorLease(AGENT);
		AgentInputState held = state(1.0F, true);
		int[] acquisitions = {0};
		for (long tick = 0; tick < 3 * LeasedServerInputController.LEASE_TIMEOUT_TICKS; tick++) {
			takeover.apply(input, held, () -> acquisitions[0]++);
			input.tick();
		}
		check(acquisitions[0] == 1 && input.currentState(AGENT).orElseThrow().equals(held),
				"renewing every tick keeps one lease alive past the deadman");
		for (long tick = 0; tick < LeasedServerInputController.LEASE_TIMEOUT_TICKS; tick++) input.tick();
		check(input.currentState(AGENT).isEmpty() && sink.clears == 1, "silence still trips the 40-tick deadman");
		return 2;
	}

	private static CarpetOperatorBodyController.Frame decode(
			float forward, float strafe, float yaw, float pitch, int flags, int slot,
			CarpetOperatorBodyController.Frame previous
	) {
		return CarpetOperatorBodyController.Frame.decode(forward, strafe, yaw, pitch, flags, slot, previous);
	}

	private static AgentInputState state(float forward, boolean attack) {
		return new AgentInputState(forward, 0.0F, false, false, false, attack, false, 0.0F, 0.0F, 0,
				InteractionHand.MAIN_HAND);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class RecordingSink implements InputStateSink {
		private final List<AgentInputState> applied = new ArrayList<>();
		private int clears;

		@Override
		public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) {
			applied.add(state);
		}

		@Override
		public void clear(AgentId agentId, AgentInputState previous) {
			clears++;
		}

		AgentInputState lastApplied() {
			return applied.getLast();
		}
	}
}
