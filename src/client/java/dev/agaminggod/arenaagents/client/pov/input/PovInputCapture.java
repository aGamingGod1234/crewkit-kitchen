package dev.agaminggod.arenaagents.client.pov.input;

import dev.agaminggod.arenaagents.pov.OperatorInputPayload;
import java.util.ArrayDeque;
import java.util.List;

/** Operator input captured during one client tick; plain state so it can be verified without Minecraft. */
public final class PovInputCapture {
	public static final int HOTBAR_SIZE = 9;
	public static final int SLOT_ACK_TICKS = 20;
	public static final int MAX_QUEUED_CLICKS = 64;
	/** Vanilla's default Options.sprintWindow: ticks between forward taps that still count as a double-tap. */
	public static final int DEFAULT_SPRINT_WINDOW = 7;

	public enum Click { ATTACK, USE, PICK, DROP, DROP_STACK, SWAP_HANDS, OPEN_INVENTORY }

	public record Frame(float forward, float strafe, int heldFlags, int selectedSlot) {
		public boolean released() {
			return forward == 0.0F && strafe == 0.0F && heldFlags == 0;
		}
	}

	private final ArrayDeque<Click> clicks = new ArrayDeque<>();
	private boolean forwardKey;
	private boolean backwardKey;
	private boolean leftKey;
	private boolean rightKey;
	private boolean jump;
	private boolean sneak;
	private boolean sprint;
	private boolean attackHeld;
	private boolean useHeld;
	private boolean movementCaptured;
	private int serverSlot = -1;
	private int localSlot = -1;
	private int pendingAckTicks;
	private int requestedSlot = -1;
	private int scrollSteps;
	private int sprintWindow = DEFAULT_SPRINT_WINDOW;
	private int sprintTriggerTicks;
	private boolean hadForwardImpulse;
	private boolean hadSneak;

	public void recordMovement(boolean forward, boolean backward, boolean left, boolean right,
			boolean jump, boolean sneak, boolean sprint) {
		this.forwardKey = forward;
		this.backwardKey = backward;
		this.leftKey = left;
		this.rightKey = right;
		this.jump = jump;
		this.sneak = sneak;
		this.sprint = sprint;
		movementCaptured = true;
	}

	public boolean movementCaptured() {
		return movementCaptured;
	}

	/** The operator's own double-tap window option; 0 turns double-tap sprinting off like vanilla. */
	public void setSprintWindow(int ticks) {
		sprintWindow = Math.max(0, ticks);
	}

	public void recordHeld(boolean attack, boolean use) {
		attackHeld = attack;
		useHeld = use;
	}

	public void click(Click click) {
		if (click != null && clicks.size() < MAX_QUEUED_CLICKS) clicks.addLast(click);
	}

	public List<Click> drainClicks() {
		List<Click> drained = List.copyOf(clicks);
		clicks.clear();
		return drained;
	}

	public void selectHotbar(int index) {
		if (index >= 0 && index < HOTBAR_SIZE) {
			requestedSlot = index;
			scrollSteps = 0;
		}
	}

	/** Same step rule as vanilla ScrollWheelHandler.getNextScrollWheelSelection: one slot against the wheel sign. */
	public void scroll(int amount) {
		scrollSteps -= Integer.signum(amount);
	}

	/** The agent's slot from the server; adopted unless a local change is still waiting for its echo. */
	public void observeServerSlot(int slot) {
		if (slot < 0 || slot >= HOTBAR_SIZE) return;
		serverSlot = slot;
		if (pendingAckTicks == 0 || slot == localSlot) {
			localSlot = slot;
			pendingAckTicks = 0;
		}
	}

	/** The hotbar slot the operator should see selected now, or -1 before the server first reports one. */
	public int selectedSlot() {
		return currentSlot();
	}

	public boolean slotKnown() {
		return currentSlot() >= 0 || requestedSlot >= 0;
	}

	public Frame nextFrame() {
		if (requestedSlot >= 0 || scrollSteps != 0) {
			int base = requestedSlot >= 0 ? requestedSlot : Math.max(currentSlot(), 0);
			localSlot = Math.floorMod(base + scrollSteps, HOTBAR_SIZE);
			pendingAckTicks = SLOT_ACK_TICKS;
		} else if (pendingAckTicks > 0 && --pendingAckTicks == 0 && serverSlot >= 0) {
			// The server never echoed the local choice, so it was rejected; follow the agent again.
			localSlot = serverSlot;
		}
		requestedSlot = -1;
		scrollSteps = 0;
		float forward = impulse(forwardKey, backwardKey);
		float strafe = impulse(leftKey, rightKey);
		float length = (float) Math.sqrt(forward * forward + strafe * strafe);
		if (length < 1.0E-4F) {
			forward = 0.0F;
			strafe = 0.0F;
		} else {
			forward /= length;
			strafe /= length;
		}
		movementCaptured = false;
		boolean sprintRequest = sprint || doubleTappedForward(forward > 1.0E-5F);
		return new Frame(forward, strafe, packHeldFlags(jump, sneak, sprintRequest, attackHeld, useHeld),
				Math.max(currentSlot(), 0));
	}

	/**
	 * The key half of vanilla LocalPlayer.aiStep's double-tap: a fresh forward press arms the window, and a
	 * second fresh press inside it requests sprint for one frame. Sneak (as of the previous tick, like vanilla)
	 * and the back key disarm it. The server applies the body half (hunger, blindness, item use, collisions),
	 * so the request rides the sprint flag and the server's sprint latch decides.
	 */
	private boolean doubleTappedForward(boolean forwardImpulse) {
		if (sprintTriggerTicks > 0) sprintTriggerTicks--;
		if (hadSneak || backwardKey) sprintTriggerTicks = 0;
		boolean tapped = false;
		if (forwardImpulse && !sneak && !hadForwardImpulse) {
			if (sprintTriggerTicks > 0) tapped = true;
			else sprintTriggerTicks = sprintWindow;
		}
		hadForwardImpulse = forwardImpulse;
		hadSneak = sneak;
		return tapped;
	}

	/** Ends a tick without a frame (slot not known yet); clicks and slot requests stay queued. */
	public void skipFrame() {
		movementCaptured = false;
	}

	public Frame releasedFrame() {
		return new Frame(0.0F, 0.0F, 0, Math.max(currentSlot(), 0));
	}

	/** Spectating: drop everything the operator pressed but keep following the agent's slot. */
	public void discard() {
		clearKeys();
		localSlot = -1;
		pendingAckTicks = 0;
	}

	public void reset() {
		clearKeys();
		serverSlot = -1;
		localSlot = -1;
		pendingAckTicks = 0;
	}

	public static int packHeldFlags(boolean jump, boolean sneak, boolean sprint, boolean attack, boolean use) {
		return (jump ? OperatorInputPayload.HELD_JUMP : 0)
				| (sneak ? OperatorInputPayload.HELD_SNEAK : 0)
				| (sprint ? OperatorInputPayload.HELD_SPRINT : 0)
				| (attack ? OperatorInputPayload.HELD_ATTACK : 0)
				| (use ? OperatorInputPayload.HELD_USE : 0);
	}

	private static float impulse(boolean positive, boolean negative) {
		return positive == negative ? 0.0F : positive ? 1.0F : -1.0F;
	}

	private int currentSlot() {
		return localSlot >= 0 ? localSlot : serverSlot;
	}

	private void clearKeys() {
		clicks.clear();
		forwardKey = backwardKey = leftKey = rightKey = jump = sneak = sprint = false;
		attackHeld = useHeld = false;
		movementCaptured = false;
		requestedSlot = -1;
		scrollSteps = 0;
		sprintTriggerTicks = 0;
		hadForwardImpulse = false;
		hadSneak = false;
	}
}
