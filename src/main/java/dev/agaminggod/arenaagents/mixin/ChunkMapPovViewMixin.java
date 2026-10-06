package dev.agaminggod.arenaagents.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.agaminggod.arenaagents.server.pov.PovViewRedirect;
import java.util.ArrayList;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Centres an operator's chunk view on its POV agent. Only the view moves: the body's ticket is registered from
 * {@code SectionPos.of(player)} in {@code updatePlayerStatus}/{@code move}, which stays on the body's real section.
 * {@code tick()} calls {@code updateChunkTracking} for every player each tick, so the view follows the agent across
 * chunk borders without the body moving; vanilla then sends the cache-centre packet and the chunk diff itself.
 */
@Mixin(ChunkMap.class)
abstract class ChunkMapPovViewMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@ModifyExpressionValue(method = "updateChunkTracking(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At(
			value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"))
	private ChunkPos arenaagents$trackAroundPovAnchor(ChunkPos bodyChunk, @Local(argsOnly = true) ServerPlayer player) {
		return PovViewRedirect.chunkAnchor(player, bodyChunk);
	}

	// The list tick() fills with players that changed section; every tracked entity is then re-checked against it.
	@ModifyExpressionValue(method = "tick()V", at = @At(
			value = "INVOKE", target = "Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;"))
	private ArrayList<ServerPlayer> arenaagents$recheckPovViewers(ArrayList<ServerPlayer> movedPlayers) {
		PovViewRedirect.addAnchoredViewers(level.players(), movedPlayers);
		return movedPlayers;
	}
}
