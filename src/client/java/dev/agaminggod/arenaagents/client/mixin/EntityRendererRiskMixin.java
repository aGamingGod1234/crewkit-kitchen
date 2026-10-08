package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.presentation.RiskOverlayClient;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shows the watched agent's risk view above creatures (players are decorated in {@link AvatarRendererMixin},
 * whose name tag logic runs after this). Presentation only.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererRiskMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
			at = @At("RETURN"))
	private void arenaagents$riskLabel(Entity entity, EntityRenderState state, float partialTick, CallbackInfo callback) {
		if (entity instanceof Avatar || !RiskOverlayClient.has(entity.getId())) return;
		state.nameTag = RiskOverlayClient.decorate(entity.getId(), state.nameTag);
		if (state.nameTagAttachment == null) {
			state.nameTagAttachment = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot());
		}
	}
}
