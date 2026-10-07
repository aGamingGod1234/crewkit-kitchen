package dev.agaminggod.arenaagents.mixin;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaving the End through the exit portal for the first time no longer goes through teleport in 26.1: the portal
 * calls showEndCredits, which removes the player and waits for the client to finish the credits and send
 * PERFORM_RESPAWN. Carpet still answers that request from its teleport override, so a fake player stayed removed in
 * the End and the agent manager recorded a death. This sends the request the way a client does after the credits,
 * on the next task pass, so the body returns to its spawn point with everything it carried.
 */
@Mixin(ServerPlayer.class)
abstract class FakePlayerEndCreditsMixin {
	@Inject(method = "showEndCredits", at = @At("TAIL"))
	private void arenaagents$finishCreditsForFakePlayer(CallbackInfo callback) {
		if (!((Object) this instanceof EntityPlayerMPFake fake) || !fake.wonGame) return;
		MinecraftServer server = fake.level().getServer();
		server.schedule(new TickTask(server.getTickCount(), () -> {
			if (fake.wonGame && fake.connection.player == fake) {
				fake.connection.handleClientCommand(new ServerboundClientCommandPacket(
						ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
			}
		}));
	}
}
