package dev.agaminggod.arenaagents.mixin;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * LocalPlayer.aiStep ends creative flight when the body lands, right after moving. That check is client-only, so a
 * Carpet body kept flying along the ground. Same check, same place, for fake players only.
 */
@Mixin(Player.class)
abstract class FakePlayerFlightLandingMixin {
	@Inject(method = "aiStep", at = @At("TAIL"))
	private void arenaagents$landEndsFlight(CallbackInfo callback) {
		if (!((Object) this instanceof EntityPlayerMPFake fake)) return;
		Abilities abilities = fake.getAbilities();
		if (fake.onGround() && abilities.flying && !fake.isSpectator()) {
			abilities.flying = false;
			fake.onUpdateAbilities();
		}
	}
}
