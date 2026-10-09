package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.server.perception.SightedFeatures.Cell;
import dev.agaminggod.arenaagents.world.ChunkMutationRevisionAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Far sight from world data: start from what the loaded world holds (generated structures, points of interest, biomes,
 * blocks that do not belong to the terrain, lava, chests and spawners) and keep only what a clear line from the agent's
 * eye inside its view cone reaches. Nothing behind terrain, outside loaded chunks or behind the agent is reported; the
 * model turns (lookAround) to see the rest. Sizes, counts and how far a thing is noticeable come only from the blocks
 * actually seen.
 */
public final class FarSight {
	/** The farthest a player sees structures, biomes and big clusters: the landmark sight distance. */
	static final int RANGE = ServerObservationCollector.LANDMARK_SIGHT_DISTANCE;
	/**
	 * Within 64 blocks a whole block covers about 14 or more screen pixels (1080 lines over a 70 degree field of view), so
	 * shapes and textures read and a structure is named; farther, only its built materials, direction and size are given.
	 */
	static final int NAMED_STRUCTURE_DISTANCE = 64;
	/**
	 * One texture pixel (1/16 block, an ore speckle) stays at least 2 screen pixels out to about 27 blocks at 1080 lines
	 * and 70 degrees, so single small blocks (ores, chests, spawners, beds, bells) are noticed within 24 blocks.
	 */
	static final int SMALL_SIGHT = 24;
	/** Seen blocks that make a built or lava cluster big enough to notice at full range; fewer count as small. */
	static final int LARGE_CLUSTER = 8;
	/** Line-of-sight tests per pass; passive passes run at most every 10 ticks and reuse results while the eye is still. */
	static final int PASSIVE_CLIPS = 128;
	static final int SURVEY_CLIPS = 256;
	/**
	 * Full far-sight passes per level per tick. Observations of up to 8 agents can land on one tick; agents over the cap
	 * keep their previous rows for a tick or two. Surveys the model asks for have their own cap; over it, a survey answers
	 * from the agent's cached candidates and sight lines without new work.
	 */
	static final int PASSES_PER_TICK = 2;
	static final int SURVEYS_PER_TICK = 1;
	/** Full section reads (4,096 blocks each) per level per tick, shared by every agent and search; palette checks are cheaper. */
	static final int SECTION_SCANS_PER_TICK = 8;
	static final int PALETTE_CHECKS_PER_TICK = 2_048;
	static final int MAX_ENTRIES_PER_SECTION = 96;
	/** Far chunks are read only near the eye's height; a player above or below sees little beyond this band at range. */
	static final int FAR_VERTICAL_BAND = 64;
	static final int ORE_VERTICAL_BAND = SMALL_SIGHT + 8;
	static final int MAX_ROWS = 8;
	static final int DEFAULT_SURVEY_ROWS = 4;
	static final int MAX_SEARCHED_BLOCKS = 4;
	/** Water fog ends at 96 blocks times the eye's water vision (at least 0.25), as the 26.1 client draws it. */
	static final double WATER_FOG_END = 96.0D;
	/** Lava fog ends at 1 block, or 5 with fire resistance (26.1 client). */
	static final double LAVA_FOG_END = 1.0D;
	static final double LAVA_FOG_END_FIRE_RESISTANT = 5.0D;
	private static final int CHUNK_RADIUS = (RANGE >> 4) + 1;
	private static final int[][] CHUNK_OFFSETS = chunkOffsets();

	static final int BUILT = 1;
	static final int LAVA = 2;
	static final int NOTABLE = 4;
	static final int ORE = 8;
	static final int POI = 16;
	private static final int FAR_KINDS = BUILT | LAVA | POI;
	private static final int NEAR_KINDS = BUILT | LAVA | POI | NOTABLE | ORE;

	/** Blocks a player notices up close and may want: spawners, loot chests, barrels and vaults. */
	private static final Set<Block> NOTABLE_BLOCKS = Set.of(Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.CHEST,
			Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.VAULT);
	/** Built-looking blocks that terrain also makes: taiga boulders and pale moss. */
	private static final Set<Block> NATURAL_LOOKALIKES = Set.of(Blocks.MOSSY_COBBLESTONE, Blocks.PALE_MOSS_CARPET);
	/** Blocks no overworld terrain makes outside generated structures (ruined portals are structures). */
	private static final Set<Block> NOT_OVERWORLD = Set.of(Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL,
			Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT,
			Blocks.CRIMSON_STEM, Blocks.WARPED_STEM, Blocks.GLOWSTONE, Blocks.BLACKSTONE, Blocks.END_STONE);
	/** Overworld terrain and trees that never generate in the Nether or the End (explicit, so it holds before tags bind). */
	private static final Set<Block> NOT_NETHER_OR_END = Set.of(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT,
			Blocks.PODZOL, Blocks.MOSS_BLOCK, Blocks.STONE, Blocks.DEEPSLATE, Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.SAND,
			Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
			Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG, Blocks.PALE_OAK_LOG);
	/**
	 * Ground and fluids that fill whole sections: a search for them would read every section in range and find terrain,
	 * which the agent already sees in landmarks and the local block rows.
	 */
	private static final Set<Block> UNSEARCHABLE = Set.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR, Blocks.STONE, Blocks.DEEPSLATE,
			Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.SAND, Blocks.RED_SAND,
			Blocks.GRAVEL, Blocks.WATER, Blocks.NETHERRACK, Blocks.END_STONE, Blocks.BEDROCK, Blocks.SANDSTONE, Blocks.TERRACOTTA,
			Blocks.BASALT, Blocks.BLACKSTONE, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.SNOW_BLOCK, Blocks.CLAY, Blocks.CALCITE);

	/** Kinds per block state, one table per dimension class (overworld, Nether, End); read in every scanned block. */
	private static final List<Map<BlockState, Integer>> KINDS = List.of(new ConcurrentHashMap<>(), new ConcurrentHashMap<>(),
			new ConcurrentHashMap<>());

	private FarSight() {
	}

	/** Survey and passive sections. Threats are not a section: they are always reported. */
	public enum Section {
		STRUCTURES("structures"), POI("poi"), BIOMES("biomes"), BUILT("built"), BLOCKS("blocks"), CAVES("caves"), VEINS("veins");

		final String key;

		Section(String key) {
			this.key = key;
		}

		static Section of(String key) {
			for (Section section : values()) if (section.key.equals(key)) return section;
			return null;
		}
	}

	/**
	 * What one pass looks for: sections, extra block ids to search near the agent, rows per section and a line-of-sight
	 * budget. A budget of 0 answers only from cached candidates and sight lines.
	 */
	public record Request(Set<Section> sections, List<Block> searched, int rowsPerSection, int clips) {
		public Request {
			sections = sections.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(sections));
			searched = List.copyOf(searched);
		}

		static Request passive() {
			return new Request(EnumSet.allOf(Section.class), List.of(), DEFAULT_SURVEY_ROWS, PASSIVE_CLIPS);
		}

		Request cachedOnly() {
			return new Request(sections, searched, rowsPerSection, 0);
		}

		boolean wants(Section section) {
			return sections.contains(section);
		}
	}

	/**
	 * One reported thing. {@code key} is stable for the thing (a structure start, or a cluster's seed block) so it is not
	 * announced again as the agent walks along it. {@code label} is a structure name (near structures only), biome id or
	 * block id; {@code blocks} lists the built block ids seen (far structures and built clusters); {@code size} is the
	 * horizontal extent of what was seen; {@code count} the seen blocks of a lava pool or a search.
	 */
	public record Row(Section section, String key, String label, List<String> blocks, int count, int size, Cell cell, double distance) {
		public Row {
			Objects.requireNonNull(section, "section must not be null");
			Objects.requireNonNull(key, "key must not be null");
			Objects.requireNonNull(cell, "cell must not be null");
			blocks = List.copyOf(blocks);
		}
	}

	/** Rows nearest first per section, the visible ore that seeds veins, and (for measurement only) the work done. */
	public record Result(List<Row> rows, List<BlockPos> seenOre, String standingIn, int clips, int candidates, boolean complete) {
		public static final Result EMPTY = new Result(List.of(), List.of(), null, 0, 0, false);

		public Result {
			rows = List.copyOf(rows);
			seenOre = List.copyOf(seenOre);
		}
	}

	// ---- Shared per-level scan cache -------------------------------------------------------------------------------

	/** One exposed block found by a section scan: packed position, state, kinds and the faces open to air. */
	record Found(long position, BlockState state, int kinds, int openFaces) {
	}

	private static final class SectionScan {
		final long revision;
		int kinds;
		final List<Found> found = new ArrayList<>();
		/** Exposed blocks of one searched id, read on demand and kept with the section. */
		final Map<Block, List<Found>> searches = new HashMap<>();

		SectionScan(long revision) {
			this.revision = revision;
		}
	}

	private static final class ChunkScan {
		// Weak, so a scan never keeps an unloaded chunk in memory.
		final java.lang.ref.WeakReference<LevelChunk> chunk;
		final SectionScan[] sections;

		ChunkScan(LevelChunk chunk) {
			this.chunk = new java.lang.ref.WeakReference<>(chunk);
			this.sections = new SectionScan[chunk.getSections().length];
		}
	}

	/**
	 * Scans of loaded chunks for one level, shared by every agent. A section's scan is kept while the same chunk object
	 * stays loaded and that section's write count is unchanged (crops growing in one section do not rescan the chunk);
	 * work is capped per game tick across all agents.
	 */
	public static final class LevelIndex {
		private final Map<Long, ChunkScan> chunks = new HashMap<>();
		private long budgetTick = Long.MIN_VALUE;
		private int scansThisTick;
		private int checksThisTick;
		private int passesThisTick;
		private int surveysThisTick;
		int sectionResets;

		synchronized int size() {
			return chunks.size();
		}

		private void roll(long tick) {
			if (budgetTick == tick) return;
			budgetTick = tick;
			scansThisTick = 0;
			checksThisTick = 0;
			passesThisTick = 0;
			surveysThisTick = 0;
		}

		/** Claims one of this tick's full passes; false when other agents already used them. */
		synchronized boolean tryPass(long tick, boolean survey) {
			roll(tick);
			if (survey) {
				if (surveysThisTick >= SURVEYS_PER_TICK) return false;
				surveysThisTick++;
				return true;
			}
			if (passesThisTick >= PASSES_PER_TICK) return false;
			passesThisTick++;
			return true;
		}

		private ChunkScan scanOf(LevelChunk chunk) {
			long key = chunk.getPos().pack();
			ChunkScan scan = chunks.get(key);
			if (scan == null || scan.chunk.get() != chunk) {
				scan = new ChunkScan(chunk);
				chunks.put(key, scan);
			}
			return scan;
		}

		/** The section's current scan, replaced when its blocks changed since it was read. */
		private SectionScan section(LevelChunk chunk, ChunkScan scan, int sectionIndex) {
			long revision = sectionRevision(chunk, sectionIndex);
			SectionScan section = scan.sections[sectionIndex];
			if (section == null || section.revision != revision) {
				if (section != null) sectionResets++;
				section = scan.sections[sectionIndex] = new SectionScan(revision);
			}
			return section;
		}

		private boolean takeCheck(long tick) {
			roll(tick);
			if (checksThisTick >= PALETTE_CHECKS_PER_TICK) return false;
			checksThisTick++;
			return true;
		}

		private boolean takeScan(long tick) {
			roll(tick);
			if (scansThisTick >= SECTION_SCANS_PER_TICK) return false;
			scansThisTick++;
			return true;
		}

		private void forgetUnloaded(ServerLevel level) {
			if (chunks.size() < 2_048) return;
			chunks.entrySet().removeIf(entry -> entry.getValue().chunk.get() == null || level.getChunkSource().getChunkNow(
					ChunkPos.getX(entry.getKey()), ChunkPos.getZ(entry.getKey())) != entry.getValue().chunk.get());
		}
	}

	private static long sectionRevision(LevelChunk chunk, int sectionIndex) {
		// Verification doubles may not carry the mixin; a scan then never outlives its game tick.
		return chunk instanceof ChunkMutationRevisionAccess access ? access.arenaagents$sectionMutationRevision(sectionIndex)
				: chunk.getLevel().getGameTime();
	}

	/** Whether a block was written after generation while its chunk has been loaded (a player's or a mob's change). */
	static boolean changedSinceLoad(ServerLevel level, BlockPos position) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
		return chunk instanceof ChunkMutationRevisionAccess access && access.arenaagents$changedSinceLoad(position);
	}

	/** Clears per-state kinds after datapack tags reload. */
	static void clearKinds() {
		KINDS.forEach(Map::clear);
	}

	static int dimensionClass(ServerLevel level) {
		if (level.dimension() == Level.NETHER) return 1;
		if (level.dimension() == Level.END) return 2;
		return 0;
	}

	/** What a block is to far sight: built (or foreign to this dimension), lava, notable, ore or a point of interest. */
	static int kinds(BlockState state, int dimensionClass) {
		Map<BlockState, Integer> table = KINDS.get(dimensionClass);
		Integer cached = table.get(state);
		if (cached != null) return cached;
		int kinds = computeKinds(state, dimensionClass);
		table.put(state, kinds);
		return kinds;
	}

	private static int computeKinds(BlockState state, int dimensionClass) {
		if (state.isAir()) return 0;
		int kinds = 0;
		if (state.is(Blocks.LAVA)) kinds |= LAVA;
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		if (SightedFeatures.oreFamily(id) != null) kinds |= ORE;
		if (NOTABLE_BLOCKS.contains(state.getBlock())) kinds |= NOTABLE;
		if (PoiTypes.hasPoi(state)) kinds |= POI;
		if (looksBuilt(state, dimensionClass)) kinds |= BUILT;
		return kinds;
	}

	/** Blocks that do not belong to this dimension's terrain: building blocks, or another dimension's ground. */
	static boolean looksBuilt(BlockState state, int dimensionClass) {
		Block block = state.getBlock();
		if (NATURAL_LOOKALIKES.contains(block)) return false;
		if (dimensionClass == 0 && NOT_OVERWORLD.contains(block)) return true;
		if (dimensionClass != 0 && (NOT_NETHER_OR_END.contains(block) || state.is(BlockTags.LOGS_THAT_BURN)
				|| state.is(BlockTags.LEAVES))) return true;
		return SightedFeatures.built(state, null) && !SightedFeatures.natural(state);
	}

	static boolean searchable(Block block) {
		return !UNSEARCHABLE.contains(block);
	}

	/** Reads one section's exposed blocks of the wanted kinds; returns false only when the tick's budget is spent. */
	private static boolean scanSection(ServerLevel level, LevelIndex index, LevelChunk chunk, ChunkScan scan, int sectionIndex, int wanted, int dimensionClass) {
		SectionScan section = index.section(chunk, scan, sectionIndex);
		int missing = wanted & ~section.kinds;
		if (missing == 0) return true;
		long tick = level.getGameTime();
		if (!index.takeCheck(tick)) return false;
		LevelChunkSection blocks = chunk.getSections()[sectionIndex];
		if (blocks.hasOnlyAir() || !blocks.maybeHas(state -> (kinds(state, dimensionClass) & missing) != 0)) {
			section.kinds |= missing;
			return true;
		}
		if (!index.takeScan(tick)) return false;
		int baseX = chunk.getPos().getMinBlockX();
		int baseY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
		int baseZ = chunk.getPos().getMinBlockZ();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int added = 0;
		for (int y = 0; y < 16 && added < MAX_ENTRIES_PER_SECTION; y++) {
			for (int z = 0; z < 16 && added < MAX_ENTRIES_PER_SECTION; z++) {
				for (int x = 0; x < 16 && added < MAX_ENTRIES_PER_SECTION; x++) {
					BlockState state = blocks.getBlockState(x, y, z);
					int kinds = kinds(state, dimensionClass) & missing;
					if (kinds == 0) continue;
					int open = openFaces(level, chunk, cursor, baseX + x, baseY + y, baseZ + z, state);
					if (open == 0) continue;
					section.found.add(new Found(BlockPos.asLong(baseX + x, baseY + y, baseZ + z), state, kinds, open));
					added++;
				}
			}
		}
		section.kinds |= missing;
		return true;
	}

	/**
	 * Faces a player could see: the neighbour does not hide the face (air, water, glass, torches...). Lava counts only
	 * where it meets something see-through that is not lava. A neighbour in an unloaded chunk hides the face.
	 */
	static int openFaces(ServerLevel level, LevelChunk chunk, BlockPos.MutableBlockPos cursor, int x, int y, int z, BlockState state) {
		int open = 0;
		for (Direction direction : Direction.values()) {
			int nx = x + direction.getStepX(), ny = y + direction.getStepY(), nz = z + direction.getStepZ();
			BlockState neighbour;
			if (ny < level.getMinY() || ny > level.getMaxY()) neighbour = Blocks.AIR.defaultBlockState();
			else if (nx >> 4 == chunk.getPos().x() && nz >> 4 == chunk.getPos().z()) neighbour = chunk.getBlockState(cursor.set(nx, ny, nz));
			else {
				LevelChunk other = level.getChunkSource().getChunkNow(nx >> 4, nz >> 4);
				if (other == null) continue;
				neighbour = other.getBlockState(cursor.set(nx, ny, nz));
			}
			if (faceOpen(state, neighbour)) open |= 1 << direction.ordinal();
		}
		return open;
	}

	static boolean faceOpen(BlockState state, BlockState neighbour) {
		if (state.is(Blocks.LAVA)) return !neighbour.is(Blocks.LAVA) && !neighbour.canOcclude();
		return !neighbour.canOcclude();
	}

	// ---- Line of sight ---------------------------------------------------------------------------------------------

	/**
	 * Per-agent caches: clip results kept while the eye stays within half a block and nothing in sight range changed (each
	 * entry maps an aim point to the block the clear line from the eye ends in), the last gathered candidates, and how
	 * long the eye has been under water (the client's water vision, which sets how far it sees there).
	 */
	public static final class SightCache {
		private String dimension;
		private long eyeX = Long.MIN_VALUE, eyeY, eyeZ, revision;
		private final Map<Long, Long> ends = new HashMap<>();
		private CandidateKey candidatesKey;
		private Candidates candidates;
		private long waterTick = Long.MIN_VALUE;
		private int waterVisionTime;

		void validate(String dimension, Vec3 eye, long revision) {
			long x = Math.round(eye.x * 2.0D), y = Math.round(eye.y * 2.0D), z = Math.round(eye.z * 2.0D);
			if (!dimension.equals(this.dimension) || x != eyeX || y != eyeY || z != eyeZ || revision != this.revision || ends.size() > 16_384) {
				ends.clear();
				this.dimension = dimension;
				eyeX = x;
				eyeY = y;
				eyeZ = z;
				this.revision = revision;
			}
		}

		/** Follows the client's water vision timer: +1 per tick with the eye in water (to 600), -10 per tick out of it. */
		float waterVision(boolean eyeInWater, long gameTime) {
			long elapsed = waterTick == Long.MIN_VALUE ? 0L : Math.max(0L, Math.min(600L, gameTime - waterTick));
			waterTick = gameTime;
			waterVisionTime = (int) Mth.clamp(eyeInWater ? waterVisionTime + elapsed : waterVisionTime - 10L * elapsed, 0L, 600L);
			return FarSight.waterVision(waterVisionTime);
		}

		int size() {
			return ends.size();
		}
	}

	/** The client's LocalPlayer.getWaterVision ramp: 0.6 after 5 s under water, 1 after 30 s. */
	static float waterVision(int waterVisionTime) {
		if (waterVisionTime >= 600) return 1.0F;
		float early = Mth.clamp(waterVisionTime / 100.0F, 0.0F, 1.0F);
		float late = waterVisionTime < 100 ? 0.0F : Mth.clamp((waterVisionTime - 100.0F) / 500.0F, 0.0F, 1.0F);
		return early * 0.6F + late * 0.4F;
	}

	/** How far the agent sees: fog ends close when its eye is in lava or water, as the client draws it. */
	static double sightRange(boolean eyeInLava, boolean fireResistant, boolean eyeInWater, float waterVision) {
		if (eyeInLava) return fireResistant ? LAVA_FOG_END_FIRE_RESISTANT : LAVA_FOG_END;
		if (eyeInWater) return Math.min(RANGE, WATER_FOG_END * Math.max(0.25F, waterVision));
		return RANGE;
	}

	/** One pass's eye, view cone, sight range, clip budget and cache. */
	static final class Sight {
		private final ServerLevel level;
		private final Vec3 eye;
		private final Vec3 view;
		private final double range;
		private final ClipContext clip;
		private final SightCache cache;
		private final Map<Long, Boolean> loadedChunks = new HashMap<>();
		private final int budget;
		private int sectionLimit = Integer.MAX_VALUE;
		int clips;

		Sight(ServerLevel level, Vec3 eye, Vec3 view, double range, CollisionContext context, SightCache cache, int budget) {
			this.level = level;
			this.eye = eye;
			this.view = view;
			this.range = range;
			this.clip = new ClipContext(eye, eye, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, context);
			this.cache = cache;
			this.budget = budget;
		}

		boolean inView(Vec3 point) {
			return ObservationVisibility.isWithinViewCone(eye, view, point);
		}

		/**
		 * Starts a section with its share of the pass's line-of-sight budget, so a busy village cannot starve biomes or
		 * built clusters; a section that needs less leaves the rest to the sections after it.
		 */
		void section(double share) {
			sectionLimit = clips + (int) Math.ceil(budget * share);
		}

		boolean exhausted() {
			return clips >= budget || clips >= sectionLimit;
		}

		double distance(BlockPos position) {
			return Math.sqrt(eye.distanceToSqr(Vec3.atCenterOf(position)));
		}

		boolean withinRange(BlockPos position) {
			return distance(position) <= range;
		}

		/**
		 * The block a clear line from the eye toward {@code point} ends in: the first block with a visual shape or holding
		 * lava (lava is opaque; water is seen through, as {@link ObservationVisibility} rules), or the block holding the
		 * point when nothing is in the way. Null when beyond sight range, out of budget, or the line crosses an unloaded
		 * chunk.
		 */
		BlockPos lineEnd(Vec3 point) {
			if (eye.distanceTo(point) > range + 1.0D) return null;
			long key = pointKey(point);
			Long cached = cache.ends.get(key);
			if (cached != null) return cached == Long.MIN_VALUE ? null : BlockPos.of(cached);
			if (exhausted()) return null;
			clips++;
			BlockPos end = null;
			if (ObservationVisibility.hasLoadedSightPath(eye, point, position -> loadedChunks.computeIfAbsent(
					ChunkPos.pack(position), ignored -> level.hasChunkAt(position)))) {
				end = BlockGetter.traverseBlocks(eye, point, clip, (context, position) -> {
					BlockState state = level.getBlockState(position);
					if (state.getFluidState().is(FluidTags.LAVA)) return position.immutable();
					BlockHitResult hit = level.clipWithInteractionOverride(eye, point, position, context.getBlockShape(state, level, position), state);
					return hit == null || hit.getType() == HitResult.Type.MISS ? null : hit.getBlockPos().immutable();
				}, context -> null);
				if (end == null) end = BlockPos.containing(point);
			}
			cache.ends.put(key, end == null ? Long.MIN_VALUE : end.asLong());
			return end;
		}

		/**
		 * Whether a scanned block is still there (candidates can be a few seconds old) and one of its open faces that turn
		 * toward the eye is in view and in clear sight within range.
		 */
		boolean sees(Found found) {
			BlockPos position = BlockPos.of(found.position());
			// The same block, not the same state: lava flows and doors open without the thing going away.
			if (!withinRange(position) || !level.getBlockState(position).is(found.state().getBlock())) return false;
			return seesBlock(position, found.openFaces());
		}

		boolean seesBlock(BlockPos position, int openFaces) {
			Vec3 center = Vec3.atCenterOf(position);
			for (Direction direction : Direction.values()) {
				if ((openFaces & (1 << direction.ordinal())) == 0) continue;
				Vec3 normal = Vec3.atLowerCornerOf(direction.getUnitVec3i());
				Vec3 face = center.add(normal.scale(0.5D));
				if (normal.dot(eye.subtract(face)) <= 0.0D) continue;
				Vec3 aim = center.add(normal.scale(0.45D));
				if (!inView(aim)) continue;
				BlockPos end = lineEnd(aim);
				if (end == null) {
					if (exhausted()) return false;
					continue;
				}
				if (end.equals(position)) return true;
			}
			return false;
		}
	}

	private static long pointKey(Vec3 point) {
		long x = Math.round(point.x * 8.0D) & 0x3FFFFFFL;
		long y = Math.round(point.y * 8.0D) & 0xFFFL;
		long z = Math.round(point.z * 8.0D) & 0x3FFFFFFL;
		return (x << 38) | (z << 12) | y;
	}

	// ---- The pass -------------------------------------------------------------------------------------------------

	private record StartView(StructureStart start, String key, String label, double distance) {
	}

	/**
	 * Everything a pass gathers from world data before testing sight: structure starts, exposed blocks by kind and one
	 * surface column per chunk by biome. It depends on where the eye is, not where it looks.
	 */
	private record Candidates(List<StartView> starts, List<Found> built, List<Found> lava, List<Found> notable, List<Found> ore,
			List<Found> poi, Map<String, List<BlockPos>> biomeColumns) {
		int size() {
			return starts.size() + built.size() + lava.size() + notable.size() + ore.size() + poi.size();
		}
	}

	/**
	 * Candidates are reused while the eye stays in the same chunk and 8-block height band, for at most 5 s: block changes,
	 * newly loaded chunks and other agents' scans join within that time. Whether each candidate is seen, and is still
	 * there, is checked live on every pass. (Keying on block changes missed almost every time in a village.)
	 */
	private record CandidateKey(String dimension, int chunkX, int chunkZ, int band, long age) {
	}

	static final long CANDIDATE_TICKS = 100L;

	record Cluster(List<Found> members, double distance) {
		/** The member with the smallest packed position: stable while the cluster stands, wherever the agent views it from. */
		long seed() {
			long seed = Long.MAX_VALUE;
			for (Found member : members) seed = Math.min(seed, member.position());
			return seed;
		}
	}

	/**
	 * Looks once from the agent's eye. Candidates come from loaded chunks within {@link #RANGE}; each is reported only if a
	 * clear line from the eye inside the view cone reaches it, and only within the distance the seen part makes noticeable.
	 */
	static Result look(ServerLevel level, ServerPlayer agent, LevelIndex index, SightCache cache, long revision, Request request) {
		Vec3 eye = agent.getEyePosition();
		Vec3 view = agent.getViewVector(1.0F);
		String dimension = level.dimension().identifier().toString();
		long gameTime = level.getGameTime();
		cache.validate(dimension, eye, revision);
		boolean eyeInWater = agent.isEyeInFluid(FluidTags.WATER);
		double range = sightRange(agent.isEyeInFluid(FluidTags.LAVA), agent.hasEffect(MobEffects.FIRE_RESISTANCE), eyeInWater,
				cache.waterVision(eyeInWater, gameTime));
		Sight sight = new Sight(level, eye, view.lengthSqr() == 0.0D ? Vec3.ZERO : view.normalize(), range, CollisionContext.of(agent), cache,
				request.clips());
		int dimensionClass = dimensionClass(level);
		int eyeChunkX = Mth.floor(eye.x) >> 4, eyeChunkZ = Mth.floor(eye.z) >> 4;
		CandidateKey key = new CandidateKey(dimension, eyeChunkX, eyeChunkZ, Mth.floor(eye.y) >> 3, gameTime / CANDIDATE_TICKS);
		Candidates gathered = key.equals(cache.candidatesKey) ? cache.candidates : null;
		PerceptionTiming.count("far_sight_candidate_cache_hit", gathered == null ? 0 : 1);
		boolean complete = true;
		if (gathered == null) {
			// Over the tick's budget a survey answers only from what is cached; there is nothing cached here.
			if (request.clips() == 0) return Result.EMPTY;
			List<LevelChunk> loaded = new ArrayList<>();
			for (int[] offset : CHUNK_OFFSETS) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(eyeChunkX + offset[0], eyeChunkZ + offset[1]);
				if (chunk != null && horizontalDistanceToChunk(eye, chunk.getPos()) <= RANGE) loaded.add(chunk);
			}
			List<StartView> starts = new ArrayList<>();
			Map<Long, List<BoundingBox>> structureBoxes = new HashMap<>();
			collectStructures(level, eye, loaded, starts, structureBoxes);
			List<Found> built = new ArrayList<>(), lava = new ArrayList<>(), notable = new ArrayList<>(), ore = new ArrayList<>(), poi = new ArrayList<>();
			synchronized (index) {
				index.forgetUnloaded(level);
				int resets = index.sectionResets;
				complete = scanAround(level, index, eye, loaded, dimensionClass, built, lava, notable, ore, poi, structureBoxes);
				PerceptionTiming.count("far_sight_section_rescans", index.sectionResets - resets);
			}
			gathered = new Candidates(starts, built, lava, notable, ore, poi, biomeColumns(level, loaded));
			// Only a finished gather is reused; an unfinished one is redone next pass so the spread-out scan continues.
			cache.candidatesKey = complete ? key : null;
			cache.candidates = complete ? gathered : null;
		}
		List<Row> rows = new ArrayList<>();
		int candidates = gathered.size();
		ToDoubleFunction<Found> distance = found -> Math.sqrt(eye.distanceToSqr(Vec3.atCenterOf(BlockPos.of(found.position()))));

		// Lava first, as a hazard; then shares of the budget per section (they sum to the whole pass).
		if (request.wants(Section.BLOCKS)) {
			sight.section(0.10D);
			rows.addAll(lavaRows(sight, cluster(gathered.lava(), distance), request.rowsPerSection()));
		}
		if (request.wants(Section.STRUCTURES)) {
			sight.section(0.25D);
			rows.addAll(structureRows(level, sight, gathered.starts(), request.rowsPerSection()));
		}
		if (request.wants(Section.POI)) {
			sight.section(0.10D);
			rows.addAll(poiRows(sight, gathered.poi(), distance, request.rowsPerSection()));
		}
		if (request.wants(Section.BUILT)) {
			sight.section(0.25D);
			rows.addAll(builtRows(sight, cluster(gathered.built(), distance), request.rowsPerSection()));
		}
		if (request.wants(Section.BLOCKS)) {
			sight.section(0.05D);
			rows.addAll(notableRows(sight, gathered.notable(), distance, request.rowsPerSection()));
			for (Block block : request.searched()) {
				sight.section(0.05D);
				List<Found> matches = new ArrayList<>();
				synchronized (index) {
					if (!search(level, index, eye, block, matches)) complete = false;
				}
				candidates += matches.size();
				rows.addAll(searchedRows(sight, block, matches, distance));
			}
		}
		String standingIn = biomeAt(level, BlockPos.containing(eye));
		if (request.wants(Section.BIOMES)) {
			sight.section(0.15D);
			rows.addAll(biomeRows(sight, gathered.biomeColumns(), standingIn, request.rowsPerSection()));
		}
		List<BlockPos> seenOre = new ArrayList<>();
		if (request.wants(Section.VEINS)) {
			sight.section(0.10D);
			int tests = 0;
			for (Found found : gathered.ore().stream().sorted(Comparator.comparingDouble(distance)).toList()) {
				if (tests++ >= SightedFeatures.MAX_VEIN_SEEDS * 2 || sight.exhausted()) break;
				if (sight.sees(found)) seenOre.add(BlockPos.of(found.position()));
			}
		}
		// Every row is judged by its own distance: a structure or cluster near the edge never reports a block past range.
		rows.removeIf(row -> row.distance() > range);
		PerceptionTiming.count("far_sight_candidates", candidates);
		PerceptionTiming.count("far_sight_sight_lines", sight.clips);
		return new Result(rows, seenOre, standingIn, sight.clips, candidates, complete && sight.clips < request.clips());
	}

	private static double horizontalDistanceToChunk(Vec3 eye, ChunkPos chunk) {
		double dx = Math.max(0.0D, Math.max(chunk.getMinBlockX() - eye.x, eye.x - (chunk.getMaxBlockX() + 1)));
		double dz = Math.max(0.0D, Math.max(chunk.getMinBlockZ() - eye.z, eye.z - (chunk.getMaxBlockZ() + 1)));
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static int[][] chunkOffsets() {
		List<int[]> offsets = new ArrayList<>();
		for (int x = -CHUNK_RADIUS; x <= CHUNK_RADIUS; x++) for (int z = -CHUNK_RADIUS; z <= CHUNK_RADIUS; z++) offsets.add(new int[]{x, z});
		offsets.sort(Comparator.comparingInt(offset -> offset[0] * offset[0] + offset[1] * offset[1]));
		return offsets.toArray(int[][]::new);
	}

	/**
	 * Every structure start referenced by a loaded chunk in range whose start chunk is loaded too (an unloaded start is
	 * never read, so this never loads a chunk), plus each start's piece boxes by chunk so built blocks inside generated
	 * structures are not mistaken for building.
	 */
	private static void collectStructures(ServerLevel level, Vec3 eye, List<LevelChunk> loaded, List<StartView> starts,
			Map<Long, List<BoundingBox>> boxesByChunk) {
		var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Set<String> seen = new HashSet<>();
		for (LevelChunk chunk : loaded) {
			if (!chunk.hasAnyStructureReferences()) continue;
			for (var references : chunk.getAllReferences().entrySet()) {
				for (long reference : references.getValue()) {
					var id = registry.getKey(references.getKey());
					String key = id + "@" + ChunkPos.getX(reference) + "," + ChunkPos.getZ(reference);
					if (!seen.add(key)) continue;
					LevelChunk startChunk = level.getChunkSource().getChunkNow(ChunkPos.getX(reference), ChunkPos.getZ(reference));
					if (startChunk == null) continue;
					StructureStart start = startChunk.getStartForStructure(references.getKey());
					if (start == null || !start.isValid()) continue;
					for (StructurePiece piece : start.getPieces()) {
						BoundingBox box = piece.getBoundingBox();
						for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
							boxesByChunk.computeIfAbsent(ChunkPos.pack(cx, cz), ignored -> new ArrayList<>()).add(box);
						}
					}
					String label = id == null ? null : SightedFeatures.structureLabel(id.toString());
					if (label != null) starts.add(new StartView(start, key, label, boxDistance(eye, start.getBoundingBox())));
				}
			}
		}
		starts.sort(Comparator.comparingDouble(StartView::distance));
	}

	static double boxDistance(Vec3 eye, BoundingBox box) {
		double dx = Math.max(0.0D, Math.max(box.minX() - eye.x, eye.x - (box.maxX() + 1)));
		double dy = Math.max(0.0D, Math.max(box.minY() - eye.y, eye.y - (box.maxY() + 1)));
		double dz = Math.max(0.0D, Math.max(box.minZ() - eye.z, eye.z - (box.maxZ() + 1)));
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Brings chunk scans up to date for this eye (bounded per tick) and gathers their exposed blocks by kind. */
	private static boolean scanAround(ServerLevel level, LevelIndex index, Vec3 eye, List<LevelChunk> loaded, int dimensionClass,
			List<Found> built, List<Found> lava, List<Found> notable, List<Found> ore, List<Found> poi,
			Map<Long, List<BoundingBox>> structureBoxes) {
		boolean complete = true;
		int eyeY = Mth.floor(eye.y);
		for (LevelChunk chunk : loaded) {
			ChunkScan scan = index.scanOf(chunk);
			boolean near = horizontalDistanceToChunk(eye, chunk.getPos()) <= SMALL_SIGHT;
			List<BoundingBox> boxes = structureBoxes.getOrDefault(chunk.getPos().pack(), List.of());
			for (int sectionIndex = 0; sectionIndex < scan.sections.length; sectionIndex++) {
				int minY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
				int wanted = 0;
				if (minY + 15 >= eyeY - FAR_VERTICAL_BAND && minY <= eyeY + FAR_VERTICAL_BAND) wanted |= FAR_KINDS;
				if (near && minY + 15 >= eyeY - ORE_VERTICAL_BAND && minY <= eyeY + ORE_VERTICAL_BAND) wanted |= NEAR_KINDS;
				if (wanted == 0) continue;
				if (!scanSection(level, index, chunk, scan, sectionIndex, wanted, dimensionClass)) complete = false;
				SectionScan section = scan.sections[sectionIndex];
				if (section == null) continue;
				for (Found found : section.found) {
					if ((found.kinds() & BUILT) != 0 && !insideAny(boxes, found.position())) built.add(found);
					if ((found.kinds() & LAVA) != 0) lava.add(found);
					if ((found.kinds() & POI) != 0) poi.add(found);
					if (!near) continue;
					if ((found.kinds() & NOTABLE) != 0) notable.add(found);
					if ((found.kinds() & ORE) != 0) ore.add(found);
				}
			}
		}
		return complete;
	}

	private static boolean insideAny(List<BoundingBox> boxes, long position) {
		int x = BlockPos.getX(position), y = BlockPos.getY(position), z = BlockPos.getZ(position);
		for (BoundingBox box : boxes) {
			if (x >= box.minX() - 1 && x <= box.maxX() + 1 && y >= box.minY() - 1 && y <= box.maxY() + 1
					&& z >= box.minZ() - 1 && z <= box.maxZ() + 1) return true;
		}
		return false;
	}

	/** Groups blocks within about 4 blocks of each other (linked 4-block cells), nearest cluster first. */
	static List<Cluster> cluster(List<Found> blocks, ToDoubleFunction<Found> distance) {
		Map<Long, List<Found>> cells = new LinkedHashMap<>();
		for (Found found : blocks) {
			long cell = BlockPos.asLong(BlockPos.getX(found.position()) >> 2, BlockPos.getY(found.position()) >> 2, BlockPos.getZ(found.position()) >> 2);
			cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(found);
		}
		Set<Long> claimed = new HashSet<>();
		List<Cluster> clusters = new ArrayList<>();
		for (Long start : cells.keySet()) {
			if (!claimed.add(start)) continue;
			List<Found> members = new ArrayList<>();
			java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
			queue.add(start);
			while (!queue.isEmpty()) {
				long cell = queue.poll();
				members.addAll(cells.get(cell));
				int cx = BlockPos.getX(cell), cy = BlockPos.getY(cell), cz = BlockPos.getZ(cell);
				for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
					long next = BlockPos.asLong(cx + dx, cy + dy, cz + dz);
					if (cells.containsKey(next) && claimed.add(next)) queue.add(next);
				}
			}
			members.sort(Comparator.comparingDouble(distance));
			clusters.add(new Cluster(members, distance.applyAsDouble(members.getFirst())));
		}
		clusters.sort(Comparator.comparingDouble(Cluster::distance));
		return clusters;
	}

	/** How far a thing of this many seen blocks is noticeable: big ones to full range, small ones up close. */
	static int noticeableDistance(int seenBlocks) {
		return seenBlocks >= LARGE_CLUSTER ? RANGE : SMALL_SIGHT;
	}

	/** The members a player sees, tested nearest first; enough to tell a large cluster from a small one. */
	record SeenPart(List<Found> seen, int tested) {
		int count() {
			return seen.size();
		}

		Found nearest() {
			return seen.getFirst();
		}

		int horizontalSize() {
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
			for (Found member : seen) {
				minX = Math.min(minX, BlockPos.getX(member.position()));
				maxX = Math.max(maxX, BlockPos.getX(member.position()));
				minZ = Math.min(minZ, BlockPos.getZ(member.position()));
				maxZ = Math.max(maxZ, BlockPos.getZ(member.position()));
			}
			return Math.max(maxX - minX, maxZ - minZ) + 1;
		}
	}

	/**
	 * Tests a cluster's members nearest first. A cluster within {@link #SMALL_SIGHT} needs one seen block; farther, it
	 * needs {@link #LARGE_CLUSTER} seen blocks, so one torch in front of a house at 200 blocks is not reported because of
	 * the hidden rooms behind it. Stops once the answer is known or after {@code maxTests} members.
	 */
	static SeenPart seenPart(Cluster cluster, Predicate<Found> sees, ToDoubleFunction<Found> distance, int maxTests) {
		List<Found> seen = new ArrayList<>();
		int tested = 0;
		boolean near = cluster.distance() <= SMALL_SIGHT;
		for (Found member : cluster.members()) {
			if (tested >= maxTests) break;
			if (!near && seen.size() + (cluster.members().size() - tested) < LARGE_CLUSTER) break;
			tested++;
			if (sees.test(member)) seen.add(member);
			if (near && !seen.isEmpty() && seen.size() >= 3) break;
			if (!near && seen.size() >= LARGE_CLUSTER) break;
		}
		List<Found> noticed = seen.stream().filter(member -> distance.applyAsDouble(member) <= noticeableDistance(seen.size())).toList();
		return new SeenPart(noticed, tested);
	}

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static List<Row> lavaRows(Sight sight, List<Cluster> clusters, int limit) {
		List<Row> rows = new ArrayList<>();
		int tests = 0;
		for (Cluster pool : clusters) {
			if (rows.size() >= limit || tests >= 32 || sight.exhausted()) break;
			if (pool.distance() > RANGE || pool.distance() > SMALL_SIGHT && pool.members().size() < LARGE_CLUSTER) continue;
			SeenPart part = seenPart(pool, sight::sees, member -> sight.distance(BlockPos.of(member.position())), 12);
			tests += part.tested();
			if (part.count() == 0) continue;
			BlockPos nearest = BlockPos.of(part.nearest().position());
			rows.add(new Row(Section.BLOCKS, "lava@" + pool.seed(), "minecraft:lava", List.of(), part.count(), 0, Cell.of(nearest),
					sight.distance(nearest)));
		}
		return rows;
	}

	/**
	 * A structure is seen when a clear line from the eye toward one of its pieces (centre, top, or a side facing the eye)
	 * ends on a built block inside that structure that was there since generation (a torch a player put into an ancient
	 * city's box does not name the city). Near ones are named; far ones list the built blocks seen and the size of the
	 * pieces seen.
	 */
	private static List<Row> structureRows(ServerLevel level, Sight sight, List<StartView> starts, int limit) {
		List<Row> rows = new ArrayList<>();
		for (StartView view : starts) {
			if (rows.size() >= limit || sight.exhausted()) break;
			if (view.distance() > sight.range) continue;
			List<StructurePiece> pieces = new ArrayList<>(view.start().getPieces());
			pieces.sort(Comparator.comparingDouble(piece -> boxDistance(sight.eye, piece.getBoundingBox())));
			BlockPos nearest = null;
			Set<String> seenBlocks = new java.util.LinkedHashSet<>();
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
			int tested = 0;
			for (StructurePiece piece : pieces) {
				if (tested >= 6 || sight.exhausted()) break;
				BoundingBox box = piece.getBoundingBox();
				if (boxDistance(sight.eye, box) > sight.range) break;
				tested++;
				for (Vec3 aim : aimPoints(sight.eye, box)) {
					if (!sight.inView(aim)) continue;
					BlockPos end = sight.lineEnd(aim);
					if (end == null || !sight.withinRange(end)) continue;
					if (!insideStructure(view.start(), end)) continue;
					BlockState state = level.getBlockState(end);
					if (!SightedFeatures.built(state, view.label()) || changedSinceLoad(level, end)) continue;
					seenBlocks.add(blockId(state));
					if (nearest == null || sight.distance(end) < sight.distance(nearest)) nearest = end;
					minX = Math.min(minX, box.minX());
					maxX = Math.max(maxX, box.maxX());
					minZ = Math.min(minZ, box.minZ());
					maxZ = Math.max(maxZ, box.maxZ());
					break;
				}
			}
			if (nearest == null) continue;
			double distance = sight.distance(nearest);
			boolean named = distance <= NAMED_STRUCTURE_DISTANCE;
			int size = Math.max(maxX - minX, maxZ - minZ) + 1;
			rows.add(new Row(Section.STRUCTURES, view.key(), named ? view.label() : null,
					named ? List.of() : seenBlocks.stream().limit(3).toList(), 0, size, Cell.of(nearest), distance));
		}
		return rows;
	}

	private static boolean insideStructure(StructureStart start, BlockPos position) {
		if (!start.getBoundingBox().isInside(position)) return false;
		for (StructurePiece piece : start.getPieces()) {
			if (piece.getBoundingBox().isInside(position)) return true;
		}
		return false;
	}

	/** The piece's centre, the middle of its top layer and the middles of the sides that face the eye. */
	static List<Vec3> aimPoints(Vec3 eye, BoundingBox box) {
		double cx = (box.minX() + box.maxX() + 1) / 2.0D, cy = (box.minY() + box.maxY() + 1) / 2.0D, cz = (box.minZ() + box.maxZ() + 1) / 2.0D;
		List<Vec3> points = new ArrayList<>(4);
		points.add(new Vec3(cx, cy, cz));
		points.add(new Vec3(cx, box.maxY() + 0.5D, cz));
		if (eye.x < box.minX()) points.add(new Vec3(box.minX() + 0.5D, cy, cz));
		else if (eye.x > box.maxX() + 1) points.add(new Vec3(box.maxX() + 0.5D, cy, cz));
		if (eye.z < box.minZ()) points.add(new Vec3(cx, cy, box.minZ() + 0.5D));
		else if (eye.z > box.maxZ() + 1) points.add(new Vec3(cx, cy, box.maxZ() + 0.5D));
		return points;
	}

	/** Nether portals glow and span several blocks, so they count as large; beds, bells and workstations are small. */
	private static List<Row> poiRows(Sight sight, List<Found> points, ToDoubleFunction<Found> distance, int limit) {
		Map<String, List<Found>> byType = new LinkedHashMap<>();
		for (Found found : points) byType.computeIfAbsent(blockId(found.state()), ignored -> new ArrayList<>()).add(found);
		List<Cluster> groups = new ArrayList<>();
		for (List<Found> sameType : byType.values()) groups.addAll(cluster(sameType, distance));
		groups.sort(Comparator.comparingDouble(Cluster::distance));
		List<Row> rows = new ArrayList<>();
		int tests = 0;
		for (Cluster group : groups) {
			if (rows.size() >= limit || tests >= 24 || sight.exhausted()) break;
			Found first = group.members().getFirst();
			int reach = first.state().is(Blocks.NETHER_PORTAL) ? RANGE : SMALL_SIGHT;
			if (group.distance() > reach) continue;
			for (Found member : group.members().subList(0, Math.min(3, group.members().size()))) {
				tests++;
				if (!sight.sees(member)) continue;
				BlockPos position = BlockPos.of(member.position());
				rows.add(new Row(Section.POI, "poi@" + group.seed(), blockId(member.state()), List.of(), 0, 0, Cell.of(position),
						distance.applyAsDouble(member)));
				break;
			}
		}
		return rows;
	}

	/**
	 * Clusters of building blocks outside every generated structure: possibly player-built. The row lists the block ids
	 * seen, and its size is the extent of the seen blocks; hidden rooms and back walls count for nothing.
	 */
	private static List<Row> builtRows(Sight sight, List<Cluster> clusters, int limit) {
		List<Row> rows = new ArrayList<>();
		int tests = 0;
		for (Cluster cluster : clusters) {
			if (rows.size() >= limit || tests >= 64 || sight.exhausted()) break;
			if (cluster.distance() > RANGE || cluster.distance() > SMALL_SIGHT && cluster.members().size() < LARGE_CLUSTER) continue;
			SeenPart part = seenPart(cluster, sight::sees, member -> sight.distance(BlockPos.of(member.position())), 16);
			tests += part.tested();
			if (part.count() == 0) continue;
			Map<String, Integer> ids = new LinkedHashMap<>();
			for (Found member : part.seen()) ids.merge(blockId(member.state()), 1, Integer::sum);
			BlockPos nearest = BlockPos.of(part.nearest().position());
			rows.add(new Row(Section.BUILT, "built@" + cluster.seed(), null,
					ids.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).map(Map.Entry::getKey).limit(3).toList(),
					0, part.horizontalSize(), Cell.of(nearest), sight.distance(nearest)));
		}
		return rows;
	}

	private static List<Row> notableRows(Sight sight, List<Found> blocks, ToDoubleFunction<Found> distance, int limit) {
		List<Row> rows = new ArrayList<>();
		int tests = 0;
		for (Found found : blocks.stream().sorted(Comparator.comparingDouble(distance)).toList()) {
			if (rows.size() >= limit || tests >= 16 || sight.exhausted()) break;
			if (distance.applyAsDouble(found) > SMALL_SIGHT) break;
			tests++;
			if (!sight.sees(found)) continue;
			BlockPos position = BlockPos.of(found.position());
			rows.add(new Row(Section.BLOCKS, "block@" + position.asLong(), blockId(found.state()), List.of(), 1, 0, Cell.of(position),
					distance.applyAsDouble(found)));
		}
		return rows;
	}

	/**
	 * Exposed blocks of one id within {@link #SMALL_SIGHT}, sections nearest the eye first. Each section's result is kept
	 * with that section's scan until its blocks change, and reading a section uses the level's shared per-tick budget, so
	 * any number of searches cannot add more than that budget to one tick. Returns false while sections are still unread.
	 */
	private static boolean search(ServerLevel level, LevelIndex index, Vec3 eye, Block block, List<Found> found) {
		BlockPos center = BlockPos.containing(eye);
		record SectionRef(LevelChunk chunk, int index, double distance) {
		}
		List<SectionRef> sections = new ArrayList<>();
		for (int cx = (center.getX() - SMALL_SIGHT) >> 4; cx <= (center.getX() + SMALL_SIGHT) >> 4; cx++) {
			for (int cz = (center.getZ() - SMALL_SIGHT) >> 4; cz <= (center.getZ() + SMALL_SIGHT) >> 4; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) continue;
				for (int sectionIndex = 0; sectionIndex < chunk.getSections().length; sectionIndex++) {
					int minY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
					if (minY + 15 < center.getY() - SMALL_SIGHT || minY > center.getY() + SMALL_SIGHT) continue;
					sections.add(new SectionRef(chunk, sectionIndex, eye.distanceTo(new Vec3((cx << 4) + 8, minY + 8, (cz << 4) + 8))));
				}
			}
		}
		sections.sort(Comparator.comparingDouble(SectionRef::distance));
		boolean complete = true;
		long tick = level.getGameTime();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (SectionRef ref : sections) {
			ChunkScan scan = index.scanOf(ref.chunk());
			SectionScan section = index.section(ref.chunk(), scan, ref.index());
			List<Found> cached = section.searches.get(block);
			if (cached == null) {
				if (!index.takeCheck(tick)) {
					complete = false;
					continue;
				}
				LevelChunkSection blocks = ref.chunk().getSections()[ref.index()];
				if (blocks.hasOnlyAir() || !blocks.maybeHas(state -> state.is(block))) {
					cached = List.of();
				} else {
					if (!index.takeScan(tick)) {
						complete = false;
						continue;
					}
					cached = new ArrayList<>();
					int baseX = ref.chunk().getPos().getMinBlockX(), baseY = ref.chunk().getSectionYFromSectionIndex(ref.index()) << 4;
					int baseZ = ref.chunk().getPos().getMinBlockZ();
					for (int y = 0; y < 16 && cached.size() < MAX_ENTRIES_PER_SECTION; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
						BlockState state = blocks.getBlockState(x, y, z);
						if (!state.is(block)) continue;
						int open = openFaces(level, ref.chunk(), cursor, baseX + x, baseY + y, baseZ + z, state);
						if (open != 0 && cached.size() < MAX_ENTRIES_PER_SECTION) cached.add(new Found(BlockPos.asLong(baseX + x, baseY + y, baseZ + z), state, 0, open));
					}
				}
				section.searches.put(block, cached);
			}
			for (Found match : cached) {
				if (Math.abs(BlockPos.getX(match.position()) - center.getX()) <= SMALL_SIGHT && Math.abs(BlockPos.getY(match.position()) - center.getY()) <= SMALL_SIGHT
						&& Math.abs(BlockPos.getZ(match.position()) - center.getZ()) <= SMALL_SIGHT) found.add(match);
			}
		}
		return complete;
	}

	/** One row per searched id: the nearest seen block and how many were seen (up to 16 tests). */
	private static List<Row> searchedRows(Sight sight, Block block, List<Found> matches, ToDoubleFunction<Found> distance) {
		int visible = 0;
		BlockPos nearest = null;
		int tests = 0;
		for (Found found : matches.stream().sorted(Comparator.comparingDouble(distance)).toList()) {
			if (tests++ >= 16 || sight.exhausted()) break;
			if (distance.applyAsDouble(found) > SMALL_SIGHT) break;
			if (!sight.sees(found)) continue;
			visible++;
			if (nearest == null) nearest = BlockPos.of(found.position());
		}
		if (nearest == null) return List.of();
		String id = BuiltInRegistries.BLOCK.getKey(block).toString();
		return List.of(new Row(Section.BLOCKS, "search:" + id, id, List.of(), visible, 0, Cell.of(nearest), sight.distance(nearest)));
	}

	static String biomeAt(ServerLevel level, BlockPos position) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
		if (chunk == null) return null;
		return chunk.getNoiseBiome(QuartPos.fromBlock(position.getX()), QuartPos.fromBlock(position.getY()), QuartPos.fromBlock(position.getZ()))
				.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
	}

	/** Each loaded chunk's middle surface block, grouped by its biome. */
	private static Map<String, List<BlockPos>> biomeColumns(ServerLevel level, List<LevelChunk> loaded) {
		Map<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>, List<BlockPos>> byHolder = new LinkedHashMap<>();
		for (LevelChunk chunk : loaded) {
			int x = chunk.getPos().getMiddleBlockX(), z = chunk.getPos().getMiddleBlockZ();
			int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			if (top < level.getMinY()) continue;
			byHolder.computeIfAbsent(chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(top), QuartPos.fromBlock(z)),
					ignored -> new ArrayList<>()).add(new BlockPos(x, top, z));
		}
		Map<String, List<BlockPos>> columns = new LinkedHashMap<>();
		byHolder.forEach((holder, surfaces) -> holder.unwrapKey().ifPresent(key -> columns.put(key.identifier().toString(), surfaces)));
		return columns;
	}

	/**
	 * Biome regions other than the one the agent stands in, by the nearest visible ground: per biome the nearest surface
	 * columns within range are tested until one is in clear sight.
	 */
	private static List<Row> biomeRows(Sight sight, Map<String, List<BlockPos>> columns, String standingIn, int limit) {
		List<Row> rows = new ArrayList<>();
		List<Map.Entry<String, List<BlockPos>>> biomes = new ArrayList<>();
		for (var entry : columns.entrySet()) {
			if (entry.getKey().equals(standingIn)) continue;
			List<BlockPos> surfaces = new ArrayList<>(entry.getValue().stream().filter(sight::withinRange).toList());
			if (surfaces.isEmpty()) continue;
			surfaces.sort(Comparator.comparingDouble(sight::distance));
			biomes.add(Map.entry(entry.getKey(), surfaces));
		}
		biomes.sort(Comparator.comparingDouble(entry -> sight.distance(entry.getValue().getFirst())));
		int tests = 0;
		for (var entry : biomes) {
			if (rows.size() >= limit || tests >= 32 || sight.exhausted()) break;
			int biomeTests = 0;
			for (BlockPos surface : entry.getValue()) {
				if (biomeTests++ >= 4 || sight.exhausted()) break;
				tests++;
				Vec3 aim = new Vec3(surface.getX() + 0.5D, surface.getY() + 0.9D, surface.getZ() + 0.5D);
				if (!sight.inView(aim)) continue;
				BlockPos end = sight.lineEnd(aim);
				// The ground of that region is seen when the line ends on the surface near the sampled column.
				if (end == null || Math.abs(end.getX() - surface.getX()) > 4 || Math.abs(end.getZ() - surface.getZ()) > 4
						|| Math.abs(end.getY() - surface.getY()) > 2) continue;
				rows.add(new Row(Section.BIOMES, "biome:" + entry.getKey(), entry.getKey(), List.of(), 0, 0, Cell.of(end), sight.distance(end)));
				break;
			}
		}
		return rows;
	}

	// ---- Rendering --------------------------------------------------------------------------------------------------

	/** A short stable id for a row's key, so a sweep can tell repeated sightings of one thing from distinct things. */
	static String rowId(String key) {
		return Integer.toUnsignedString(key.hashCode(), 36);
	}

	/**
	 * Adds rows to {@code target} per section with bearings for the live view. {@code show} filters rows (passive updates
	 * keep only recent sightings) and {@code isNew} marks first sightings; at most {@code limit} rows per section. Survey
	 * rows carry an {@code id} so lookAround can merge headings.
	 */
	static void render(JsonObject target, List<Row> rows, Vec3 eye, float yaw, Predicate<Row> show, Predicate<Row> isNew,
			Map<Section, Integer> limits, boolean withIds) {
		Map<Section, JsonArray> sections = new LinkedHashMap<>();
		for (Row row : rows) {
			if (!show.test(row)) continue;
			JsonArray array = sections.computeIfAbsent(row.section(), ignored -> new JsonArray());
			if (array.size() >= limits.getOrDefault(row.section(), MAX_ROWS)) continue;
			JsonObject json = new JsonObject();
			if (withIds) json.addProperty("id", rowId(row.key()));
			switch (row.section()) {
				case STRUCTURES -> {
					if (row.label() != null) json.addProperty("structure", row.label());
					else json.add("blocks", strings(row.blocks()));
					json.addProperty("size", row.size());
				}
				case BUILT -> {
					json.add("blocks", strings(row.blocks()));
					json.addProperty("size", row.size());
				}
				case BIOMES -> json.addProperty("biome", row.label());
				case POI -> json.addProperty("blockId", row.label());
				case BLOCKS -> {
					json.addProperty("blockId", row.label());
					if (row.count() > 1) json.addProperty("count", row.count());
				}
				default -> {
					continue;
				}
			}
			json.addProperty("x", row.cell().x());
			json.addProperty("y", row.cell().y());
			json.addProperty("z", row.cell().z());
			json.addProperty("distance", Math.round(row.distance()));
			json.addProperty("bearing", SightedFeatures.bearing(eye, yaw, row.cell()));
			if (isNew.test(row)) json.addProperty("new", true);
			array.add(json);
		}
		for (var entry : sections.entrySet()) if (!entry.getValue().isEmpty()) target.add(entry.getKey().key, entry.getValue());
	}

	private static JsonArray strings(List<String> values) {
		JsonArray array = new JsonArray();
		values.forEach(array::add);
		return array;
	}

	/** Parses survey include/exclude entries; unknown sections, malformed or terrain block ids are rejected. */
	static Request surveyRequest(List<String> include, List<String> exclude, int rows) {
		EnumSet<Section> sections = include.isEmpty() ? EnumSet.allOf(Section.class) : EnumSet.noneOf(Section.class);
		List<Block> searched = new ArrayList<>();
		for (String entry : include) {
			if (entry.startsWith("blocks:")) {
				String id = entry.substring("blocks:".length());
				var key = net.minecraft.resources.Identifier.tryParse(id);
				if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
					throw new AgentDomainException("INVALID_INSPECTION", "Unknown block id in include: " + id);
				}
				Block block = BuiltInRegistries.BLOCK.getValue(key);
				if (!searchable(block)) {
					throw new AgentDomainException("INVALID_INSPECTION", id + " is terrain that fills whole areas; landmarks and blocks already show it");
				}
				if (!searched.contains(block)) searched.add(block);
				sections.add(Section.BLOCKS);
				continue;
			}
			Section section = Section.of(entry);
			if (section == null) throw new AgentDomainException("INVALID_INSPECTION", "Unknown survey section: " + entry);
			sections.add(section);
		}
		for (String entry : exclude) {
			Section section = Section.of(entry);
			if (section == null) throw new AgentDomainException("INVALID_INSPECTION", "Unknown survey section: " + entry);
			sections.remove(section);
		}
		if (searched.size() > MAX_SEARCHED_BLOCKS) {
			throw new AgentDomainException("INVALID_INSPECTION", "At most 4 blocks:<id> searches");
		}
		return new Request(sections, searched, rows, SURVEY_CLIPS);
	}
}
