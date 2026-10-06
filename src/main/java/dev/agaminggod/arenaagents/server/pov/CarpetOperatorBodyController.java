package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.pov.OperatorAction;
import dev.agaminggod.arenaagents.pov.OperatorActionPayload;
import dev.agaminggod.arenaagents.pov.OperatorBodyController;
import dev.agaminggod.arenaagents.pov.OperatorBodyControllers;
import dev.agaminggod.arenaagents.pov.OperatorInputPayload;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputRuntime;
import dev.agaminggod.arenaagents.server.runtime.input.AgentInputState;
import dev.agaminggod.arenaagents.server.runtime.input.CarpetInputStateSink;
import dev.agaminggod.arenaagents.server.runtime.input.InputLease;
import dev.agaminggod.arenaagents.server.runtime.input.InputOwner;
import dev.agaminggod.arenaagents.server.runtime.input.LeasedServerInputController;
import java.lang.ref.WeakReference;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives an agent's Carpet body from a human operator during a POV takeover.
 *
 * <p>Held keys ride the shared input lease system at {@link #OPERATOR_PRIORITY}, so model leases lose
 * arbitration without being cancelled and the 40-tick deadman still neutralises the body if frames stop.
 * The use key is not sent through the sink: vanilla tries the main hand then the off hand and repeats every
 * four ticks, which the sink's single-hand use driver cannot express, so {@link OperatorActionDispatcher}
 * runs that loop directly. Entity melee arrives only as explicit clicks; a held attack only mines.
 *
 * <p>The agent player is resolved by id on every call. The weak reference below is only an identity probe to
 * notice a respawned body (vanilla keeps the entity id across respawn); it is never used to act.
 */
public final class CarpetOperatorBodyController implements OperatorBodyController {
	public static final int OPERATOR_PRIORITY = 1000;
	private static final Logger LOGGER = LoggerFactory.getLogger(CarpetOperatorBodyController.class);

	private final MinecraftServer server;
	private final AgentId agentId;
	private final Keys keys = new Keys();
	private final OperatorLease lease;
	private WeakReference<ServerPlayer> boundBody = new WeakReference<>(null);
	private Frame frame;
	private boolean active;
	private int lastInputSequence;

	public static void register() {
		OperatorBodyControllers.install(CarpetOperatorBodyController::new);
	}

	public CarpetOperatorBodyController(MinecraftServer server, AgentId agentId) {
		this.server = Objects.requireNonNull(server, "server must not be null");
		this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
		this.lease = new OperatorLease(agentId);
	}

	@Override
	public void begin(ServerPlayer operator) {
		if (active) return;
		active = true;
		keys.reset();
		frame = null;
		guarded("begin", () -> {
			// Taking the lease with a neutral state stops model-held keys this tick.
			ServerPlayer agent = bind();
			if (agent != null) push(agent);
		});
	}

	@Override
	public void applyFrame(OperatorInputPayload payload) {
		if (!active || payload == null) return;
		Frame next = Frame.decode(payload, frame);
		keys.onFrame(next);
		frame = next;
		lastInputSequence = Math.max(0, payload.sequence());
		guarded("frame", () -> {
			ServerPlayer agent = bind();
			if (agent != null) push(agent);
		});
	}

	@Override
	public void applyAction(ServerPlayer operator, OperatorActionPayload action) {
		if (!active || action == null || action.action() == null) return;
		guarded("action " + action.action(), () -> {
			if (action.action() == OperatorAction.RESPAWN) {
				OperatorActionDispatcher.respawn(server, agentId);
				return;
			}
			ServerPlayer agent = bind();
			if (agent == null) return;
			OperatorActionDispatcher.dispatch(agent, action, keys, frameOf(agent));
			push(agent);
		});
	}

	@Override
	public void tick() {
		if (!active) return;
		guarded("tick", () -> {
			ServerPlayer agent = bind();
			if (agent == null) return;
			keys.tick();
			OperatorActionDispatcher.tickUseKey(agent, keys, frameOf(agent));
			// Re-applying every tick renews the lease well inside the 40-tick deadman.
			push(agent);
		});
	}

	@Override
	public void end() {
		if (!active) return;
		active = false;
		try {
			ServerPlayer agent = livingAgent();
			LeasedServerInputController input = AgentInputRuntime.controller(server);
			lease.release(input, frame == null ? null : frame.releasedState(keys));
			if (agent != null) {
				if (agent.isUsingItem()) agent.releaseUsingItem();
				// stop() also revokes other owners' leases, so only use it when the operator was the last owner.
				if (input.currentState(agentId).isEmpty()) OfflineAgentPlayers.stop(agent);
			}
		} catch (RuntimeException failure) {
			LOGGER.warn("Operator takeover cleanup for agent {} failed", agentId, failure);
		} finally {
			CarpetInputStateSink.setMeleeByClickOnly(agentId, null, false);
			keys.reset();
			boundBody = new WeakReference<>(null);
			frame = null;
		}
	}

	@Override
	public boolean active() {
		return active;
	}

	@Override
	public int lastInputSequence() {
		return lastInputSequence;
	}

	/** Resolves the living body, preparing a new body (respawn) and dropping input while there is none. */
	private ServerPlayer bind() {
		ServerPlayer agent = livingAgent();
		if (agent == null) {
			// Dead or removed: hold no input while vanilla handles death; re-acquire once a body is back.
			lease.release(AgentInputRuntime.controller(server), null);
			boundBody = new WeakReference<>(null);
			return null;
		}
		if (boundBody.get() != agent) {
			// A fresh lease makes the sink apply every held key to the new player object.
			lease.release(AgentInputRuntime.controller(server), null);
			OperatorActionDispatcher.prepareBody(agent);
			keys.newBody();
			boundBody = new WeakReference<>(agent);
		}
		return agent;
	}

	private void push(ServerPlayer agent) {
		Frame current = frameOf(agent);
		AgentInputState state = current.toInputState(keys, agent.isUsingItem(), canSprint(agent));
		lease.apply(AgentInputRuntime.controller(server), state,
				() -> CarpetInputStateSink.setMeleeByClickOnly(agentId, agent.getUUID(), true));
	}

	private Frame frameOf(ServerPlayer agent) {
		if (frame == null) {
			frame = Frame.neutral(agent.getYRot(), agent.getXRot(), agent.getInventory().getSelectedSlot());
		}
		return frame;
	}

	private ServerPlayer livingAgent() {
		try {
			return CodexAgentManager.get(server).findAgentPlayer(agentId)
					.filter(player -> player.isAlive() && !player.isRemoved())
					.orElse(null);
		} catch (AgentDomainException missing) {
			return null;
		}
	}

	private static boolean canSprint(ServerPlayer agent) {
		// Vanilla refuses sprint at 6 food or less unless the player may fly.
		return agent.getFoodData().getFoodLevel() > 6 || agent.getAbilities().mayfly;
	}

	private void guarded(String phase, Runnable work) {
		try {
			work.run();
		} catch (RuntimeException failure) {
			LOGGER.warn("Operator takeover {} for agent {} failed", phase, agentId, failure);
		}
	}

	/** One operator input frame after sanitising; pure so it can be verified without a server. */
	record Frame(
			float forward,
			float strafe,
			float yaw,
			float pitch,
			boolean jump,
			boolean sneak,
			boolean sprint,
			boolean attack,
			boolean use,
			int selectedSlot
	) {
		static Frame neutral(float yaw, float pitch, int selectedSlot) {
			return new Frame(0.0F, 0.0F, look(yaw, 0.0F), pitch(pitch, 0.0F),
					false, false, false, false, false, slot(selectedSlot, 0));
		}

		static Frame decode(OperatorInputPayload payload, Frame previous) {
			return decode(payload.forward(), payload.strafe(), payload.yaw(), payload.pitch(),
					payload.heldFlags(), payload.selectedSlot(), previous);
		}

		/** Defensive even if the payload record already bounds its fields: bad values never reach the body. */
		static Frame decode(float forward, float strafe, float yaw, float pitch, int flags, int selectedSlot, Frame previous) {
			return new Frame(
					axis(forward),
					axis(strafe),
					look(yaw, previous == null ? 0.0F : previous.yaw),
					pitch(pitch, previous == null ? 0.0F : previous.pitch),
					(flags & OperatorInputPayload.HELD_JUMP) != 0,
					(flags & OperatorInputPayload.HELD_SNEAK) != 0,
					(flags & OperatorInputPayload.HELD_SPRINT) != 0,
					(flags & OperatorInputPayload.HELD_ATTACK) != 0,
					(flags & OperatorInputPayload.HELD_USE) != 0,
					slot(selectedSlot, previous == null ? 0 : previous.selectedSlot)
			);
		}

		/**
		 * Held keys for the shared sink. Use is always released here: the dispatcher's use-key loop owns it.
		 * Sneak wins over sprint, sprint needs forward input and food, and using an item pauses mining.
		 */
		AgentInputState toInputState(Keys keys, boolean usingItem, boolean canSprint) {
			boolean sprinting = sprint && !sneak && forward > 0.0F && canSprint;
			boolean attacking = keys.attackHeld(this) && !usingItem;
			return new AgentInputState(forward, strafe, jump, sneak, sprinting, attacking, false,
					yaw, pitch, keys.slot(this), InteractionHand.MAIN_HAND);
		}

		/** All keys released with the look and hotbar slot kept, sent once when the takeover ends. */
		AgentInputState releasedState(Keys keys) {
			return AgentInputState.idle(yaw, pitch, keys.slot(this));
		}

		private static float axis(float value) {
			return Float.isFinite(value) ? Math.clamp(value, -1.0F, 1.0F) : 0.0F;
		}

		private static float look(float value, float fallback) {
			return Float.isFinite(value) ? Mth.wrapDegrees(value) : fallback;
		}

		private static float pitch(float value, float fallback) {
			return Float.isFinite(value) ? Math.clamp(value, -90.0F, 90.0F) : fallback;
		}

		private static int slot(int value, int fallback) {
			return value >= 0 && value <= 8 ? value : fallback;
		}
	}

	/** Vanilla client key timing the server has to reproduce for a body without a real client. */
	static final class Keys {
		/** Minecraft.rightClickDelay after startUseItem: a held use key repeats every four ticks. */
		static final int RIGHT_CLICK_DELAY = 4;
		/** Minecraft.missTime after a survival swing at nothing. */
		static final int MISS_TIME = 10;
		/** Keeps a click shorter than one tick held across a full server tick in either tick phase. */
		static final int CLICK_PULSE_TICKS = 2;

		enum UseStep { NONE, START, RELEASE }

		private int rightClickDelay;
		private int missTicks;
		private int attackPulse;
		private int usePulse;
		private int slotOverride = -1;
		private int staleFrameSlot = -1;

		void tick() {
			if (rightClickDelay > 0) rightClickDelay--;
			if (missTicks > 0) missTicks--;
			if (attackPulse > 0) attackPulse--;
			if (usePulse > 0) usePulse--;
		}

		void onFrame(Frame next) {
			// Vanilla continueAttack(false) clears the miss cooldown as soon as the key is up.
			if (!next.attack()) missTicks = 0;
			// The client caught up with (or moved away from) a server-chosen slot.
			if (slotOverride >= 0 && next.selectedSlot() != staleFrameSlot) slotOverride = -1;
		}

		boolean attackHeld(Frame frame) {
			return (frame.attack() && missTicks == 0) || attackPulse > 0;
		}

		boolean useHeld(Frame frame) {
			return frame.use() || usePulse > 0;
		}

		int slot(Frame frame) {
			return slotOverride >= 0 ? slotOverride : frame.selectedSlot();
		}

		boolean attackClickAllowed(boolean spectator, boolean usingItem) {
			// Vanilla drops attack clicks while an item is in use and during the miss cooldown.
			return !spectator && !usingItem && missTicks == 0;
		}

		boolean useClickAllowed(boolean usingItem) {
			return !usingItem;
		}

		UseStep useStep(boolean usingItem, Frame frame) {
			if (usingItem) return useHeld(frame) ? UseStep.NONE : UseStep.RELEASE;
			return useHeld(frame) && rightClickDelay == 0 ? UseStep.START : UseStep.NONE;
		}

		void missed(boolean creative) {
			if (!creative) missTicks = MISS_TIME;
		}

		void blockClicked() {
			attackPulse = CLICK_PULSE_TICKS;
		}

		void useClicked() {
			usePulse = CLICK_PULSE_TICKS;
		}

		void useStarted() {
			rightClickDelay = RIGHT_CLICK_DELAY;
		}

		/** Pick-block may move the hotbar selection; keep it until a frame shows the client noticed. */
		void serverSelectedSlot(int before, int after, int frameSlot) {
			if (before == after || after < 0 || after > 8) return;
			slotOverride = after;
			staleFrameSlot = frameSlot;
		}

		void newBody() {
			rightClickDelay = 0;
			missTicks = 0;
			attackPulse = 0;
			usePulse = 0;
		}

		void reset() {
			newBody();
			slotOverride = -1;
			staleFrameSlot = -1;
		}
	}

	/** The operator's single lease, re-acquired when a lifecycle clear (death, respawn, stop) revoked it. */
	static final class OperatorLease {
		private final AgentId agentId;
		private InputLease lease;

		OperatorLease(AgentId agentId) {
			this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
		}

		void apply(LeasedServerInputController input, AgentInputState state, Runnable beforeAcquire) {
			if (lease == null) lease = acquire(input, beforeAcquire);
			try {
				input.apply(lease, state);
			} catch (LeasedServerInputController.StaleInputLeaseException revoked) {
				lease = acquire(input, beforeAcquire);
				input.apply(lease, state);
			}
		}

		/** Applies {@code released} (when given) so held keys let go, then gives the lease back. */
		void release(LeasedServerInputController input, AgentInputState released) {
			InputLease held = lease;
			lease = null;
			if (held == null) return;
			try {
				try {
					if (released != null) input.apply(held, released);
				} finally {
					input.release(held);
				}
			} catch (LeasedServerInputController.StaleInputLeaseException alreadyRevoked) {
				// A lifecycle clear already neutralised and removed every lease for this agent.
			}
		}

		boolean held() {
			return lease != null;
		}

		private InputLease acquire(LeasedServerInputController input, Runnable beforeAcquire) {
			if (beforeAcquire != null) beforeAcquire.run();
			return input.acquire(agentId, InputOwner.OPERATOR, OPERATOR_PRIORITY);
		}
	}
}
