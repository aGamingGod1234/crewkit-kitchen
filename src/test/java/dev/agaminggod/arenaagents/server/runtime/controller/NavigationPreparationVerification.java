package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.*;
import dev.agaminggod.arenaagents.world.WorldMutationRevisions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Real preparation/state code with deterministic terrain and budgets; no live player physics. */
public final class NavigationPreparationVerification {
	public static int verify() {
		verifyEndpointComposition();
		verifyOrderedResumption();
		verifyRegionSupport();
		verifySharedExhaustion();
		verifyCancellation();
		return 19 + verifyEndpointConvergence() + verifyFractionalPointSupport() + verifyLocalRevisionScopes();
	}

	private static int verifyFractionalPointSupport() {
		WalkabilityView floor = p -> p.y() == 63 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		GridPosition feet = new GridPosition(0, 64, 0);
		Vec3 destination = new Vec3(0.5D, 63.5D, 0.5D);
		double support = 63D + MinecraftNavigationWorld.collisionHeightAt(Shapes.box(0, 0, 0, 1, 0.5D, 1), 0.5D, 0.5D);
		for (Vec3 actual : List.of(destination, destination.add(1D, 0D, 0D))) {
			ServerNavigationController.Preparation preparation = new ServerNavigationController.Preparation(
					new GridPosition((int) Math.floor(actual.x), 63, 0), destination, 0.2D, null, actual);
			check(preparation.advance(floor, p -> support, () -> true), "fractional point preparation finishes");
			check(preparation.goals.equals(List.of(feet)) && !preparation.destinationHasNoSupport,
					"exact and approaching slab destinations use actual collision height within tight radius");
		}
		check(!ServerNavigationController.approachableEndpoint(feet, new Vec3(0.65D, 63.65D, 0.5D), 0.2D, support, destination),
				"combined horizontal and vertical error cannot exceed the radius after conservative pruning");
		check(!ServerNavigationController.approachableEndpoint(feet, destination, 0.2D, Double.NaN, destination),
				"missing collision support remains rejected");
		return 6;
	}

	private static int verifyLocalRevisionScopes() {
		WorldMutationRevisions revisions = new WorldMutationRevisions();
		MinecraftNavigationWorld.SampledRevisions local = new MinecraftNavigationWorld.SampledRevisions(
				(center, radius) -> revisions.revision(center.getX(), center.getZ(), radius));
		local.sample(128, 128);
		long wide = revisions.revision(128, 128, 257);
		revisions.recordMutation(240, 240);
		check(local.isCurrent(), "distant same-region changes preserve local search facts");
		check(revisions.revision(128, 128, 257) > wide, "wide perception still observes the distant change");
		revisions.recordMutation(127, 127);
		check(!local.isCurrent(), "neighboring collision reach invalidates across a chunk boundary");
		MinecraftNavigationWorld.SampledRevisions negative = new MinecraftNavigationWorld.SampledRevisions(
				(center, radius) -> revisions.revision(center.getX(), center.getZ(), radius));
		negative.sample(-16, -16);
		revisions.recordMutation(-17, -17);
		check(!negative.isCurrent(), "negative chunk boundaries preserve collision invalidation");
		MinecraftNavigationWorld.SampledRevisions evicted = new MinecraftNavigationWorld.SampledRevisions(
				(center, radius) -> revisions.revision(center.getX(), center.getZ(), radius));
		evicted.sample(0, 0);
		for (int i = 1; i <= 300; i++) revisions.revision(i * 2048, 0, 0);
		check(!evicted.isCurrent(), "eviction never revives stale local terrain");
		return 5;
	}

	private static void verifyEndpointComposition() {
		Vec3 requested = new Vec3(2.5D, 64.0D, 0.5D);
		ServerNavigationController controller = new ServerNavigationController(requested, 1D, false, 0L, 1_000L);
		GridPosition adjusted = new GridPosition(1, 64, 0);
		Vec3 endpoint = new Vec3(1.5D, 64D, 0.5D);
		setField(controller, "resolvedEndpointPosition", adjusted);
		setField(controller, "resolvedEndpointTarget", endpoint);
		check(!controller.withinEndpoint(new Vec3(0.5D, 64D, 0.5D)),
				"two composed radii must not grant success two blocks from a one-block request");
		check(controller.withinEndpoint(endpoint), "exact original-radius boundary remains accepted");
		check(!controller.satisfiesRequestedEndpoint(new Vec3(1.5D, 65D, 0.5D), adjusted, endpoint), "adjusted radius includes vertical displacement");
		ServerNavigationController slab = new ServerNavigationController(requested, 0.2D, false, 0L, 1_000L);
		check(slab.satisfiesRequestedEndpoint(new Vec3(2.5D, 63.5D, 0.5D), new GridPosition(2, 64, 0),
				new Vec3(2.5D, 63.5D, 0.5D)), "exact-cell collision surface semantics remain explicit");
	}

	private static int verifyEndpointConvergence() {
		Vec3 requested = new Vec3(-71.5D, 64D, -282.5D);
		GridPosition endpoint = new GridPosition(-71, 64, -283);
		Vec3 captured = new Vec3(-70.44505777684265D, 64D, -282.3891719247275D);
		ServerNavigationController controller = new ServerNavigationController(requested, 1D, true, 0L, 30_000L);
		setField(controller, "resolvedEndpointPosition", endpoint);
		setField(controller, "resolvedEndpointTarget", new Vec3(-70.5D, 64D, -282.5D));
		PathNode node = new PathNode(endpoint, TraversalType.WALK);
		check(!controller.reachedTarget(captured, node, true), "captured endpoint-near position must continue driving until original radius is reached");
		check(controller.reachedTarget(captured, node, false), "intermediate waypoint advancement keeps its existing tolerance");
		double innerX = MinecraftNavigationWorld.inwardCoordinate(endpoint.x(), requested.x, 0.3D);
		Vec3 innerTarget = new Vec3(innerX, 64D, requested.z);
		check(innerTarget.distanceTo(requested) < 1D && innerX - 0.3D > endpoint.x(), "relocated aim lies inside both the original radius and the supported cell footprint");
		setField(controller, "resolvedEndpointTarget", innerTarget);
		check(!controller.withinEndpoint(captured), "moving the steering target does not enlarge the original radius");
		check(controller.reachedTarget(innerTarget, node, true) && controller.withinEndpoint(innerTarget), "final advancement and success agree inside the reachable region");
		Vec3 verticalRequest = new Vec3(-74.5D, 65D, -280.5D);
		GridPosition ground = new GridPosition(-75, 64, -281);
		Vec3 capturedVertical = new Vec3(-74.49838710470216D, 64D, -280.50390553935966D);
		check(!ServerNavigationController.approachableEndpoint(ground, verticalRequest, 1D, 64D, capturedVertical), "captured tangent sphere has no horizontal standing region and must not enter an endless arrival loop");
		check(ServerNavigationController.approachableEndpoint(ground, verticalRequest, 1D, 64D, new Vec3(-74.5D, 64D, -280.5D)), "an already satisfied exact boundary is retained");
		check(ServerNavigationController.approachableEndpoint(new GridPosition(-74, 65, -281),
				new Vec3(-74.1D, 65D, -280.5D), 1D, 64.5D, capturedVertical), "actual raised partial support provides a nonempty horizontal arrival region");
		check(ServerNavigationController.approachableEndpoint(new GridPosition(-75, 65, -281), verticalRequest, 0.2D, 64.5D, capturedVertical), "exact-cell slab height normalization remains eligible");
		AABB footprint = new AABB(0.1D, 0D, 0.2D, 0.7D, 1D, 0.8D);
		check(MinecraftNavigationWorld.supportsFootprintAt(Shapes.block(), footprint, 1D), "full block supports shifted footprint");
		check(MinecraftNavigationWorld.supportsFootprintAt(Shapes.box(0D, 0D, 0D, 1D, 0.5D, 1D), footprint, 0.5D), "slab target uses the actual top face");
		check(!MinecraftNavigationWorld.supportsFootprintAt(Shapes.or(Shapes.box(0D, 0D, 0D, 0.3D, 1D, 1D),
				Shapes.box(0.5D, 0D, 0D, 1D, 1D, 1D)), footprint, 1D), "a gap under the shifted footprint rejects the target");
		check(MinecraftNavigationWorld.supportsFootprintAt(Shapes.or(Shapes.box(0D, 0D, 0D, 0.4D, 1D, 1D),
				Shapes.box(0.4D, 0D, 0D, 1D, 1D, 1D)), footprint, 1D), "touching support boxes cover the footprint as a union");
		return 13;
	}

	private static void verifyOrderedResumption() {
		Vec3 destination = new Vec3(0.1D, 64.1D, 0.9D);
		GridPosition origin = new GridPosition(0, 64, 0);
		CountingTerrain terrain = new CountingTerrain();
		ServerNavigationController.Preparation preparation = new ServerNavigationController.Preparation(origin, destination, 16D, null);
		List<GridPosition> expected = new ArrayList<>();
		int possibleSurfaces = 0;
		for (int x = -17; x <= 17; x++) for (int z = -17; z <= 17; z++) for (int y = 47; y <= 81; y++) {
			GridPosition candidate = new GridPosition(x, y, z);
			if (candidate.equals(origin) || new Vec3(x + 0.5D, y, z + 0.5D).distanceTo(destination) <= 16D) expected.add(candidate);
			double nearestSurfaceY = Math.max(y - 1D, Math.min(y, destination.y));
			if (candidate.equals(origin) || new Vec3(x + 0.5D, nearestSurfaceY, z + 0.5D).distanceTo(destination) <= 16D) possibleSurfaces++;
		}
		int slices = 0;
		long totalWork = 0;
		boolean done;
		do {
			ServerPathPlanner.TickBudget budget = new ServerPathPlanner.TickBudget(137, 1_000L, () -> 0L);
			done = preparation.advance(terrain, p -> p.y(), budget::tryPrepare);
			totalWork += budget.preparationWork();
			check(++slices < 400, "enumeration must retain its cursor rather than restart");
		} while (!done);
		check(expected.equals(preparation.goals), "sliced preparation preserves exact candidates in enumeration order");
		check(totalWork == 42_876L, "every cube candidate plus one start is charged exactly once");
		check(terrain.traversals == possibleSurfaces + 1, "only geometrically possible support intervals query terrain");
		check(slices > 1, "maximum-tolerance preparation spans bounded slices");
	}

	private static void verifyRegionSupport() {
		AABB region = new AABB(0D, 63.4D, 0D, 1D, 63.6D, 1D);
		ServerNavigationController.Preparation preparation = new ServerNavigationController.Preparation(
				new GridPosition(0, 64, 0), new Vec3(0.5D, 63.5D, 0.5D), 0.2D, region);
		CountingTerrain terrain = new CountingTerrain();
		check(preparation.advance(terrain, p -> p.y() == 64 ? 63.5D : 62.5D, () -> true), "region preparation completes");
		check(preparation.goals.equals(List.of(new GridPosition(0, 64, 0))), "region admits actual slab surface even when feet-grid Y is outside region");
		check(terrain.traversals == 3, "region X/Z pruning avoids impossible columns without pruning support Y");
	}

	private static void verifySharedExhaustion() {
		AtomicLong clock = new AtomicLong();
		ServerPathPlanner.TickBudget timeBudget = new ServerPathPlanner.TickBudget(10, 10L, clock::get);
		clock.set(10L);
		CountingTerrain terrain = new CountingTerrain();
		ServerNavigationController.Preparation pending = new ServerNavigationController.Preparation(
				new GridPosition(0, 64, 0), new Vec3(1.5D, 64D, 0.5D), 1D, null);
		check(!pending.advance(terrain, p -> p.y(), timeBudget::tryPrepare) && terrain.traversals == 0,
				"expired time budget defers before any terrain query");
		ServerPathPlanner.TickBudget exhausted = new ServerPathPlanner.TickBudget(1, 100L, () -> 0L);
		check(exhausted.tryPrepare() && !exhausted.tryPrepare(), "preparation claims aggregate work limit");
		check(!pending.advance(terrain, p -> p.y(), exhausted::tryPrepare) && terrain.traversals == 0,
				"exhausted aggregate work defers another controller without terrain queries");
		WalkabilityView pathTerrain = p -> p.y() == 63 ? WalkabilityView.Cell.SAFE_SUPPORT : WalkabilityView.Cell.CLEAR;
		ServerPathPlanner.PlanningResult result = new ServerPathPlanner().planPath(pathTerrain,
				new GridPosition(0, 64, 0), new GridPosition(1, 64, 0), exhausted);
		check(result.deferred() && result.plan().expandedNodes() == 0, "preparation consumption leaves no fresh expansion budget");
	}

	private static void verifyCancellation() {
		try {
			ServerNavigationController controller = new ServerNavigationController(new Vec3(0.5D, 64D, 0.5D), 1D, false, 0L, 1_000L);
			Field preparation = ServerNavigationController.class.getDeclaredField("preparation");
			Field search = ServerNavigationController.class.getDeclaredField("search");
			preparation.setAccessible(true);
			search.setAccessible(true);
			preparation.set(controller, new ServerNavigationController.Preparation(new GridPosition(0, 64, 0), new Vec3(0.5D, 64D, 0.5D), 1D, null));
			search.set(controller, new LocalPathfinder().beginSearch(new GridPosition(0, 64, 0), Set.of(), new GridPosition(8, 64, 0), 8, Set.of()));
			controller.cancel(null);
			check(preparation.get(controller) == null && search.get(controller) == null, "cancellation discards both retained planning stages");
			GridPosition origin = new GridPosition(0, 64, 0);
			preparation.set(controller, new ServerNavigationController.Preparation(origin, new Vec3(0.5D, 64D, 0.5D), 1D, null));
			search.set(controller, new LocalPathfinder().beginSearch(origin, Set.of(), new GridPosition(8, 64, 0), 8, Set.of()));
			controller.invalidatePlanning(origin, false);
			check(preparation.get(controller) == null && search.get(controller) == null, "terrain invalidation discards both retained planning stages");
			preparation.set(controller, new ServerNavigationController.Preparation(origin, new Vec3(0.5D, 64D, 0.5D), 1D, null));
			setField(controller, "searchOrigin", origin);
			controller.invalidatePlanning(origin.above(), true);
			check(preparation.get(controller) == null, "moving to a different origin discards incomplete preparation");
		} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
	}

	private static void setField(Object target, String name, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
	}

	private static final class CountingTerrain implements WalkabilityView {
		int traversals;
		@Override public Cell cellAt(GridPosition position) { return Cell.CLEAR; }
		@Override public TraversalType traversalAt(GridPosition position) { traversals++; return TraversalType.WALK; }
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
