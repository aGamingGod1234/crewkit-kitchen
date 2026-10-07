package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.client.pov.input.OperatorInputSender;
import dev.agaminggod.arenaagents.pov.OperatorAction;
import net.minecraft.client.gui.screens.InBedChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leave Bed (and Escape) send STOP_SLEEPING for the local player. During a takeover the sleeping body is the agent,
 * so the request goes to the server as the operator action that relays the same packet into the agent's connection.
 */
@Mixin(InBedChatScreen.class)
abstract class InBedChatScreenPovMixin {
	@Inject(method = "sendWakeUp", at = @At("HEAD"), cancellable = true)
	private void arenaagents$wakeAgent(CallbackInfo callback) {
		if (!PovClient.isTakeover()) return;
		OperatorInputSender.sendAction(OperatorAction.LEAVE_BED, 0, 0, 0);
		callback.cancel();
	}
}
