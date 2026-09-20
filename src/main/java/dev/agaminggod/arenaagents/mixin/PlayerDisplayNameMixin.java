package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.server.SkitActors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla chat composes player display names from getName, not the entity custom name. */
@Mixin(Player.class)
abstract class PlayerDisplayNameMixin {
	@Inject(method = "getName", at = @At("HEAD"), cancellable = true)
	private void arenaagents$directorLabel(CallbackInfoReturnable<Component> callback) {
		if ((Object) this instanceof ServerPlayer player) {
			SkitActors.displayName(player).ifPresent(name -> callback.setReturnValue(Component.literal(name)));
		}
	}
}
