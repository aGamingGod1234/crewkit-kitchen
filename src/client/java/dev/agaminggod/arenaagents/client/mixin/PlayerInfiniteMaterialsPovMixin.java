package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.client.pov.PovHudProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * During a takeover the inventory key, the creative screen and its slot packets ask the local player whether it
 * has infinite materials. The body being driven is the agent, so they get the agent's answer: a creative agent gets
 * the creative inventory, a survival agent the survival one, whatever mode the operator's own body is in.
 */
@Mixin(Player.class)
abstract class PlayerInfiniteMaterialsPovMixin {
	@ModifyReturnValue(method = "hasInfiniteMaterials", at = @At("RETURN"))
	private boolean arenaagents$agentMaterials(boolean original) {
		if (!PovClient.isTakeover() || (Object) this != Minecraft.getInstance().player) return original;
		GameType agentMode = PovHudProxy.gameMode();
		return agentMode == null ? original : agentMode == GameType.CREATIVE;
	}
}
