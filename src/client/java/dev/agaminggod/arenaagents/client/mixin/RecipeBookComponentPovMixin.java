package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The recipe book counts what can be crafted from the local player's inventory. In a mirrored takeover screen the
 * items are the agent's, held by the mirror's stand-in inventory, so the book counts those instead.
 */
@Mixin(RecipeBookComponent.class)
abstract class RecipeBookComponentPovMixin {
	@WrapOperation(method = "*", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getInventory()Lnet/minecraft/world/entity/player/Inventory;"))
	private Inventory arenaagents$countAgentItems(LocalPlayer player, Operation<Inventory> original) {
		return PovScreens.recipeInventory(original.call(player));
	}
}
