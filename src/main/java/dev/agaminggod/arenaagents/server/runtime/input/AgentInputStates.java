package dev.agaminggod.arenaagents.server.runtime.input;

import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

public final class AgentInputStates {
	private AgentInputStates() {
	}

	public static AgentInputState lookingAt(
			ServerPlayer player,
			Vec3 target,
			float forward,
			float strafe,
			boolean jump,
			boolean sneak,
			boolean sprint,
			boolean attack,
			boolean use,
			InteractionHand hand
	) {
		Objects.requireNonNull(player, "player must not be null");
		Objects.requireNonNull(target, "target must not be null");
		Vec3 delta = target.subtract(player.getEyePosition());
		double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
		float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, horizontal)), -90.0F, 90.0F);
		return new AgentInputState(
				forward, strafe, jump, sneak, sprint, attack, use,
				yaw, pitch, player.getInventory().getSelectedSlot(), hand
		);
	}
}
