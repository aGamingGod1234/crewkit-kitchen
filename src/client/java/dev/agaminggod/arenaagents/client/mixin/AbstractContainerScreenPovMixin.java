package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Bundle slot actions send ServerboundSelectBundleItemPacket for the operator's own menu; mirrors skip them. */
@Mixin(AbstractContainerScreen.class)
abstract class AbstractContainerScreenPovMixin {
	@WrapWithCondition(method = "slotClicked", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;onMouseClickAction(Lnet/minecraft/world/inventory/Slot;Lnet/minecraft/world/inventory/ContainerInput;)V"))
	private boolean arenaagents$skipMirroredSlotActions(AbstractContainerScreen<?> screen, Slot slot, ContainerInput input) {
		return !PovScreens.isPovScreen(screen);
	}
}
