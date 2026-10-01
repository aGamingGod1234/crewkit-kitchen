package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.control.AgentClientRoster;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerInfo.class)
abstract class PlayerInfoMixin {
	@Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
	private void arenaagents$tabLabel(CallbackInfoReturnable<Component> callback) {
		PlayerInfo info = (PlayerInfo) (Object) this;
		AgentClientRoster.displayName(info.getProfile())
				.ifPresent(name -> callback.setReturnValue(Component.literal(name)));
	}
}
