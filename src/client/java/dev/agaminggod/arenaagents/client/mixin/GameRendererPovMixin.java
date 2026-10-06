package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.agaminggod.arenaagents.client.pov.PovClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * First-person hands always draw the operator's arm and items, so they are hidden in an agent view.
 * Only the hand draw is skipped: renderItemInHand also flushes the level's pending features first.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererPovMixin {
	@WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V"))
	private void arenaagents$hidePovHands(ItemInHandRenderer renderer, float partialTick, PoseStack poseStack,
			SubmitNodeCollector collector, LocalPlayer player, int light, Operation<Void> original) {
		if (!PovClient.hidesHands()) original.call(renderer, partialTick, poseStack, collector, player, light);
	}
}
