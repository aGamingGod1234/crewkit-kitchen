package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps generated offline-player identifiers out of the world view for known Arena Agents. */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Inject(
			method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z",
			at = @At("HEAD"),
			cancellable = true
	)
	private void arenaagents$hideAgentName(Avatar avatar, double distance, CallbackInfoReturnable<Boolean> callback) {
		avatar.getProfile().name()
				.filter(AgentControlClient::isAgentPlayer)
				.ifPresent(ignored -> callback.setReturnValue(false));
	}
}
