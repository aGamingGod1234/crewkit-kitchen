package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The F3+F4 switcher changes the operator's own body, so it must compare against the operator's real
 * mode, not the viewed agent's one that getPlayerMode reports during a session. Both reads are covered:
 * the default selection in the constructor (before the screen is even set) and the send-or-skip check.
 */
@Mixin(GameModeSwitcherScreen.class)
abstract class GameModeSwitcherScreenPovMixin {
	@ModifyExpressionValue(method = {"getDefaultSelected",
			"switchToHoveredGameMode(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/screens/debug/GameModeSwitcherScreen$GameModeIcon;)V"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"),
			require = 2)
	private static GameType arenaagents$operatorPlayerMode(GameType reported) {
		Object gameMode = Minecraft.getInstance().gameMode;
		return gameMode instanceof MultiPlayerGameModeAccessor real ? real.arenaagents$localPlayerMode() : reported;
	}
}
