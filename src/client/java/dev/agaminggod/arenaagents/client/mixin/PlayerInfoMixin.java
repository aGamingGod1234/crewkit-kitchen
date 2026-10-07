package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.control.AgentClientRoster;
import dev.agaminggod.arenaagents.client.render.AgentPlayerSkins;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.PlayerSkin;
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

	/**
	 * The tab list face reads PlayerInfo's skin lookup, which is built once when the player is added. An agent
	 * that joins before the client roster arrives would keep the default face forever, so resolve it per call,
	 * the same way the in-world body does.
	 */
	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void arenaagents$agentTabSkin(CallbackInfoReturnable<PlayerSkin> callback) {
		AgentPlayerSkins.forProfile(((PlayerInfo) (Object) this).getProfile()).ifPresent(callback::setReturnValue);
	}
}
