package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.scenario.ScenarioCategory;
import dev.agaminggod.arenaagents.scenario.ScenarioPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic, self-contained arena geometry. Changes are applied incrementally by the runtime.
 */
public final class ScenarioArenaBlueprint {
	private final BlockPos origin;
	private final List<Placement> placements;

	private ScenarioArenaBlueprint(BlockPos origin, List<Placement> placements) {
		this.origin = origin.immutable();
		this.placements = List.copyOf(placements);
	}

	public static ScenarioArenaBlueprint create(ScenarioPreset preset, BlockPos origin) {
		Objects.requireNonNull(preset, "preset must not be null");
		Builder builder = new Builder(origin);
		switch (preset.category()) {
			case SURVIVAL -> survival(builder);
			case BUILDING -> building(builder);
			case PVP -> pvp(builder);
			case PARKOUR -> parkour(builder);
		}
		builder.spectatorDeck();
		return new ScenarioArenaBlueprint(origin, builder.placements);
	}

	public BlockPos origin() {
		return origin;
	}

	public List<Placement> placements() {
		return placements;
	}

	private static void survival(Builder b) {
		b.clear(-64, 64, 1, 24, -64, 64);
		b.fill(-64, 64, -1, -1, -64, 64, Blocks.DIRT);
		b.fill(-64, 64, 0, 0, -64, 64, Blocks.GRASS_BLOCK);
		b.annulus(0, 0, 51, 57, 0, Blocks.COARSE_DIRT);
		b.annulus(0, 0, 51, 57, 1, Blocks.AIR);
		b.wall(-64, 64, -64, 64, 1, 5, Blocks.MOSSY_COBBLESTONE);
		b.fill(-64, 64, 0, 0, -4, 4, Blocks.GRAVEL);
		b.fill(-64, 64, 1, 1, -3, 3, Blocks.WATER);
		for (int bridgeX : new int[]{-38, 0, 38}) {
			b.fill(bridgeX - 3, bridgeX + 3, 1, 1, -5, 5, Blocks.OAK_PLANKS);
			b.lineX(bridgeX - 3, bridgeX + 3, 2, -5, Blocks.OAK_FENCE);
			b.lineX(bridgeX - 3, bridgeX + 3, 2, 5, Blocks.OAK_FENCE);
		}
		for (int x = -52; x <= 52; x += 13) {
			for (int z = -52; z <= 52; z += 17) {
				if (Math.abs(z) < 9 || Math.abs(x) < 10 && Math.abs(z) < 18) continue;
				b.tree(x + Math.floorMod(x + z, 4), z + Math.floorMod(x - z, 3));
			}
		}
		b.ruin(-28, 24, 12, 9);
		b.ruin(25, -30, 10, 12);
		b.fill(-6, 6, 1, 1, 18, 30, Blocks.COBBLESTONE);
		b.fill(-4, 4, 2, 2, 20, 28, Blocks.MOSS_BLOCK);
		b.ring(0, 24, 7, 1, Blocks.COBBLESTONE);
		b.set(0, 1, 24, Blocks.CAMPFIRE);
		for (int i = -2; i <= 2; i++) {
			b.set(47 + i, 1, 42, i % 2 == 0 ? Blocks.IRON_ORE : Blocks.COAL_ORE);
			b.set(-48 + i, 1, -42, i % 2 == 0 ? Blocks.COPPER_ORE : Blocks.COAL_ORE);
		}
		b.fill(-10, 10, 1, 1, -58, -54, Blocks.HAY_BLOCK);
	}

	private static void building(Builder b) {
		b.clear(-48, 48, 1, 32, -48, 48);
		b.fill(-48, 48, -1, -1, -48, 48, Blocks.STONE);
		b.fill(-48, 48, 0, 0, -48, 48, Blocks.SMOOTH_STONE);
		b.wall(-48, 48, -48, 48, 1, 4, Blocks.QUARTZ_BRICKS);
		for (int x = -36; x <= 36; x += 24) {
			for (int z = -36; z <= 36; z += 24) {
				b.plot(x, z, 18);
			}
		}
		b.fill(-8, 8, 1, 1, -8, 8, Blocks.POLISHED_BLACKSTONE);
		b.ring(0, 0, 9, 2, Blocks.GOLD_BLOCK);
		b.fill(-3, 3, 2, 2, -3, 3, Blocks.QUARTZ_BLOCK);
		b.pillar(-45, -45, 10, Blocks.SEA_LANTERN);
		b.pillar(45, -45, 10, Blocks.SEA_LANTERN);
		b.pillar(-45, 45, 10, Blocks.SEA_LANTERN);
		b.pillar(45, 45, 10, Blocks.SEA_LANTERN);
	}

	private static void pvp(Builder b) {
		b.clear(-58, 58, 1, 30, -58, 58);
		b.fill(-58, 58, -2, -1, -58, 58, Blocks.DEEPSLATE);
		b.fill(-58, 58, 0, 0, -58, 58, Blocks.STONE_BRICKS);
		b.wall(-58, 58, -58, 58, 1, 9, Blocks.DEEPSLATE_BRICKS);
		b.ring(0, 0, 18, 1, Blocks.CRACKED_STONE_BRICKS);
		b.ring(0, 0, 19, 1, Blocks.LAVA);
		for (int[] corner : new int[][]{{-35, -35}, {35, -35}, {-35, 35}, {35, 35}}) {
			b.tower(corner[0], corner[1], 7, 15);
		}
		b.fill(-5, 5, 1, 7, -5, 5, Blocks.POLISHED_BLACKSTONE_BRICKS);
		b.fill(-3, 3, 1, 8, -3, 3, Blocks.AIR);
		b.fill(-24, 24, 2, 2, -2, 2, Blocks.DARK_OAK_PLANKS);
		b.fill(-2, 2, 2, 2, -24, 24, Blocks.DARK_OAK_PLANKS);
		for (int i = -48; i <= 48; i += 16) {
			b.fill(i - 2, i + 2, 1, 3, -12, -10, Blocks.COBBLED_DEEPSLATE);
			b.fill(i - 2, i + 2, 1, 3, 10, 12, Blocks.COBBLED_DEEPSLATE);
		}
		b.ring(0, 0, 50, 1, Blocks.RED_NETHER_BRICKS);
	}

	private static void parkour(Builder b) {
		b.clear(-30, 30, 1, 40, -30, 30);
		b.fill(-30, 30, -2, -1, -30, 30, Blocks.DEEPSLATE);
		b.fill(-30, 30, 0, 0, -30, 30, Blocks.POLISHED_DEEPSLATE);
		b.wall(-30, 30, -30, 30, 1, 4, Blocks.TINTED_GLASS);
		b.fill(-24, 24, 1, 1, -28, -26, Blocks.LIGHT_BLUE_CONCRETE);
		ScenarioParkourCourse course = ScenarioParkourCourse.create();
		for (ScenarioParkourCourse.Lane lane : course.lanes()) {
			for (int index = 0; index < lane.platforms().size(); index++) {
				ScenarioParkourCourse.Platform platform = lane.platforms().get(index);
				Block block = index == 0 ? Blocks.LIGHT_BLUE_CONCRETE
						: index % 8 == 0 ? Blocks.GOLD_BLOCK
						: (lane.index() & 1) == 0 ? Blocks.WHITE_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE;
				b.fill(
						platform.x(),
						platform.x() + 1,
						platform.y(),
						platform.y(),
						platform.z(),
						platform.z(),
						block
				);
				if (index > 0 && index % 8 == 0) {
					b.pillar(platform.x(), platform.z(), 1, platform.y() - 1, Blocks.SEA_LANTERN);
				}
			}
		}
		b.fill(-24, 24, 17, 17, 24, 27, Blocks.GOLD_BLOCK);
		b.fill(-2, 2, 18, 18, 25, 27, Blocks.EMERALD_BLOCK);
		b.set(0, 19, 26, Blocks.BEACON);
	}

	public record Placement(BlockPos position, BlockState state) {
		public Placement {
			position = position.immutable();
			Objects.requireNonNull(state, "state must not be null");
		}
	}

	private static final class Builder {
		private final BlockPos origin;
		private final ArrayList<Placement> placements = new ArrayList<>();

		private Builder(BlockPos origin) {
			this.origin = origin.immutable();
		}

		private void set(int x, int y, int z, Block block) {
			placements.add(new Placement(origin.offset(x, y, z), block.defaultBlockState()));
		}

		private void fill(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Block block) {
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					for (int z = minZ; z <= maxZ; z++) set(x, y, z, block);
				}
			}
		}

		private void clear(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
			fill(minX, maxX, minY, maxY, minZ, maxZ, Blocks.AIR);
		}

		private void wall(int minX, int maxX, int minZ, int maxZ, int minY, int maxY, Block block) {
			for (int y = minY; y <= maxY; y++) {
				lineX(minX, maxX, y, minZ, block);
				lineX(minX, maxX, y, maxZ, block);
				lineZ(minZ, maxZ, y, minX, block);
				lineZ(minZ, maxZ, y, maxX, block);
			}
		}

		private void lineX(int minX, int maxX, int y, int z, Block block) {
			for (int x = minX; x <= maxX; x++) set(x, y, z, block);
		}

		private void lineZ(int minZ, int maxZ, int y, int x, Block block) {
			for (int z = minZ; z <= maxZ; z++) set(x, y, z, block);
		}

		private void pillar(int x, int z, int height, Block block) {
			pillar(x, z, 1, height, block);
		}

		private void pillar(int x, int z, int top, int bottom, Block block) {
			int min = Math.min(top, bottom);
			int max = Math.max(top, bottom);
			for (int y = min; y <= max; y++) set(x, y, z, block);
		}

		private void ring(int centerX, int centerZ, int radius, int y, Block block) {
			for (int x = -radius; x <= radius; x++) {
				set(centerX + x, y, centerZ - radius, block);
				set(centerX + x, y, centerZ + radius, block);
			}
			for (int z = -radius + 1; z < radius; z++) {
				set(centerX - radius, y, centerZ + z, block);
				set(centerX + radius, y, centerZ + z, block);
			}
		}

		private void annulus(
				int centerX,
				int centerZ,
				int innerRadius,
				int outerRadius,
				int y,
				Block block
		) {
			int innerSquared = innerRadius * innerRadius;
			int outerSquared = outerRadius * outerRadius;
			for (int x = -outerRadius; x <= outerRadius; x++) {
				for (int z = -outerRadius; z <= outerRadius; z++) {
					int distanceSquared = x * x + z * z;
					if (distanceSquared >= innerSquared && distanceSquared <= outerSquared) {
						set(centerX + x, y, centerZ + z, block);
					}
				}
			}
		}

		private void tree(int x, int z) {
			for (int y = 1; y <= 5; y++) set(x, y, z, Blocks.OAK_LOG);
			for (int y = 4; y <= 7; y++) {
				int radius = y == 7 ? 1 : 2;
				for (int dx = -radius; dx <= radius; dx++) {
					for (int dz = -radius; dz <= radius; dz++) {
						if (Math.abs(dx) + Math.abs(dz) <= radius + 1) set(x + dx, y, z + dz, Blocks.OAK_LEAVES);
					}
				}
			}
		}

		private void ruin(int centerX, int centerZ, int width, int depth) {
			fill(centerX - width / 2, centerX + width / 2, 1, 1, centerZ - depth / 2, centerZ + depth / 2,
					Blocks.COBBLESTONE);
			for (int y = 2; y <= 6; y++) {
				lineX(centerX - width / 2, centerX + width / 2, y, centerZ - depth / 2, Blocks.STONE_BRICKS);
				lineZ(centerZ - depth / 2, centerZ + depth / 2, y, centerX - width / 2, Blocks.MOSSY_STONE_BRICKS);
			}
		}

		private void plot(int centerX, int centerZ, int size) {
			int half = size / 2;
			fill(centerX - half, centerX + half, 0, 0, centerZ - half, centerZ + half, Blocks.WHITE_CONCRETE);
			ring(centerX, centerZ, half + 1, 0, Blocks.LIGHT_GRAY_CONCRETE);
			set(centerX, 0, centerZ, Blocks.SEA_LANTERN);
		}

		private void tower(int centerX, int centerZ, int radius, int height) {
			for (int y = 1; y <= height; y++) ring(centerX, centerZ, radius, y, Blocks.DEEPSLATE_BRICKS);
			fill(centerX - radius + 1, centerX + radius - 1, height, height,
					centerZ - radius + 1, centerZ + radius - 1, Blocks.POLISHED_BLACKSTONE);
			for (int y = 2; y < height; y++) set(centerX, y, centerZ, Blocks.LADDER);
		}

		private void spectatorDeck() {
			fill(-7, 7, 14, 14, 66, 74, Blocks.QUARTZ_BLOCK);
			for (int x = -7; x <= 7; x++) {
				set(x, 15, 66, Blocks.GLASS);
				set(x, 15, 74, Blocks.GLASS);
			}
			for (int z = 67; z < 74; z++) {
				set(-7, 15, z, Blocks.GLASS);
				set(7, 15, z, Blocks.GLASS);
			}
			fill(-3, 3, 14, 14, 67, 73, Blocks.SEA_LANTERN);
		}
	}
}
