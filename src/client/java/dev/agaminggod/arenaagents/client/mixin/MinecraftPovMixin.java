package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.pov.input.OperatorInputSender;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hotbar, drop, swap, inventory, attack, use and pick clicks go to the agent; chat and command keys stay vanilla. */
@Mixin(Minecraft.class)
abstract class MinecraftPovMixin {
	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void arenaagents$drainPovClicks(CallbackInfo callback) {
		if (OperatorInputSender.sessionActive()) OperatorInputSender.drainKeybinds((Minecraft) (Object) this);
	}

	// hitResult follows the camera, so these would act on the agent's target with the operator's body.
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void arenaagents$noOperatorAttack(CallbackInfoReturnable<Boolean> callback) {
		if (OperatorInputSender.sessionActive()) callback.setReturnValue(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void arenaagents$noOperatorMining(boolean leftClick, CallbackInfo callback) {
		if (OperatorInputSender.sessionActive()) callback.cancel();
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void arenaagents$noOperatorUse(CallbackInfo callback) {
		if (OperatorInputSender.sessionActive()) callback.cancel();
	}

	@Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
	private void arenaagents$noOperatorPick(CallbackInfo callback) {
		if (OperatorInputSender.sessionActive()) callback.cancel();
	}
}
