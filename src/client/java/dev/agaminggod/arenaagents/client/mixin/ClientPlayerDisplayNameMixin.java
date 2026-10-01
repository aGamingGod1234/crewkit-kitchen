package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.control.AgentClientRoster;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Target HUDs read Player.getName rather than the avatar render state's name tag. */
@Mixin(Player.class)
abstract class ClientPlayerDisplayNameMixin {
	@Inject(method = "getName", at = @At("HEAD"), cancellable = true)
	private void arenaagents$clientLabel(CallbackInfoReturnable<Component> callback) {
		if ((Object) this instanceof AbstractClientPlayer player) {
			AgentClientRoster.displayName(player.getGameProfile())
					.ifPresent(name -> callback.setReturnValue(Component.literal(name)));
		}
	}
}
