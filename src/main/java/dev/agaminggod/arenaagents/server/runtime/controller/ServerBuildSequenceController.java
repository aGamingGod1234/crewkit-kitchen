package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.server.runtime.ElapsedTimeAccumulator;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Executes model-authored exact placements in their original order. */
public final class ServerBuildSequenceController implements ServerController {
	private static final double NAVIGATION_TOLERANCE = 4.0D;

	private final List<Placement> placements;
	private final ElapsedTimeAccumulator elapsedTime;
	private final long timeoutMs;
	private final PlacementDriver driver;
	private int index;
	private ServerNavigationController navigation;

	public ServerBuildSequenceController(
			List<Placement> placements,
			long startedAt,
			long timeoutMs,
			PlacementDriver driver
	) {
		this.placements = List.copyOf(Objects.requireNonNull(placements, "placements must not be null"));
		if (this.placements.isEmpty() || this.placements.size() > 32) {
			throw new IllegalArgumentException("placements must contain 1 to 32 entries");
		}
		if (timeoutMs <= 0L) throw new IllegalArgumentException("timeoutMs must be positive");
		this.elapsedTime = new ElapsedTimeAccumulator(startedAt);
		this.timeoutMs = timeoutMs;
		this.driver = Objects.requireNonNull(driver, "driver must not be null");
	}

	@Override
	public TickResult tick(ServerPlayer player, long nowEpochMs) {
		long elapsed = elapsedTime.advance(nowEpochMs);
		if (elapsed >= timeoutMs) {
			return failure("BUILD_SEQUENCE_TIMEOUT", "sequence timeout", progress());
		}
		Placement placement = placements.get(index);
		if (!driver.isInRange(player, placement)) {
			if (player == null) return failure("TARGET_TOO_FAR", "navigation requires a player", progress());
			if (navigation == null) {
				navigation = new ServerNavigationController(
						new Vec3(placement.x() + 0.5D, placement.y(), placement.z() + 0.5D),
						NAVIGATION_TOLERANCE,
						true,
						nowEpochMs,
						Math.max(1L, timeoutMs - elapsed)
				);
			}
			TickResult result = navigation.tick(player, nowEpochMs);
			if (result.state() == State.FAILED) return failure(result.reasonCode(), result.message(), progress());
			if (result.state() == State.SUCCEEDED) navigation = null;
			return TickResult.running(progressWithStep(result.progress()));
		}
		if (navigation != null) {
			navigation.cancel(player);
			navigation = null;
		}
		TickResult result = Objects.requireNonNull(
				driver.tick(player, placement, index, nowEpochMs), "placement driver result must not be null");
		if (result.state() == State.FAILED) return failure(result.reasonCode(), result.message(), progressWithStep(result.progress()));
		if (result.state() == State.RUNNING) return TickResult.running(progressWithStep(result.progress()));
		index++;
		if (index == placements.size()) {
			return TickResult.succeeded("BUILD_SEQUENCE_COMPLETED", "completed=" + index);
		}
		return TickResult.running(progress());
	}

	@Override
	public void cancel(ServerPlayer player) {
		try {
			if (navigation != null && player != null) navigation.cancel(player);
		} finally {
			driver.cancel(player);
		}
	}

	private TickResult failure(String reasonCode, String reason, double progress) {
		return TickResult.failed(
				reasonCode,
				"completed=" + index + ", failedIndex=" + index + ", reason=" + reasonCode + ": " + reason,
				progress
		);
	}

	private double progress() {
		return (double) index / placements.size();
	}

	private double progressWithStep(double stepProgress) {
		return Math.min(0.999D, (index + Math.max(0.0D, Math.min(1.0D, stepProgress))) / placements.size());
	}

	public record Placement(int x, int y, int z, Direction face, String itemId, String desiredState) {
		public Placement {
			Objects.requireNonNull(face, "face must not be null");
			Objects.requireNonNull(itemId, "itemId must not be null");
		}
	}

	public interface PlacementDriver {
		boolean isInRange(ServerPlayer player, Placement placement);

		TickResult tick(ServerPlayer player, Placement placement, int index, long nowEpochMs);

		default void cancel(ServerPlayer player) {
		}
	}
}
