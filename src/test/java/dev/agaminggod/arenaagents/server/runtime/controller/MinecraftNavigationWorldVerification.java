package dev.agaminggod.arenaagents.server.runtime.controller;

import dev.agaminggod.arenaagents.client.navigation.WalkabilityView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class MinecraftNavigationWorldVerification {
	private MinecraftNavigationWorldVerification() {
	}

	public static int verify() {
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.STONE.defaultBlockState(), false),
				"full blocks remain safe support");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.STONE_SLAB.defaultBlockState(), false),
				"slabs are real walkable support");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.OAK_STAIRS.defaultBlockState(), false),
				"stairs are real walkable support");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.FARMLAND.defaultBlockState(), false),
				"farmland is real walkable support");
		assertEquals(WalkabilityView.Cell.SAFE_SUPPORT, classify(Blocks.DIRT_PATH.defaultBlockState(), false),
				"dirt paths are real walkable support");

		BlockState closedDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, false);
		BlockState openDoor = closedDoor.setValue(BlockStateProperties.OPEN, true);
		assertEquals(WalkabilityView.Cell.BLOCKED, classify(closedDoor, false),
				"closed doors remain collision barriers");
		assertEquals(WalkabilityView.Cell.CLEAR, classify(openDoor, false),
				"open doors are clear navigation space");

		assertEquals(WalkabilityView.Cell.CLEAR, classify(Blocks.WATER.defaultBlockState(), true),
				"bounded one-block-deep water is crossable");
		assertEquals(WalkabilityView.Cell.HAZARD, classify(Blocks.WATER.defaultBlockState(), false),
				"unbounded water remains hazardous");
		assertEquals(WalkabilityView.Cell.HAZARD, classify(Blocks.LAVA.defaultBlockState(), true),
				"lava never inherits shallow-water traversal");
		assertEquals(WalkabilityView.Cell.HAZARD, classify(Blocks.MAGMA_BLOCK.defaultBlockState(), false),
				"damaging support remains hazardous");
		assertEquals(WalkabilityView.Cell.BLOCKED, classify(Blocks.OAK_FENCE.defaultBlockState(), false),
				"other partial collision such as fences remains blocked");

		assertEquals(0.5D, collisionHeight(Blocks.STONE_SLAB.defaultBlockState(), 0.5D, 0.5D),
				"bottom slabs expose their half-block standing height");
		double lowStairHeight = Double.POSITIVE_INFINITY;
		double highStairHeight = Double.NEGATIVE_INFINITY;
		for (double x : new double[]{0.25D, 0.75D}) {
			for (double z : new double[]{0.25D, 0.75D}) {
				double height = collisionHeight(Blocks.OAK_STAIRS.defaultBlockState(), x, z);
				lowStairHeight = Math.min(lowStairHeight, height);
				highStairHeight = Math.max(highStairHeight, height);
			}
		}
		assertEquals(0.5D, lowStairHeight, "bottom stairs expose their lower collision surface");
		assertEquals(1.0D, highStairHeight, "bottom stairs retain their upper collision surface");
		assertTrue(Double.isNaN(collisionHeight(Blocks.AIR.defaultBlockState(), 0.5D, 0.5D)),
				"empty space never invents support across a hole or cliff");
		assertTrue(Double.isNaN(collisionHeight(Blocks.STONE.defaultBlockState(), 1.0D, 0.5D)),
				"support from the adjacent block cannot complete a waypoint early");
		return 17;
	}

	private static WalkabilityView.Cell classify(BlockState state, boolean boundedShallowWater) {
		return MinecraftNavigationWorld.classifyCell(
				state,
				state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
				boundedShallowWater
		);
	}

	private static double collisionHeight(BlockState state, double localX, double localZ) {
		return MinecraftNavigationWorld.collisionHeightAt(
				state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
				localX,
				localZ
		);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
