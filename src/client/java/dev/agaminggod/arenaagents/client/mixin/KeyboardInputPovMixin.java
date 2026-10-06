package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.pov.input.OperatorInputSender;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** During a POV session the keys belong to the agent: the operator's body sees no movement and sends no input packet. */
@Mixin(KeyboardInput.class)
abstract class KeyboardInputPovMixin extends ClientInput {
	@Inject(method = "tick", at = @At("TAIL"))
	private void arenaagents$capturePovMovement(CallbackInfo callback) {
		if (!OperatorInputSender.sessionActive()) return;
		Input keys = keyPresses;
		OperatorInputSender.recordMovement(keys.forward(), keys.backward(), keys.left(), keys.right(),
				keys.jump(), keys.shift(), keys.sprint());
		keyPresses = Input.EMPTY;
		moveVector = Vec2.ZERO;
	}
}
