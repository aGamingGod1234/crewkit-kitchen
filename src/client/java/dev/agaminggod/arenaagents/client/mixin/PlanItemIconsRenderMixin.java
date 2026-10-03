package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.control.PlanItemIcons;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Icon rendering runs after vanilla GUI rendering and cannot consume world submissions. */
@Mixin(GameRenderer.class)
public abstract class PlanItemIconsRenderMixin {
 @Inject(method = "render", at = @At("RETURN"))
 private void arenaagents$planItemIcons(DeltaTracker delta, boolean renderLevel, CallbackInfo callback) {
  PlanItemIcons.renderPending(((GameRenderer) (Object) this).getMinecraft());
 }
}
