package dev.agaminggod.arenaagents.client.pov.input;

import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.client.pov.PovClientSession;
import dev.agaminggod.arenaagents.client.pov.PovHudProxy;
import dev.agaminggod.arenaagents.client.pov.PovLook;
import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
import dev.agaminggod.arenaagents.pov.OperatorAction;
import dev.agaminggod.arenaagents.pov.OperatorActionPayload;
import dev.agaminggod.arenaagents.pov.OperatorInputPayload;
import dev.agaminggod.arenaagents.pov.OperatorTextPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Streams takeover input once per client tick; spectating captures the same keys and drops them. */
public final class OperatorInputSender {
	private static final PovInputCapture CAPTURE = new PovInputCapture();
	private static boolean registered;
	private static int sequence;
	private static boolean framesSent;
	private static long frameSessionId;
	private static float lastYaw;
	private static float lastPitch;

	private OperatorInputSender() {
	}

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ClientTickEvents.END_CLIENT_TICK.register(OperatorInputSender::tick);
		PovScreens.register();
	}

	public static boolean sessionActive() {
		return PovClient.session().isPresent();
	}

	public static void recordMovement(boolean forward, boolean backward, boolean left, boolean right,
			boolean jump, boolean sneak, boolean sprint) {
		CAPTURE.recordMovement(forward, backward, left, right, jump, sneak, sprint);
	}

	public static void scroll(int amount) {
		CAPTURE.scroll(amount);
	}

	public static void observeServerSlot(int slot) {
		CAPTURE.observeServerSlot(slot);
		// The state payload just wrote the server's slot into the HUD; keep a pending local choice on screen.
		if (PovClient.isTakeover()) PovHudProxy.showSelectedSlot(CAPTURE.selectedSlot());
	}

	/** Runs at the head of Minecraft.handleKeybinds so vanilla finds no clicks to act on. */
	public static void drainKeybinds(Minecraft client) {
		Options options = client.options;
		for (int slot = 0; slot < options.keyHotbarSlots.length; slot++) {
			while (options.keyHotbarSlots[slot].consumeClick()) CAPTURE.selectHotbar(slot);
		}
		while (options.keyDrop.consumeClick()) {
			CAPTURE.click(client.hasControlDown() ? PovInputCapture.Click.DROP_STACK : PovInputCapture.Click.DROP);
		}
		while (options.keySwapOffhand.consumeClick()) CAPTURE.click(PovInputCapture.Click.SWAP_HANDS);
		while (options.keyInventory.consumeClick()) {
			CAPTURE.click(PovInputCapture.Click.OPEN_INVENTORY);
			if (PovClient.isTakeover()) PovScreens.requestInventory();
		}
		while (options.keyAttack.consumeClick()) CAPTURE.click(PovInputCapture.Click.ATTACK);
		while (options.keyUse.consumeClick()) CAPTURE.click(PovInputCapture.Click.USE);
		while (options.keyPickItem.consumeClick()) CAPTURE.click(PovInputCapture.Click.PICK);
	}

	/** One-shot takeover action; spectators never send anything. */
	public static void sendAction(OperatorAction action, int a, int b, int c) {
		PovClientSession session = PovClient.session().filter(PovClientSession::takeover).orElse(null);
		if (session == null) return;
		send(Minecraft.getInstance(), new OperatorActionPayload(session.sessionId(), nextSequence(), action, a, b, c));
	}

	/** Text typed into a vanilla screen for the agent (anvil, sign, book); spectators never send anything. */
	public static void sendText(java.util.function.BiFunction<Long, Integer, OperatorTextPayload> build) {
		PovClientSession session = PovClient.session().filter(PovClientSession::takeover).orElse(null);
		if (session == null) return;
		send(Minecraft.getInstance(), build.apply(session.sessionId(), nextSequence()));
	}

	private static void tick(Minecraft client) {
		PovClientSession session = PovClient.session().orElse(null);
		boolean takeover = session != null && session.takeover();
		if (framesSent && (!takeover || session.sessionId() != frameSessionId)) {
			PovInputCapture.Frame released = CAPTURE.releasedFrame();
			send(client, new OperatorInputPayload(frameSessionId, nextSequence(), 0.0F, 0.0F,
					lastYaw, lastPitch, 0, released.selectedSlot()));
			framesSent = false;
			CAPTURE.reset();
		}
		if (session == null) {
			CAPTURE.reset();
			return;
		}
		if (!takeover) {
			CAPTURE.discard();
			return;
		}
		// The body's chunk can be unloaded client-side while viewing the agent, which stops KeyboardInput.tick.
		if (!CAPTURE.movementCaptured()) pollMovement(client);
		boolean free = client.screen == null;
		CAPTURE.recordHeld(free && client.options.keyAttack.isDown(), free && client.options.keyUse.isDown());
		if (!CAPTURE.slotKnown()) {
			// Never guess the agent's hotbar slot; the first state payload supplies it.
			CAPTURE.skipFrame();
			return;
		}
		CAPTURE.setSprintWindow(client.options.sprintWindow().get());
		PovInputCapture.Frame frame = CAPTURE.nextFrame();
		PovHudProxy.showSelectedSlot(frame.selectedSlot());
		float yaw = PovLook.yaw();
		float pitch = PovLook.pitch();
		long sessionId = session.sessionId();
		int frameSequence = nextSequence();
		if (send(client, new OperatorInputPayload(sessionId, frameSequence, frame.forward(), frame.strafe(),
				yaw, pitch, frame.heldFlags(), frame.selectedSlot()))) {
			PovClient.inputFrameSent(frameSequence);
			framesSent = true;
			frameSessionId = sessionId;
			lastYaw = yaw;
			lastPitch = pitch;
		}
		for (PovInputCapture.Click click : CAPTURE.drainClicks()) {
			send(client, new OperatorActionPayload(sessionId, nextSequence(), action(click), 0, 0, 0));
		}
	}

	private static void pollMovement(Minecraft client) {
		Options options = client.options;
		CAPTURE.recordMovement(options.keyUp.isDown(), options.keyDown.isDown(), options.keyLeft.isDown(),
				options.keyRight.isDown(), options.keyJump.isDown(), options.keyShift.isDown(), options.keySprint.isDown());
	}

	private static OperatorAction action(PovInputCapture.Click click) {
		return switch (click) {
			case ATTACK -> OperatorAction.ATTACK_CLICK;
			case USE -> OperatorAction.USE_CLICK;
			case PICK -> OperatorAction.PICK_BLOCK;
			case DROP -> OperatorAction.DROP_ITEM;
			case DROP_STACK -> OperatorAction.DROP_STACK;
			case SWAP_HANDS -> OperatorAction.SWAP_HANDS;
			case OPEN_INVENTORY -> OperatorAction.OPEN_INVENTORY;
		};
	}

	// One counter for frames and actions, never reset, so it stays increasing within every session
	// (including the release frame of a session that just ended) and orders clicks after their frame.
	private static int nextSequence() {
		return ++sequence;
	}

	private static boolean send(Minecraft client, CustomPacketPayload payload) {
		if (client.getConnection() == null || !ClientPlayNetworking.canSend(payload.type())) return false;
		ClientPlayNetworking.send(payload);
		return true;
	}
}
