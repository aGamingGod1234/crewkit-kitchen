package dev.agaminggod.arenaagents.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.client.pov.PovHudProxy;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While an agent view is active the HUD reads the stand-in player: getCameraPlayer covers hotbar,
 * hearts, food, armor and air; the wraps below cover the reads that go to the operator directly.
 * getPlayerMode needs no wrap here: MultiPlayerGameModePovMixin answers it for every caller.
 */
@Mixin(Gui.class)
abstract class GuiPovMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@ModifyReturnValue(method = "getCameraPlayer", at = @At("RETURN"))
	private Player arenaagents$povCameraPlayer(Player original) {
		RemotePlayer proxy = PovHudProxy.current();
		return proxy == null ? original : proxy;
	}

	@WrapOperation(method = {"extractHotbarAndDecorations", "extractSelectedItemName"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;canHurtPlayer()Z"))
	private boolean arenaagents$povCanHurtPlayer(MultiPlayerGameMode gameMode, Operation<Boolean> original) {
		GameType pov = PovHudProxy.gameMode();
		return pov == null ? original.call(gameMode) : pov.isSurvival();
	}

	@WrapOperation(method = {"extractHotbarAndDecorations", "nextContextualInfoState"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;hasExperience()Z"))
	private boolean arenaagents$povHasExperience(MultiPlayerGameMode gameMode, Operation<Boolean> original) {
		GameType pov = PovHudProxy.gameMode();
		return pov == null ? original.call(gameMode) : pov.isSurvival();
	}

	@WrapOperation(method = "extractHotbarAndDecorations", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;isSpectator()Z"))
	private boolean arenaagents$povSpectator(LocalPlayer player, Operation<Boolean> original) {
		GameType pov = PovHudProxy.gameMode();
		return pov == null ? original.call(player) : pov == GameType.SPECTATOR;
	}

	@ModifyExpressionValue(method = "extractHotbarAndDecorations", at = @At(value = "FIELD",
			target = "Lnet/minecraft/client/player/LocalPlayer;experienceLevel:I", opcode = Opcodes.GETFIELD))
	private int arenaagents$povExperienceLevel(int original) {
		RemotePlayer proxy = PovHudProxy.current();
		return proxy == null ? original : proxy.experienceLevel;
	}

	@WrapOperation(method = "extractEffects", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getActiveEffects()Ljava/util/Collection;"))
	private Collection<MobEffectInstance> arenaagents$povEffects(LocalPlayer player, Operation<Collection<MobEffectInstance>> original) {
		RemotePlayer proxy = PovHudProxy.current();
		return proxy == null ? original.call(player) : proxy.getActiveEffects();
	}

	@WrapOperation(method = {"extractItemHotbar", "extractCrosshair"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getAttackStrengthScale(F)F"))
	private float arenaagents$povAttackStrength(LocalPlayer player, float partialTick, Operation<Float> original) {
		return PovHudProxy.current() == null ? original.call(player, partialTick) : PovClient.attackStrength();
	}

	@WrapOperation(method = "extractCrosshair", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getCurrentItemAttackStrengthDelay()F"))
	private float arenaagents$povAttackDelay(LocalPlayer player, Operation<Float> original) {
		RemotePlayer proxy = PovHudProxy.current();
		return proxy == null ? original.call(player) : proxy.getCurrentItemAttackStrengthDelay();
	}

	@WrapOperation(method = "extractCrosshair", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getActiveItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack arenaagents$povActiveItem(LocalPlayer player, Operation<ItemStack> original) {
		RemotePlayer proxy = PovHudProxy.current();
		return proxy == null ? original.call(player) : proxy.getActiveItem();
	}

	@WrapOperation(method = "extractCrosshair", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/component/AttackRange;isInRange(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/phys/Vec3;)Z"))
	private boolean arenaagents$povAttackRange(AttackRange range, LivingEntity attacker, Vec3 location, Operation<Boolean> original) {
		// Reach is measured from the agent's eyes, which is where the crosshair ray started.
		Entity camera = minecraft.getCameraEntity();
		LivingEntity source = PovHudProxy.current() != null && camera instanceof LivingEntity living ? living : attacker;
		return original.call(range, source, location);
	}

	@WrapOperation(method = "tick()V", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getInventory()Lnet/minecraft/world/entity/player/Inventory;"))
	private Inventory arenaagents$povHeldItemName(LocalPlayer player, Operation<Inventory> original) {
		RemotePlayer proxy = PovHudProxy.current();
		return proxy == null ? original.call(player) : proxy.getInventory();
	}
}
