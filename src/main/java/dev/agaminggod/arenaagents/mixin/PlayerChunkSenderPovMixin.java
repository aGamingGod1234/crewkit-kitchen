package dev.agaminggod.arenaagents.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.agaminggod.arenaagents.server.pov.PovViewRedirect;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sends an operator's pending chunks nearest to its POV agent first, so the agent's surroundings appear first. */
@Mixin(PlayerChunkSender.class)
abstract class PlayerChunkSenderPovMixin {
	@ModifyExpressionValue(method = "sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At(
			value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"))
	private ChunkPos arenaagents$sendNearPovAnchorFirst(ChunkPos bodyChunk, @Local(argsOnly = true) ServerPlayer player) {
		return PovViewRedirect.chunkAnchor(player, bodyChunk);
	}
}
