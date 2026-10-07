package dev.agaminggod.arenaagents.server.runtime.input;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.UseEffects;

/**
 * The movement-key scaling a vanilla client applies before its body moves (LocalPlayer.modifyInput), for Carpet
 * bodies whose input never passes through a client. Carpet only scales by a constant 0.3 while its sneak flag is
 * set, so a fake player kept full speed while eating, drawing a bow or raising a shield, crawled through one-block
 * gaps at walking speed and ignored Swift Sneak. Keyboard axes are normalised like KeyboardInput; analog values
 * shorter than one (model control) keep their length.
 */
public final class VanillaMoveInput {
	/**
	 * LivingEntity.applyInput multiplies by this after the input is set. LocalPlayer overrides applyInput and, for the
	 * body its camera is on, writes modifyInput's result (which already holds the one 0.98) without calling super, so
	 * a real player moves with a single 0.98. Pre-dividing here leaves the Carpet body with that same single 0.98:
	 * measured on a headless server, a walking agent settles at 0.11786 blocks/tick of motion and 4.317 m/s, vanilla's
	 * walking speed; a second 0.98 would give 4.23 m/s.
	 */
	static final float INPUT_FRICTION = 0.98F;

	private VanillaMoveInput() {
	}

	/** Writes the body's strafe/forward impulse from Carpet's raw axes, pre-divided by the server's own 0.98. */
	public static void apply(ServerPlayer player, float forward, float strafing) {
		boolean usingItem = player.isUsingItem() && !player.isPassenger();
		float itemUse = usingItem
				? player.getUseItem().getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT).speedMultiplier()
				: 1.0F;
		boolean slowly = player.isCrouching() || player.isVisuallyCrawling();
		float sneaking = slowly ? (float) player.getAttributeValue(Attributes.SNEAKING_SPEED) : 1.0F;
		float[] modified = modify(strafing, forward, itemUse, sneaking);
		player.xxa = modified[0] / INPUT_FRICTION;
		player.zza = modified[1] / INPUT_FRICTION;
	}

	/** LocalPlayer.modifyInput on {x = strafe, y = forward}; returns the same order. */
	static float[] modify(float strafe, float forward, float itemUseMultiplier, float sneakingMultiplier) {
		float lengthSquared = strafe * strafe + forward * forward;
		if (!(lengthSquared > 0.0F) || !Float.isFinite(lengthSquared)) return new float[]{0.0F, 0.0F};
		float length = (float) Math.sqrt(lengthSquared);
		float scale = length > 1.0F ? 1.0F / length : 1.0F;
		float x = strafe * scale * INPUT_FRICTION * itemUseMultiplier * sneakingMultiplier;
		float y = forward * scale * INPUT_FRICTION * itemUseMultiplier * sneakingMultiplier;
		// modifyInputSpeedForSquareMovement: diagonals reach the unit square's edge, capped at one.
		float scaled = (float) Math.sqrt(x * x + y * y);
		if (scaled <= 0.0F) return new float[]{0.0F, 0.0F};
		float directionX = x / scaled;
		float directionY = y / scaled;
		float absX = Math.abs(directionX);
		float absY = Math.abs(directionY);
		float tan = absY > absX ? absX / absY : absY / absX;
		float modifiedLength = Math.min(scaled * (float) Math.sqrt(1.0F + tan * tan), 1.0F);
		return new float[]{directionX * modifiedLength, directionY * modifiedLength};
	}
}
