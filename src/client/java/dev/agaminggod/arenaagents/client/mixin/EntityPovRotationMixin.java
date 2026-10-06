package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.agaminggod.arenaagents.client.pov.PovView;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Camera alignment and crosshair picking read getViewXRot/getViewYRot. For the POV target they return
 * the exact server pose (spectating) or the local predicted look (takeover). Living entities override
 * getViewYRot, see LivingEntityPovRotationMixin; this one covers pitch and the signal-lost marker.
 */
@Mixin(Entity.class)
abstract class EntityPovRotationMixin {
	@ModifyReturnValue(method = "getViewXRot", at = @At("RETURN"))
	private float arenaagents$povViewPitch(float original, @Local(argsOnly = true) float partialTick) {
		return PovView.pitch(this, partialTick, original);
	}

	@ModifyReturnValue(method = "getViewYRot", at = @At("RETURN"))
	private float arenaagents$povViewYaw(float original, @Local(argsOnly = true) float partialTick) {
		return PovView.yaw(this, partialTick, original);
	}
}
