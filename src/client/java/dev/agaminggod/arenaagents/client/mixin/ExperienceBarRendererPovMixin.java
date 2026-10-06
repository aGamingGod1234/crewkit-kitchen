package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.agaminggod.arenaagents.client.pov.PovHudProxy;
import net.minecraft.client.gui.contextualbar.ExperienceBarRenderer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The XP bar reads the operator directly; during an agent view it reads the stand-in instead. */
@Mixin(ExperienceBarRenderer.class)
abstract class ExperienceBarRendererPovMixin {
	@WrapOperation(method = "extractBackground", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getXpNeededForNextLevel()I"))
	private int arenaagents$povXpNeeded(LocalPlayer player, Operation<Integer> original) {
		RemotePlayer proxy = PovHudProxy.current();
		return proxy == null ? original.call(player) : proxy.getXpNeededForNextLevel();
	}

	@ModifyExpressionValue(method = "extractBackground", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/player/LocalPlayer;experienceProgress:F", opcode = Opcodes.GETFIELD))
	private float arenaagents$povXpProgress(float original) {
		RemotePlayer proxy = PovHudProxy.current();
		return proxy == null ? original : proxy.experienceProgress;
	}
}
