package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.agaminggod.arenaagents.client.pov.PovClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla lights the first-person hands at the local player's position. In an agent view the hands
 * are the agent's (ItemInHandRendererPovMixin), so they take the light where the agent stands.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererPovMixin {
	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I"))
	private int arenaagents$povHandLight(EntityRenderDispatcher dispatcher, Entity entity, float partialTick, Operation<Integer> original) {
		AbstractClientPlayer agent = PovClient.agentPlayer();
		return original.call(dispatcher, agent == null ? entity : agent, partialTick);
	}
}
