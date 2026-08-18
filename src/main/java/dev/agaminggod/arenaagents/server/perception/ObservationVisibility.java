package dev.agaminggod.arenaagents.server.perception;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Human-equivalent visual gating for structured server observations. */
public final class ObservationVisibility {
	private static final double TOUCH_AWARENESS_DISTANCE_SQUARED = 4.0D;
	private static final double MINIMUM_VIEW_DOT = 0.25D;

	private ObservationVisibility() {
	}

	public static boolean canSeeEntity(ServerPlayer observer, Entity entity) {
		Objects.requireNonNull(observer, "observer must not be null");
		Objects.requireNonNull(entity, "entity must not be null");
		Vec3 target = entity.getBoundingBox().getCenter();
		return isWithinViewCone(observer.getEyePosition(), observer.getViewVector(1.0F), target)
				&& observer.hasLineOfSight(entity);
	}

	static boolean canSeeBlock(ServerLevel level, ServerPlayer observer, BlockPos position) {
		Objects.requireNonNull(level, "level must not be null");
		Objects.requireNonNull(observer, "observer must not be null");
		Objects.requireNonNull(position, "position must not be null");
		Vec3 eye = observer.getEyePosition();
		Vec3 target = Vec3.atCenterOf(position);
		if (!isWithinViewCone(eye, observer.getViewVector(1.0F), target)) return false;
		BlockHitResult hit = level.clip(new ClipContext(
				eye,
				target,
				ClipContext.Block.VISUAL,
				ClipContext.Fluid.NONE,
				observer
		));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position);
	}

	static boolean isWithinViewCone(Vec3 eye, Vec3 view, Vec3 target) {
		Objects.requireNonNull(eye, "eye must not be null");
		Objects.requireNonNull(view, "view must not be null");
		Objects.requireNonNull(target, "target must not be null");
		Vec3 offset = target.subtract(eye);
		double distanceSquared = offset.lengthSqr();
		if (distanceSquared <= TOUCH_AWARENESS_DISTANCE_SQUARED) return true;
		if (distanceSquared == 0.0D || view.lengthSqr() == 0.0D) return false;
		return offset.normalize().dot(view.normalize()) >= MINIMUM_VIEW_DOT;
	}
}
