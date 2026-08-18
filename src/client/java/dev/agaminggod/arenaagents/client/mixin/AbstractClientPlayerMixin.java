package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.render.CodexAgentRenderer;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerMixin {
	@Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
	private void arenaagents$agentSkin(CallbackInfoReturnable<PlayerSkin> callback) {
		AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
		String profileName = player.getGameProfile().name();
		AgentControlAgent agent = AgentControlClient.agentForPlayer(profileName).orElse(null);
		AgentIdentity.SkinIdentity fallback = agent == null
				? AgentIdentity.skinForPlayerName(profileName).orElse(null) : null;
		if (agent == null && fallback == null) return;

		Identifier texture = agent != null
				? CodexAgentRenderer.textureFor(agent.provider(), agent.skinVariant())
				: CodexAgentRenderer.textureFor(fallback.provider(), fallback.variant());
		ClientAsset.Texture body = new ClientAsset.ResourceTexture(texture, texture);
		callback.setReturnValue(new PlayerSkin(body, null, null, PlayerModelType.WIDE, false));
	}

}
