package dev.agaminggod.arenaagents.client.mixin;

import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.client.render.AgentPlayerSkins;
import java.util.function.Supplier;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SkinManager.class)
abstract class SkinManagerMixin {
	@Inject(method = "createLookup", at = @At("HEAD"), cancellable = true)
	private void arenaagents$profileSkin(GameProfile profile, boolean requireSecure,
			CallbackInfoReturnable<Supplier<PlayerSkin>> callback) {
		// Facebar reads SkinManager directly instead of AbstractClientPlayer.getSkin.
		// Only verified offline agents/actors use the bundled resource texture here.
		AgentPlayerSkins.forProfile(profile).ifPresent(skin -> callback.setReturnValue(() -> skin));
	}
}
