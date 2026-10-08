package dev.agaminggod.arenaagents.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces Carpet's stale checkFallDamage override with the vanilla ServerPlayer one.
 *
 * <p>Carpet's override forwards to Entity.doCheckFallDamage, which in 26.1 dispatches back to checkFallDamage,
 * so once FakePlayerFallAuthorityMixin lets Entity.move reach it, it would recurse forever. Entity.move has
 * already updated the supporting block, so calling the vanilla chain directly is all the override needs to do.
 */
@Mixin(value = EntityPlayerMPFake.class, remap = false)
abstract class FakePlayerCheckFallDamageMixin extends ServerPlayer {
	private FakePlayerCheckFallDamageMixin(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation information) {
		super(server, level, profile, information);
	}

	@Inject(method = "checkFallDamage", at = @At("HEAD"), cancellable = true)
	private void arenaagents$useVanillaFallDamage(double ya, boolean onGround, BlockState onState, BlockPos pos, CallbackInfo callback) {
		super.checkFallDamage(ya, onGround, onState, pos);
		callback.cancel();
	}
}
