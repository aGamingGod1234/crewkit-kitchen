package dev.agaminggod.arenaagents.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.server.OfflineAgentProfileLookup;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityPlayerMPFake.class, remap = false)
abstract class EntityPlayerMPFakeMixin {
	@Inject(method = "fetchGameProfile", at = @At("HEAD"), cancellable = true)
	private static void arenaagents$skipRemoteProfileLookup(
			MinecraftServer server,
			UUID uuid,
			CallbackInfoReturnable<CompletableFuture<GameProfile>> callback
	) {
		if (OfflineAgentProfileLookup.shouldBypassRemoteLookup(uuid)) {
			callback.setReturnValue(CompletableFuture.completedFuture(new GameProfile(uuid, "")));
		}
	}
}
