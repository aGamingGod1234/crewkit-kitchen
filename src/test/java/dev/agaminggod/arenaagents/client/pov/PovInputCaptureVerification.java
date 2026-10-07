package dev.agaminggod.arenaagents.client.pov;

import dev.agaminggod.arenaagents.client.pov.input.PovInputCapture;
import dev.agaminggod.arenaagents.client.pov.input.PovInputCapture.Click;
import dev.agaminggod.arenaagents.client.pov.input.PovInputCapture.Frame;
import java.util.List;

public final class PovInputCaptureVerification {
	private static int assertions;

	private PovInputCaptureVerification() {
	}

	public static int verify() {
		assertions = 0;
		verifyFlagPacking();
		verifyMovement();
		verifyClickDraining();
		verifySprintRequests();
		verifySlotSelection();
		verifyScrollWrap();
		verifySpectateDiscard();
		verifyReleasedFrame();
		return assertions;
	}

	private static void verifyFlagPacking() {
		assertEquals(0, PovInputCapture.packHeldFlags(false, false, false, false, false), "no held keys pack to zero");
		assertEquals(1, PovInputCapture.packHeldFlags(true, false, false, false, false), "jump is bit 1");
		assertEquals(2, PovInputCapture.packHeldFlags(false, true, false, false, false), "sneak is bit 2");
		assertEquals(4, PovInputCapture.packHeldFlags(false, false, true, false, false), "sprint is bit 4");
		assertEquals(8, PovInputCapture.packHeldFlags(false, false, false, true, false), "attack is bit 8");
		assertEquals(16, PovInputCapture.packHeldFlags(false, false, false, false, true), "use is bit 16");
		assertEquals(31, PovInputCapture.packHeldFlags(true, true, true, true, true), "all held keys pack together");
		PovInputCapture capture = known(0);
		capture.recordMovement(false, false, false, false, true, true, false);
		capture.recordHeld(true, false);
		assertEquals(1 | 2 | 8, capture.nextFrame().heldFlags(), "frame carries movement and mouse holds");
	}

	private static void verifyMovement() {
		PovInputCapture capture = known(0);
		capture.recordMovement(true, false, false, false, false, false, false);
		assertTrue(capture.movementCaptured(), "recorded movement marks the tick as captured");
		Frame forward = capture.nextFrame();
		assertEquals(1.0F, forward.forward(), "forward key gives full forward impulse");
		assertEquals(0.0F, forward.strafe(), "forward key alone gives no strafe");
		assertFalse(capture.movementCaptured(), "a frame consumes the captured flag");
		capture.recordMovement(true, false, true, false, false, false, false);
		Frame diagonal = capture.nextFrame();
		assertNear(0.70710677F, diagonal.forward(), "diagonal forward is normalized like vanilla");
		assertNear(0.70710677F, diagonal.strafe(), "left strafe is positive and normalized");
		capture.recordMovement(true, true, false, true, false, false, false);
		Frame opposed = capture.nextFrame();
		assertEquals(0.0F, opposed.forward(), "opposite keys cancel");
		assertEquals(-1.0F, opposed.strafe(), "right strafe is negative");
	}

	private static void verifyClickDraining() {
		PovInputCapture capture = known(0);
		capture.click(Click.ATTACK);
		capture.click(Click.USE);
		capture.click(Click.DROP_STACK);
		assertEquals(List.of(Click.ATTACK, Click.USE, Click.DROP_STACK), capture.drainClicks(), "clicks drain in order");
		assertTrue(capture.drainClicks().isEmpty(), "a second drain is empty");
		for (int index = 0; index < PovInputCapture.MAX_QUEUED_CLICKS + 10; index++) capture.click(Click.ATTACK);
		assertEquals(PovInputCapture.MAX_QUEUED_CLICKS, capture.drainClicks().size(), "click queue is bounded");
		capture.click(null);
		assertTrue(capture.drainClicks().isEmpty(), "null clicks are ignored");
	}

	private static void verifySprintRequests() {
		PovInputCapture capture = known(0);
		capture.setSprintWindow(PovInputCapture.DEFAULT_SPRINT_WINDOW);
		assertFalse(sprintRequested(capture, true, false, false), "a first forward press only arms the double-tap");
		assertFalse(sprintRequested(capture, false, false, false), "letting go requests nothing");
		assertTrue(sprintRequested(capture, true, false, false), "a second press inside the window requests sprint");
		assertFalse(sprintRequested(capture, true, false, false), "the double-tap request lasts one frame");
		sprintRequested(capture, false, false, false);
		for (int tick = 0; tick < PovInputCapture.DEFAULT_SPRINT_WINDOW; tick++) sprintRequested(capture, false, false, false);
		assertFalse(sprintRequested(capture, true, false, false), "a press after the window only re-arms it");
		sprintRequested(capture, false, false, false);
		for (int tick = 0; tick < PovInputCapture.DEFAULT_SPRINT_WINDOW - 3; tick++) sprintRequested(capture, false, false, false);
		assertTrue(sprintRequested(capture, true, false, false), "a press on the last tick of the window still counts");
		PovInputCapture backed = known(0);
		sprintRequested(backed, true, false, false);
		sprintRequested(backed, false, false, false);
		backed.recordMovement(false, true, false, false, false, false, false);
		backed.nextFrame();
		assertFalse(sprintRequested(backed, true, false, false), "the back key disarms a pending double-tap");
		PovInputCapture sneaked = known(0);
		sprintRequested(sneaked, true, false, false);
		sprintRequested(sneaked, false, false, false);
		sprintRequested(sneaked, false, false, true);
		sprintRequested(sneaked, false, false, false);
		assertFalse(sprintRequested(sneaked, true, false, false), "sneaking on the previous tick disarms it like vanilla");
		PovInputCapture crouched = known(0);
		sprintRequested(crouched, true, false, true);
		sprintRequested(crouched, false, false, true);
		assertFalse(sprintRequested(crouched, true, false, true), "presses while sneaking never fire");
		assertTrue(sprintRequested(capture, true, true, false), "the sprint key requests sprint while held");
		assertTrue(sprintRequested(capture, false, true, false), "the server decides if a sprint key without forward counts");
		PovInputCapture disabled = known(0);
		disabled.setSprintWindow(0);
		sprintRequested(disabled, true, false, false);
		sprintRequested(disabled, false, false, false);
		assertFalse(sprintRequested(disabled, true, false, false), "a zero sprint window turns double-tap off");
		PovInputCapture interrupted = known(0);
		sprintRequested(interrupted, true, false, false);
		sprintRequested(interrupted, false, false, false);
		interrupted.discard();
		assertFalse(sprintRequested(interrupted, true, false, false), "spectating forgets a half double-tap");
	}

	private static boolean sprintRequested(PovInputCapture capture, boolean forward, boolean sprintKey, boolean sneak) {
		capture.recordMovement(forward, false, false, false, false, sneak, sprintKey);
		return (capture.nextFrame().heldFlags() & dev.agaminggod.arenaagents.pov.OperatorInputPayload.HELD_SPRINT) != 0;
	}

	private static void verifySlotSelection() {
		PovInputCapture capture = new PovInputCapture();
		assertFalse(capture.slotKnown(), "slot is unknown before the first state");
		assertEquals(-1, capture.selectedSlot(), "no slot is shown before the first state");
		capture.observeServerSlot(9);
		assertFalse(capture.slotKnown(), "out-of-range server slots are ignored");
		capture.observeServerSlot(4);
		assertTrue(capture.slotKnown(), "server slot makes the slot known");
		assertEquals(4, capture.nextFrame().selectedSlot(), "frame echoes the agent's slot");
		capture.selectHotbar(7);
		assertEquals(7, capture.nextFrame().selectedSlot(), "hotbar key selects locally");
		capture.observeServerSlot(4);
		assertEquals(7, capture.selectedSlot(), "the HUD keeps showing a pending choice over a stale server slot");
		assertEquals(7, capture.nextFrame().selectedSlot(), "a stale server slot does not undo a pending choice");
		capture.observeServerSlot(7);
		capture.observeServerSlot(2);
		assertEquals(2, capture.nextFrame().selectedSlot(), "after the echo the server is followed again");
		capture.selectHotbar(5);
		capture.nextFrame();
		for (int tick = 0; tick < PovInputCapture.SLOT_ACK_TICKS; tick++) capture.nextFrame();
		assertEquals(2, capture.nextFrame().selectedSlot(), "an unacknowledged choice falls back to the server slot");
		capture.selectHotbar(12);
		assertEquals(2, capture.nextFrame().selectedSlot(), "invalid hotbar indexes are ignored");
		PovInputCapture fresh = new PovInputCapture();
		fresh.selectHotbar(3);
		assertTrue(fresh.slotKnown(), "a hotbar key makes the slot known without a state");
		assertEquals(3, fresh.nextFrame().selectedSlot(), "the requested slot is sent");
	}

	private static void verifyScrollWrap() {
		PovInputCapture capture = known(0);
		capture.scroll(1);
		assertEquals(8, capture.nextFrame().selectedSlot(), "wheel up from slot 0 wraps to slot 8");
		capture.scroll(-1);
		assertEquals(0, capture.nextFrame().selectedSlot(), "wheel down from slot 8 wraps to slot 0");
		capture.scroll(-3);
		assertEquals(1, capture.nextFrame().selectedSlot(), "one wheel event moves one slot like vanilla");
		capture.scroll(-1);
		capture.scroll(-1);
		capture.scroll(1);
		assertEquals(2, capture.nextFrame().selectedSlot(), "wheel events in one tick add up");
		capture.selectHotbar(6);
		capture.scroll(-1);
		assertEquals(7, capture.nextFrame().selectedSlot(), "scroll after a hotbar key starts from the key");
		capture.scroll(0);
		assertEquals(7, capture.nextFrame().selectedSlot(), "zero scroll changes nothing");
	}

	private static void verifySpectateDiscard() {
		PovInputCapture capture = known(1);
		capture.recordMovement(true, false, false, false, true, false, false);
		capture.recordHeld(true, true);
		capture.click(Click.ATTACK);
		capture.selectHotbar(6);
		capture.scroll(1);
		capture.discard();
		assertTrue(capture.drainClicks().isEmpty(), "spectating drops clicks");
		assertFalse(capture.movementCaptured(), "spectating drops movement");
		Frame frame = capture.nextFrame();
		assertTrue(frame.released(), "spectating leaves nothing held");
		assertEquals(1, frame.selectedSlot(), "spectating keeps following the agent's slot");
	}

	private static void verifyReleasedFrame() {
		PovInputCapture capture = known(3);
		capture.recordMovement(true, false, true, false, true, true, true);
		capture.recordHeld(true, true);
		Frame held = capture.nextFrame();
		assertFalse(held.released(), "a frame with keys down is not released");
		Frame released = capture.releasedFrame();
		assertTrue(released.released(), "released frame has no movement or held keys");
		assertEquals(0, released.heldFlags(), "released frame clears every held flag");
		assertEquals(3, released.selectedSlot(), "released frame keeps the agent's slot");
		capture.reset();
		assertFalse(capture.slotKnown(), "reset forgets the slot for the next session");
		assertEquals(0, capture.releasedFrame().selectedSlot(), "released frame never sends a negative slot");
		capture.skipFrame();
		assertFalse(capture.movementCaptured(), "skipping a frame still ends the tick");
	}

	private static PovInputCapture known(int slot) {
		PovInputCapture capture = new PovInputCapture();
		capture.observeServerSlot(slot);
		return capture;
	}

	private static void assertNear(float expected, float actual, String label) {
		assertTrue(Math.abs(expected - actual) < 1.0E-5F, label + ": expected <" + expected + "> but was <" + actual + ">");
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
		assertions++;
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		assertTrue(expected.equals(actual), label + ": expected <" + expected + "> but was <" + actual + ">");
	}
}
