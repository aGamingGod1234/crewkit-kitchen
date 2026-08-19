package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.render.CodexAgentRenderer;
import dev.agaminggod.arenaagents.agent.AgentIdentity;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
		if (agent != null && !player.getUUID().equals(UUID.nameUUIDFromBytes(
				("OfflinePlayer:" + agent.playerName()).getBytes(StandardCharsets.UTF_8)))) return;
		AgentIdentity.SkinIdentity fallback = agent == null
				? AgentIdentity.skinForPlayerName(profileName).orElse(null) : null;
		if (agent == null && fallback == null) return;

		AgentVisualIdentity.Resolved identity;
		if (agent != null) {
			identity = AgentVisualIdentity.resolve(agent.provider(), agent.model(), agent.skinVariant());
		} else if (fallback.modelFamily().isBlank()) {
			identity = AgentVisualIdentity.resolveProviderFallback(fallback.provider(), fallback.variant());
		} else {
			identity = AgentVisualIdentity.resolveFamily(
					fallback.provider(), fallback.modelFamily(), fallback.variant());
		}
		Identifier texture = CodexAgentRenderer.textureFor(identity);
		ClientAsset.Texture body = new ClientAsset.ResourceTexture(texture, texture);
		callback.setReturnValue(new PlayerSkin(body, null, null, PlayerModelType.WIDE, false));
	}

}
