package dev.agaminggod.arenaagents.crewkit;

/**
 * Kitchen anchors, relative to the set origin (north-west floor corner), from docs/crewkit/kitchen-layout.html.
 * The set track places the kitchen at CrewkitAnchors.origin and fills seats; others read positions from here.
 * Coordinates: x east, y up, z south. Camera looks north from z=21.
 */
public final class CrewkitAnchors {
	private CrewkitAnchors() {}

	/** World position of the set origin; set track may make this configurable/persisted. */
	public static volatile net.minecraft.core.BlockPos origin = new net.minecraft.core.BlockPos(0, 100, 0);

	public static final int[] BUDGET = {6, 4, 0};
	public static final int[] LEDGER = {21, 4, 0};
	public static final int[] AGENT = {8, 1, 6};
	public static final int[] SCREEN = {9, 2, 8};
	public static final int[] CRATE = {25, 1, 6};
	public static final int[] PLAYER = {14, 5, 21};

	public static net.minecraft.world.phys.Vec3 at(int[] rel) {
		return new net.minecraft.world.phys.Vec3(origin.getX() + rel[0] + 0.5, origin.getY() + rel[1], origin.getZ() + rel[2] + 0.5);
	}

	/** Seats in brief order: tables A, B, C (4 each) then D, E spares. {x, z, facing: 0=south(faces table at +z),1=north}. */
	public static final double[][] SEATS = {
		{4.5, 11.4, 0}, {7.5, 11.4, 0}, {4.5, 14.6, 1}, {7.5, 14.6, 1},
		{12.5, 11.4, 0}, {15.5, 11.4, 0}, {12.5, 14.6, 1}, {15.5, 14.6, 1},
		{20.5, 11.4, 0}, {23.5, 11.4, 0}, {20.5, 14.6, 1}, {23.5, 14.6, 1},
		{8.5, 15.9, 0}, {11.5, 15.9, 0}, {16.5, 15.9, 0}, {19.5, 15.9, 0},
	};
}
