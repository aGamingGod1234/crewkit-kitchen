package dev.agaminggod.arenaagents.camera;

import net.minecraft.network.syncher.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;

/** A floor-mounted camera with a telescoping tripod and independently aimed head. */
public final class CameraRig extends Entity {
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(CameraRig.class, EntityDataSerializers.FLOAT);
    private Vec3 drive = Vec3.ZERO;
    private int driveTicks;
    public CameraRig(EntityType<?> type, Level level) { super(type, level); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { builder.define(HEIGHT, 1.4f); }
    public float lensHeight() { return entityData.get(HEIGHT); }
    public void lensHeight(float value) { entityData.set(HEIGHT, net.minecraft.util.Mth.clamp(value, 0.7f, 2.5f)); }
    public void aim(float yaw, float pitch) { setYRot(net.minecraft.util.Mth.wrapDegrees(yaw)); setXRot(net.minecraft.util.Mth.clamp(pitch, -80, 80)); }
    public void drive(float yaw, float speed) { drive = Vec3.directionFromRotation(0, yaw).scale(net.minecraft.util.Mth.clamp(speed, -0.15f, 0.15f)); driveTicks = speed == 0 ? 0 : 100; }
    @Override public void tick() {
        super.tick();
        if (level().isClientSide()) return;
        if (driveTicks > 0) driveTicks--; else drive = Vec3.ZERO;
        setDeltaMovement(drive.x, isNoGravity() ? 0 : Math.max(-0.5, getDeltaMovement().y - 0.04), drive.z);
        move(MoverType.SELF, getDeltaMovement());
        if (horizontalCollision) { drive = Vec3.ZERO; driveTicks = 0; }
        if (onGround()) setDeltaMovement(getDeltaMovement().multiply(1, 0, 1));
    }
    @Override public boolean isPickable() { return !isRemoved(); }
    @Override public boolean isPushable() { return false; }
    @Override public ItemStack getPickResult() { return new ItemStack(CameraDolly.ITEM); }
    @Override public boolean hurtServer(net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (!(source.getEntity() instanceof net.minecraft.world.entity.player.Player player) || !player.mayBuild()) return false;
        if (!player.getAbilities().instabuild) spawnAtLocation(level, new ItemStack(CameraDolly.ITEM));
        discard(); return true;
    }
    @Override protected void readAdditionalSaveData(ValueInput input) { float value = input.getFloatOr("LensHeight", 1.4f); lensHeight(Float.isFinite(value) ? value : 1.4f); }
    @Override protected void addAdditionalSaveData(ValueOutput output) { output.putFloat("LensHeight", lensHeight()); }
}
