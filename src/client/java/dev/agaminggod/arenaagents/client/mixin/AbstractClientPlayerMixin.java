package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.control.AgentControlClient;
import dev.agaminggod.arenaagents.client.render.CodexAgentRenderer;
import dev.agaminggod.arenaagents.control.AgentControlAgent;
import java.util.Locale;
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
		AgentControlAgent agent = AgentControlClient.snapshot()
				.flatMap(snapshot -> snapshot.agents().stream()
						.filter(candidate -> offlinePlayerName(candidate).equals(profileName))
						.findFirst())
				.orElse(null);
		if (agent == null) return;

		int variant = Math.floorMod(agent.agentId().hashCode(), 4);
		Identifier texture = CodexAgentRenderer.textureFor(agent.provider(), variant);
		ClientAsset.Texture body = new ClientAsset.ResourceTexture(texture, texture);
		callback.setReturnValue(new PlayerSkin(body, null, null, PlayerModelType.WIDE, false));
	}

	private static String offlinePlayerName(AgentControlAgent agent) {
		return "AA" + agent.agentId().replace("-", "").substring(0, 14).toUpperCase(Locale.ROOT);
	}
}
