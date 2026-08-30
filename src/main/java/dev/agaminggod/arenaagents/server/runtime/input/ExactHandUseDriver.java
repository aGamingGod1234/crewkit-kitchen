package dev.agaminggod.arenaagents.server.runtime.input;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.InteractionHand;

/** Runs Carpet-compatible use-key repeats without Carpet's main-then-offhand fallback. */
final class ExactHandUseDriver {
	static final int REPEAT_COOLDOWN_TICKS = 3;

	private final Map<AgentId, UseState> states = new LinkedHashMap<>();

	void start(AgentId agentId, InteractionHand hand, PlayerUseAccess player) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(hand, "hand must not be null");
		Objects.requireNonNull(player, "player must not be null");
		UseState current = states.get(agentId);
		if (current != null && current.hand == hand) return;
		if (player.isUsingItem()) player.releaseUsingItem();
		states.put(agentId, new UseState(hand));
	}

	void tick(AgentId agentId, InteractionHand hand, PlayerUseAccess player) {
		Objects.requireNonNull(player, "player must not be null");
		start(agentId, hand, player);
		UseState state = states.get(agentId);
		if (state.cooldownTicks > 0) {
			state.cooldownTicks--;
			return;
		}
		if (player.isUsingItem()) {
			if (player.usedHand() == hand) return;
			player.releaseUsingItem();
		}
		TargetAttempt target = switch (Objects.requireNonNull(player.target(), "target must not be null")) {
			case BLOCK -> player.useBlock(hand);
			case ENTITY -> player.useEntity(hand);
			case MISS -> TargetAttempt.pass();
		};
		Objects.requireNonNull(target, "target attempt must not be null");
		if (target.consumed()) {
			if (target.swing()) player.swing(hand);
			state.cooldownTicks = REPEAT_COOLDOWN_TICKS;
			return;
		}
		if (player.useItem(hand)) state.cooldownTicks = REPEAT_COOLDOWN_TICKS;
	}

	void stop(AgentId agentId, PlayerUseAccess player) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(player, "player must not be null");
		if (states.remove(agentId) != null && player.isUsingItem()) player.releaseUsingItem();
	}

	void discard(AgentId agentId) {
		states.remove(Objects.requireNonNull(agentId, "agentId must not be null"));
	}

	interface PlayerUseAccess {
		boolean isUsingItem();

		InteractionHand usedHand();

		void releaseUsingItem();

		TargetKind target();

		TargetAttempt useBlock(InteractionHand hand);

		TargetAttempt useEntity(InteractionHand hand);

		boolean useItem(InteractionHand hand);

		void swing(InteractionHand hand);
	}

	enum TargetKind {
		MISS,
		BLOCK,
		ENTITY
	}

	record TargetAttempt(boolean consumed, boolean swing) {
		static TargetAttempt pass() {
			return new TargetAttempt(false, false);
		}

		static TargetAttempt consumed(boolean swing) {
			return new TargetAttempt(true, swing);
		}
	}

	private static final class UseState {
		private final InteractionHand hand;
		private int cooldownTicks;

		private UseState(InteractionHand hand) {
			this.hand = hand;
		}
	}
}
