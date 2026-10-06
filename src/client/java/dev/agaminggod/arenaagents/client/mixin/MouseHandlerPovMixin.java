package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.agaminggod.arenaagents.client.pov.PovLook;
import dev.agaminggod.arenaagents.client.pov.input.OperatorInputSender;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.ScrollWheelHandler;
import net.minecraft.client.player.LocalPlayer;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
abstract class MouseHandlerPovMixin {
	@WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void arenaagents$turnAgentView(LocalPlayer player, double yaw, double pitch, Operation<Void> original) {
		if (OperatorInputSender.sessionActive()) {
			PovLook.turn(yaw, pitch);
			return;
		}
		original.call(player, yaw, pitch);
	}

	// Wrapping the wheel steps covers every in-world branch (hotbar, spectator menu, flight speed) at once.
	@WrapOperation(method = "onScroll", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/ScrollWheelHandler;onMouseScroll(DD)Lorg/joml/Vector2i;"))
	private Vector2i arenaagents$scrollAgentHotbar(ScrollWheelHandler handler, double horizontal, double vertical,
			Operation<Vector2i> original) {
		Vector2i steps = original.call(handler, horizontal, vertical);
		if (!OperatorInputSender.sessionActive()) return steps;
		OperatorInputSender.scroll(steps.y == 0 ? -steps.x : steps.y);
		// Zero steps make vanilla return before touching the operator's own selection.
		return new Vector2i();
	}
}
