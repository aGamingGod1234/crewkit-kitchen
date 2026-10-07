package dev.agaminggod.arenaagents.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.server.AgentDeathCapture;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.OfflineAgentProfileLookup;
import dev.agaminggod.arenaagents.server.SkitActors;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.OldUsersConverter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityPlayerMPFake.class, remap = false)
abstract class EntityPlayerMPFakeMixin {
	/**
	 * Carpet replaces Entity.kill(ServerLevel), which only /kill calls on players, with a disconnect. For an Arena
	 * body that skipped the death entirely: no death record, no drops, and the manager's missing-player recovery
	 * recreated the body in place. Arena bodies take vanilla's generic_kill damage instead, so the normal death
	 * path (ALLOW_DEATH capture, drops, death message, retained connected death below) runs exactly once.
	 */
	@Inject(method = "kill(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"), cancellable = true, remap = false)
	private void arenaagents$killArenaBodyAsVanillaDeath(ServerLevel level, CallbackInfo callback) {
		EntityPlayerMPFake player = (EntityPlayerMPFake) (Object) this;
		boolean arenaBody = CodexAgentManager.playerDisplayName(player).isPresent()
				|| SkitActors.displayName(player).isPresent();
		switch (AgentDeathCapture.killRoute(arenaBody, player.isDeadOrDying())) {
			case CARPET_DISCONNECT -> { }
			case ALREADY_DEAD -> callback.cancel();
			case VANILLA_DEATH -> {
				callback.cancel();
				// Body of LivingEntity.kill(ServerLevel), which Carpet's override hides from callers.
				player.hurtServer(level, player.damageSources().genericKill(), Float.MAX_VALUE);
			}
		}
	}

	@Redirect(
			method = "createFake",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/players/OldUsersConverter;convertMobOwnerIfNecessary(Lnet/minecraft/server/MinecraftServer;Ljava/lang/String;)Ljava/util/UUID;",
					remap = true
			),
			remap = false
	)
	private static UUID arenaagents$keepRequestedOfflineIdentity(MinecraftServer server, String name) {
		return OfflineAgentProfileLookup.requestedUuid(name)
				.orElseGet(() -> OldUsersConverter.convertMobOwnerIfNecessary(server, name));
	}

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

	@Redirect(
			method = "die",
			at = @At(
					value = "INVOKE",
					target = "Lcarpet/patches/EntityPlayerMPFake;kill(Lnet/minecraft/network/chat/Component;)V",
					remap = false
			),
			remap = false
	)
	private void arenaagents$retainManagedDeath(EntityPlayerMPFake player, Component reason) {
		MinecraftServer server = player.level().getServer();
		if (server != null && (dev.agaminggod.arenaagents.server.SkitActors.retainDeath(player)
				|| CodexAgentManager.get(server).retainConnectedDeath(player))) return;
		player.kill(reason);
	}
}
