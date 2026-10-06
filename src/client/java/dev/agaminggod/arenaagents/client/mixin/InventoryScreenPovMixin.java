package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The mirrored agent inventory must not turn into the operator's creative inventory or show the operator's body. */
@Mixin(InventoryScreen.class)
abstract class InventoryScreenPovMixin {
	@WrapOperation(method = {"init", "containerTick"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;hasInfiniteMaterials()Z"))
	private boolean arenaagents$keepMirrorSurvivalLayout(LocalPlayer player, Operation<Boolean> original) {
		return !PovScreens.isPovScreen((Screen) (Object) this) && original.call(player);
	}

	@WrapOperation(method = "extractBackground", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/inventory/InventoryScreen;extractEntityInInventoryFollowsMouse(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIIFFFLnet/minecraft/world/entity/LivingEntity;)V"))
	private void arenaagents$showAgentBody(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int scale,
			float yOffset, float mouseX, float mouseY, LivingEntity entity, Operation<Void> original) {
		LivingEntity shown = PovScreens.displayEntity((Screen) (Object) this, entity);
		if (shown != null) original.call(graphics, x0, y0, x1, y1, scale, yOffset, mouseX, mouseY, shown);
	}
}
