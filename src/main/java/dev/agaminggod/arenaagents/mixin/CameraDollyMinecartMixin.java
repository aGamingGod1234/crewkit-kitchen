package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.camera.CameraDolly;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A camera mounted on vanilla rails must stay a camera when collected or picked. */
@Mixin(Minecart.class)
abstract class CameraDollyMinecartMixin {
    @Inject(method = "getDropItem", at = @At("HEAD"), cancellable = true)
    private void arenaagents$cameraDrop(CallbackInfoReturnable<Item> result) {
        if (CameraDolly.isCamera((Entity) (Object) this)) result.setReturnValue(CameraDolly.ITEM);
    }

    @Inject(method = "getPickResult", at = @At("HEAD"), cancellable = true)
    private void arenaagents$cameraPick(CallbackInfoReturnable<ItemStack> result) {
        if (CameraDolly.isCamera((Entity) (Object) this)) result.setReturnValue(new ItemStack(CameraDolly.ITEM));
    }
}
