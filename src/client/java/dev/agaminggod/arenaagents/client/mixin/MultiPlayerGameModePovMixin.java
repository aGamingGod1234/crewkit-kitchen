package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrored screens never reach vanilla container packets; container id 0 would edit the operator's own inventory. */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModePovMixin {
	@Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
	private void arenaagents$relayMirroredClick(int containerId, int slot, int button, ContainerInput input, Player player,
			CallbackInfo callback) {
		if (PovScreens.interceptContainerInput(containerId, slot, button, input)) callback.cancel();
	}

	@Inject(method = "handleInventoryButtonClick", at = @At("HEAD"), cancellable = true)
	private void arenaagents$relayMirroredButton(int containerId, int buttonId, CallbackInfo callback) {
		if (PovScreens.interceptButtonClick(containerId, buttonId)) callback.cancel();
	}

	@Inject(method = {"handlePlaceRecipe", "handleSlotStateChanged"}, at = @At("HEAD"), cancellable = true)
	private void arenaagents$dropMirroredMenuPackets(CallbackInfo callback) {
		if (PovScreens.blocksVanillaMenuPackets()) callback.cancel();
	}
}
