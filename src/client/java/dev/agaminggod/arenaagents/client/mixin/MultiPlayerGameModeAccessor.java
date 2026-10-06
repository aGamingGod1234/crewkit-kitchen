package dev.agaminggod.arenaagents.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The operator's real game mode, unaffected by the agent view substitution in getPlayerMode. */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Accessor("localPlayerMode")
	GameType arenaagents$localPlayerMode();
}
