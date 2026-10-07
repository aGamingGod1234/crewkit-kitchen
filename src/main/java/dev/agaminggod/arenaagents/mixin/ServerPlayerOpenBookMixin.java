package dev.agaminggod.arenaagents.mixin;

import dev.agaminggod.arenaagents.server.pov.PovUiForwarder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A taken-over agent opening a book opens the editor or reader on the operator's client, as on its own client. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerOpenBookMixin {
	// TAIL: a written book's components are resolved by then, as the reader expects.
	@Inject(method = "openItemGui", at = @At("TAIL"))
	private void arenaagents$showOperatorBook(ItemStack book, InteractionHand hand, CallbackInfo callback) {
		PovUiForwarder.bookOpened((ServerPlayer) (Object) this, book, hand);
	}
}
