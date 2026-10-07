package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.agaminggod.arenaagents.client.pov.PovHudProxy;
import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrored screens never reach vanilla container packets; container id 0 would edit the operator's
 * own inventory. During an agent view getPlayerMode reports the agent's mode so third-party HUD gates
 * such as Axiom's creative tool slot follow the viewed agent. Its vanilla callers are cosmetic (HUD,
 * hand gate, block outline, marker particles in ClientLevel, quick-play log, Tutorial.isSurvival, which
 * can only show tutorial toasts) except the F3+F4 switcher, which changes the operator's own body and
 * therefore reads the real field through {@link GameModeSwitcherScreenPovMixin}.
 */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModePovMixin {
	@ModifyReturnValue(method = "getPlayerMode", at = @At("RETURN"))
	private GameType arenaagents$povPlayerMode(GameType original) {
		GameType pov = PovHudProxy.gameMode();
		return pov == null ? original : pov;
	}

	@Inject(method = "handleContainerInput", at = @At("HEAD"), cancellable = true)
	private void arenaagents$relayMirroredClick(int containerId, int slot, int button, ContainerInput input, Player player,
			CallbackInfo callback) {
		if (PovScreens.interceptContainerInput(containerId, slot, button, input)) callback.cancel();
	}

	@Inject(method = "handleInventoryButtonClick", at = @At("HEAD"), cancellable = true)
	private void arenaagents$relayMirroredButton(int containerId, int buttonId, CallbackInfo callback) {
		if (PovScreens.interceptButtonClick(containerId, buttonId)) callback.cancel();
	}

	// Recipe placement and crafter toggles reach ClientCommonPacketListenerPovMixin, which relays or drops them.
}
