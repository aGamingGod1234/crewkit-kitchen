package dev.agaminggod.arenaagents.client.mixin;

import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * handleRespawn points the camera at the new local player; rebind the agent view or end it on a new level.
 * Merchant offers and ghost recipes forwarded from the taken-over agent go to the mirrored screen.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerPovMixin {
	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void arenaagents$keepPovCamera(ClientboundRespawnPacket packet, CallbackInfo callback) {
		PovClient.afterRespawn(Minecraft.getInstance());
	}

	// The agent's offers and ghost recipes name the agent's container, which only the mirrored screen shows.
	@Inject(method = "handleMerchantOffers", at = @At("HEAD"), cancellable = true)
	private void arenaagents$mirrorMerchantOffers(ClientboundMerchantOffersPacket packet, CallbackInfo callback) {
		if (Minecraft.getInstance().isSameThread() && PovScreens.acceptMerchantOffers(packet)) callback.cancel();
	}

	@Inject(method = "handlePlaceRecipe", at = @At("HEAD"), cancellable = true)
	private void arenaagents$mirrorGhostRecipe(ClientboundPlaceGhostRecipePacket packet, CallbackInfo callback) {
		if (Minecraft.getInstance().isSameThread() && PovScreens.acceptGhostRecipe(packet)) callback.cancel();
	}
}
