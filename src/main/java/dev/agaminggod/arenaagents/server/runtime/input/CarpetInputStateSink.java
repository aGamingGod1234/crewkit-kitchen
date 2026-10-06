package dev.agaminggod.arenaagents.server.runtime.input;

import carpet.helpers.EntityPlayerActionPack;
import carpet.script.utils.Tracer;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class CarpetInputStateSink implements InputStateSink {
	private static final Map<EntityPlayerActionPack.Action, Boolean> ATTACK_PRESSES = new WeakHashMap<>();
	/** Player UUID to agent: bodies whose entity melee arrives only as explicit operator clicks. */
	private static final Map<UUID, AgentId> CLICK_ONLY_MELEE = new HashMap<>();
	private static final ThreadLocal<AttackTarget> ATTACK_TARGET = new ThreadLocal<>();
	private record AttackTarget(ServerPlayer player, HitResult hit) { }
	private final CodexAgentManager manager;
	private final ExactHandUseDriver useDriver = new ExactHandUseDriver();

	public CarpetInputStateSink(CodexAgentManager manager) {
		this.manager = Objects.requireNonNull(manager, "manager must not be null");
	}

	@Override
	public void apply(AgentId agentId, AgentInputState previous, AgentInputState state) {
		ServerPlayer player = manager.findAgentPlayer(agentId).orElse(null);
		if (player == null) {
			CarpetActionArbitration.unbind(agentId);
			ModelPlayerInputBridge.unbind(agentId);
			useDriver.discard(agentId);
			return;
		}
		ModelPlayerInputBridge.bind(player, agentId, state);
		EntityPlayerActionPack actions = OfflineAgentPlayers.actions(player);
		MinecraftPlayerUseAccess useAccess = new MinecraftPlayerUseAccess(player);
		if (previous != null && previous.use() && (!state.use() || previous.hand() != state.hand())) {
			useDriver.stop(agentId, useAccess);
		}
		applyMovement(actions, player, state);
		applyHeldActions(actions, previous, state);
		if (state.use()) useDriver.start(agentId, state.hand(), useAccess);
		if (state.use() && state.attack()) {
			CarpetActionArbitration.bind(player, agentId, state.hand(), useDriver);
		} else {
			CarpetActionArbitration.unbind(agentId);
		}
	}

	static void applyMovement(EntityPlayerActionPack actions, ServerPlayer player, AgentInputState state) {
		// Carpet's sprint setter releases sneak. Resolve the held-key pair before either physical write.
		boolean sprint = state.sprint() && !state.sneak();
		actions.look(state.yaw(), state.pitch())
				.setForward(state.forward())
				.setStrafing(state.strafe())
				.setSneaking(state.sneak())
				.setSprinting(sprint);
		actions.setSlot(state.selectedSlot() + 1);
		player.setLastClientInput(new Input(state.forward() > 0, state.forward() < 0,
				state.strafe() > 0, state.strafe() < 0, state.jump(), state.sneak(), sprint));
	}

	static void applyHeldActions(EntityPlayerActionPack actions, AgentInputState previous, AgentInputState state) {
		// stopAll also aborts block damage, so release only the key whose state changed.
		if (previous != null && previous.jump() && !state.jump()) actions.start(EntityPlayerActionPack.ActionType.JUMP, null);
		if (previous != null && previous.attack() && !state.attack()) actions.start(EntityPlayerActionPack.ActionType.ATTACK, null);
		if (state.jump() && (previous == null || !previous.jump())) {
			actions.start(EntityPlayerActionPack.ActionType.JUMP, EntityPlayerActionPack.Action.continuous());
		}
		if (state.attack() && (previous == null || !previous.attack())) {
			EntityPlayerActionPack.Action attack = EntityPlayerActionPack.Action.continuous();
			synchronized (ATTACK_PRESSES) { ATTACK_PRESSES.put(attack, true); }
			actions.start(EntityPlayerActionPack.ActionType.ATTACK, attack);
		}
	}

	/** Called inside exact-hand use arbitration, so consuming use still suppresses attacks. */
	public static Boolean tickAttack(ServerPlayer player, EntityPlayerActionPack.Action action, Supplier<Boolean> original) {
		return tickAttack(player, action, original, () -> Tracer.rayTrace(
				player, 1.0F, player.gameMode.isCreative() ? 5.0D : 4.5D, false));
	}

	static Boolean tickAttack(ServerPlayer player, EntityPlayerActionPack.Action action,
			Supplier<Boolean> original, Supplier<HitResult> target) {
		Boolean press;
		synchronized (ATTACK_PRESSES) {
			press = ATTACK_PRESSES.get(action);
			if (press != null) ATTACK_PRESSES.put(action, false);
		}
		if (press == null) return original.get();
		if (player.isSpectator()) return original.get();
		HitResult hit = target.get();
		if (!(hit instanceof EntityHitResult entityHit)) {
			AttackTarget previous = ATTACK_TARGET.get();
			ATTACK_TARGET.set(new AttackTarget(player, hit));
			try {
				return original.get();
			} finally {
				if (previous == null) ATTACK_TARGET.remove();
				else ATTACK_TARGET.set(previous);
			}
		}
		// Continuous Carpet attacks omit entity hits but reset attack strength every tick.
		// A held key mines continuously; melee requires a press (release/repress repeats it).
		// Operator takeover sends each melee click separately, so its held key never hits entities.
		if (!press || meleeByClickOnly(player)
				|| !player.isWithinEntityInteractionRange(entityHit.getEntity(), 0.0D)) return false;
		player.attack(entityHit.getEntity());
		player.swing(InteractionHand.MAIN_HAND);
		player.resetAttackStrengthTicker();
		player.resetLastActionTime();
		return true;
	}

	/**
	 * Marks a body whose entity melee arrives only as explicit clicks (operator takeover), so a held
	 * attack press only mines. Any full input clear drops the mark; the operator re-marks on re-acquire.
	 */
	public static void setMeleeByClickOnly(AgentId agentId, UUID playerId, boolean enabled) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		synchronized (CLICK_ONLY_MELEE) {
			CLICK_ONLY_MELEE.values().removeIf(agentId::equals);
			if (enabled) CLICK_ONLY_MELEE.put(Objects.requireNonNull(playerId, "playerId must not be null"), agentId);
		}
	}

	static boolean meleeByClickOnly(ServerPlayer player) {
		synchronized (CLICK_ONLY_MELEE) {
			return !CLICK_ONLY_MELEE.isEmpty() && CLICK_ONLY_MELEE.containsKey(player.getUUID());
		}
	}

	/** One-shot handoff to Carpet's getTarget, scoped to this original ATTACK invocation. */
	public static HitResult takeAttackTarget(ServerPlayer player) {
		AttackTarget target = ATTACK_TARGET.get();
		if (target == null || target.player() != player) return null;
		ATTACK_TARGET.remove();
		return target.hit();
	}

	@Override
	public void tick(AgentId agentId, AgentInputState state) {
		if (!state.use()) {
			CarpetActionArbitration.unbind(agentId);
			return;
		}
		ServerPlayer player = manager.findAgentPlayer(agentId).orElse(null);
		if (player == null) {
			CarpetActionArbitration.unbind(agentId);
			useDriver.discard(agentId);
			return;
		}
		if (state.attack()) {
			CarpetActionArbitration.bind(player, agentId, state.hand(), useDriver);
			return;
		}
		CarpetActionArbitration.unbind(agentId);
		useDriver.tick(
				agentId,
				state.hand(),
				new MinecraftPlayerUseAccess(player),
				player.level().getServer().getTickCount()
		);
	}

	@Override
	public long acceptedUses(AgentId agentId) { return useDriver.acceptedUses(agentId); }

	@Override
	public void clear(AgentId agentId, AgentInputState previous) {
		setMeleeByClickOnly(agentId, null, false);
		CarpetActionArbitration.unbind(agentId);
		ModelPlayerInputBridge.unbind(agentId);
		ServerPlayer player = manager.findAgentPlayer(agentId).orElse(null);
		if (player == null) {
			useDriver.discard(agentId);
		} else {
			OfflineAgentPlayers.actions(player).stopAll();
			player.setLastClientInput(Input.EMPTY);
			useDriver.stop(agentId, new MinecraftPlayerUseAccess(player));
			useDriver.discard(agentId);
		}
	}

	static final class MinecraftPlayerUseAccess implements ExactHandUseDriver.PlayerUseAccess {
		private final ServerPlayer player;
		private HitResult target;

		MinecraftPlayerUseAccess(ServerPlayer player) {
			this.player = player;
		}

		@Override
		public boolean isUsingItem() {
			return player.isUsingItem();
		}

		@Override
		public InteractionHand usedHand() {
			return player.getUsedItemHand();
		}

		@Override
		public void releaseUsingItem() {
			player.releaseUsingItem();
		}

		@Override
		public ExactHandUseDriver.TargetKind target() {
			double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
			target = Tracer.rayTrace(player, 1.0F, reach, false);
			return switch (target.getType()) {
				case BLOCK -> ExactHandUseDriver.TargetKind.BLOCK;
				case ENTITY -> ExactHandUseDriver.TargetKind.ENTITY;
				case MISS -> ExactHandUseDriver.TargetKind.MISS;
			};
		}

		@Override
		public ExactHandUseDriver.TargetAttempt useBlock(InteractionHand hand) {
			BlockHitResult hit = (BlockHitResult) target;
			player.resetLastActionTime();
			ServerLevel level = player.level();
			BlockPos position = hit.getBlockPos();
			Direction direction = hit.getDirection();
			if (!player.isWithinBlockInteractionRange(position, 0.0D)
					|| position.getY() >= level.getMaxY() - (direction == Direction.UP ? 1 : 0)
					|| !level.mayInteract(player, position)) {
				return ExactHandUseDriver.TargetAttempt.pass();
			}
			InteractionResult result = player.gameMode.useItemOn(
					player, level, player.getItemInHand(hand), hand, hit
			);
			if (result instanceof InteractionResult.Success success) {
				return ExactHandUseDriver.TargetAttempt.consumed(
						success.swingSource() == InteractionResult.SwingSource.SERVER
				);
			}
			return ExactHandUseDriver.TargetAttempt.pass();
		}

		@Override
		public ExactHandUseDriver.TargetAttempt useEntity(InteractionHand hand) {
			EntityHitResult hit = (EntityHitResult) target;
			player.resetLastActionTime();
			Entity target = hit.getEntity();
			if (!player.isWithinEntityInteractionRange(target, 0.0D)) return ExactHandUseDriver.TargetAttempt.pass();
			ItemStack held = player.getItemInHand(hand);
			boolean itemWasEmpty = held.isEmpty();
			boolean emptyItemFrame = target instanceof ItemFrame frame && frame.getItem().isEmpty();
			Vec3 relativeHit = hit.getLocation().subtract(target.getX(), target.getY(), target.getZ());
			if (target.interact(player, hand, relativeHit).consumesAction()) {
				return ExactHandUseDriver.TargetAttempt.consumed(false);
			}
			if (player.interactOn(target, hand, relativeHit).consumesAction()
					&& (!itemWasEmpty || !emptyItemFrame)) {
				return ExactHandUseDriver.TargetAttempt.consumed(false);
			}
			return ExactHandUseDriver.TargetAttempt.pass();
		}

		@Override
		public boolean useItem(InteractionHand hand) {
			return player.gameMode.useItem(
					player, player.level(), player.getItemInHand(hand), hand
			).consumesAction();
		}

		@Override
		public void swing(InteractionHand hand) {
			player.swing(hand);
		}
	}
}
