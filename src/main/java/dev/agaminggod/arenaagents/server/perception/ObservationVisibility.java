package dev.agaminggod.arenaagents.server.perception;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
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
		return frame(observer.level(), observer).canSeeEntity(entity);
	}

	public static boolean canSeeBlock(ServerLevel level, ServerPlayer observer, BlockPos position) {
		Objects.requireNonNull(level, "level must not be null");
		Objects.requireNonNull(observer, "observer must not be null");
		Objects.requireNonNull(position, "position must not be null");
		return frame(level, observer).canSeeBlock(position);
	}

	/** Reuses observer geometry and block visibility within one observation collection. */
	public static Frame frame(ServerLevel level, ServerPlayer observer) {
		return new Frame(level, observer);
	}

	static boolean isWithinViewCone(Vec3 eye, Vec3 view, Vec3 target) {
		Objects.requireNonNull(eye, "eye must not be null");
		Objects.requireNonNull(view, "view must not be null");
		Objects.requireNonNull(target, "target must not be null");
		return isWithinNormalizedViewCone(
				eye,
				view.lengthSqr() == 0.0D ? Vec3.ZERO : view.normalize(),
				target
		);
	}

	private static boolean isWithinNormalizedViewCone(Vec3 eye, Vec3 normalizedView, Vec3 target) {
		Vec3 offset = target.subtract(eye);
		double distanceSquared = offset.lengthSqr();
		if (distanceSquared <= TOUCH_AWARENESS_DISTANCE_SQUARED) return true;
		if (distanceSquared == 0.0D || normalizedView.lengthSqr() == 0.0D) return false;
		return offset.normalize().dot(normalizedView) >= MINIMUM_VIEW_DOT;
	}

	public static final class Frame {
		private final ServerLevel level;
		private final ServerPlayer observer;
		private final Vec3 eye;
		private final Vec3 normalizedView;
		private final Map<Long, Boolean> visibleBlocks = new HashMap<>();

		private Frame(ServerLevel level, ServerPlayer observer) {
			this.level = Objects.requireNonNull(level, "level must not be null");
			this.observer = Objects.requireNonNull(observer, "observer must not be null");
			this.eye = observer.getEyePosition();
			Vec3 view = observer.getViewVector(1.0F);
			this.normalizedView = view.lengthSqr() == 0.0D ? Vec3.ZERO : view.normalize();
		}

		public boolean isEntityWithinView(Entity entity) {
			Objects.requireNonNull(entity, "entity must not be null");
			return !entity.isInvisibleTo(observer)
					&& isWithinNormalizedViewCone(eye, normalizedView, entity.getBoundingBox().getCenter());
		}

		public boolean hasLineOfSight(Entity entity) {
			return observer.hasLineOfSight(Objects.requireNonNull(entity, "entity must not be null"));
		}

		public boolean canSeeEntity(Entity entity) {
			return isEntityWithinView(entity) && hasLineOfSight(entity);
		}

		public boolean canSeeBlock(BlockPos position) {
			Objects.requireNonNull(position, "position must not be null");
			return isBlockWithinView(position) && hasLineOfSight(position);
		}

		public boolean isBlockWithinView(BlockPos position) {
			return isWithinNormalizedViewCone(
					eye, normalizedView, Vec3.atCenterOf(Objects.requireNonNull(position, "position must not be null")));
		}

		public boolean hasLineOfSight(BlockPos position) {
			Objects.requireNonNull(position, "position must not be null");
			return memoizedBlockVisibility(visibleBlocks, position, this::traceBlock);
		}

		private boolean traceBlock(BlockPos position) {
			BlockHitResult hit = level.clip(new ClipContext(
					eye,
					Vec3.atCenterOf(position),
					ClipContext.Block.VISUAL,
					level.getFluidState(position).isEmpty() ? ClipContext.Fluid.NONE : ClipContext.Fluid.ANY,
					observer
			));
			return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position);
		}
	}

	static boolean memoizedBlockVisibility(
			Map<Long, Boolean> visibleBlocks,
			BlockPos position,
			Predicate<BlockPos> trace
	) {
		Objects.requireNonNull(visibleBlocks, "visibleBlocks must not be null");
		Objects.requireNonNull(position, "position must not be null");
		Objects.requireNonNull(trace, "trace must not be null");
		long key = position.asLong();
		Boolean cached = visibleBlocks.get(key);
		if (cached != null || visibleBlocks.containsKey(key)) return Boolean.TRUE.equals(cached);
		boolean visible = trace.test(position);
		visibleBlocks.put(key, visible);
		return visible;
	}
}
