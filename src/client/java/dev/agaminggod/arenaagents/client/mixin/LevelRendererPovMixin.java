package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.agaminggod.arenaagents.client.pov.PovClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;

/**
 * Vanilla never draws the local player unless the camera is on it ({@code !(entity instanceof LocalPlayer) ||
 * camera.entity() == entity} in extractVisibleEntities): a spectator riding a mob's view is not in the world. During
 * an agent view the operator's own body really is standing in the world, so with the camera on the agent the
 * operator saw no body where it stood. Other players were never affected; this is the operator's own render only.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererPovMixin {
	@WrapOperation(method = "extractVisibleEntities", constant = @Constant(classValue = LocalPlayer.class))
	private boolean arenaagents$drawOperatorBody(Object entity, Operation<Boolean> original) {
		return original.call(entity) && !PovClient.showsOperatorBody(entity);
	}
}
