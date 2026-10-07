package dev.agaminggod.arenaagents.server.perception;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.world.WorldMutationRevisionAccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.AmbientMoodSettings;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.core.Direction;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * What the agent hears, as a player with sound on would. Facts only: nothing here acts or wakes anything by itself.
 * <p>
 * Two sources. (1) Sound packets the server addresses to this player's connection (mob ambient/hurt/step sounds,
 * explosions, doors, other players' block breaks, fuses, arrows...), captured by PlayerObservationEventsMixin.
 * (2) Client-only ambience the server never sends: a real client's {@code ClientLevel.animateTick} rolls 667
 * samples per tick at triangular offsets (radius 16 and 32) around the player's block and calls each block's
 * {@code animateTick}, whose vanilla chance plays lava pops, flowing water, bubble columns, fire, campfires, portals
 * and dripstone drips; {@code BiomeAmbientSoundsHandler} plays cave mood in the dark. We compute each block's
 * expected sounds per tick from those exact rules and emit a sound whenever the accumulated expectation reaches one,
 * so the agent hears lava as often as a player does on average, deterministically and without per-tick work.
 * <p>
 * Entries keep the source position privately and report only what a listener perceives: direction relative to the
 * current facing, elevation, rounded distance and a repeat count, merged per source and kept 5 s (lava 30 s: a player
 * remembers hearing lava nearby).
 */
public final class HearingPerception {
	public static final int WINDOW_TICKS = 100;
	/** Lava is remembered longer, which also keeps an intermittent pop from re-raising attention every few seconds. */
	public static final int LAVA_MEMORY_TICKS = 600;
	public static final int MAX_REPORTED = 6;
	static final int AMBIENT_RADIUS = 16;
	/** Rescans after moving or a nearby block change, at most this often. */
	static final int MIN_RESCAN_TICKS = 20;
	/** Rescans a stationary listener this often so unrecorded changes (fire spread) are picked up. */
	static final int MAX_SCAN_AGE_TICKS = 100;
	static final int MAX_SOURCES = 512;
	private static final int MAX_ENTRIES = 48;
	private static final int MOOD_SAMPLES_PER_UPDATE = 20;
	private static final String[] DIRECTIONS = { "front", "front_right", "right", "back_right", "back", "back_left", "left", "front_left" };

	static final String LAVA_POP = "minecraft:block.lava.pop";
	static final String LAVA_AMBIENT = "minecraft:block.lava.ambient";
	static final String WATER_AMBIENT = "minecraft:block.water.ambient";
	static final String BUBBLE_WHIRLPOOL = "minecraft:block.bubble_column.whirlpool_ambient";
	static final String BUBBLE_UPWARDS = "minecraft:block.bubble_column.upwards_ambient";
	static final String FIRE_AMBIENT = "minecraft:block.fire.ambient";
	static final String CAMPFIRE_CRACKLE = "minecraft:block.campfire.crackle";
	static final String PORTAL_AMBIENT = "minecraft:block.portal.ambient";
	static final String DRIP_LAVA = "minecraft:block.pointed_dripstone.drip_lava";
	static final String DRIP_WATER = "minecraft:block.pointed_dripstone.drip_water";

	private static final Map<ServerPlayer, Listener> LISTENERS = new WeakHashMap<>();

	private HearingPerception() { }

	/** One client-only sound emitter: chance per animateTick sample, where the sound plays and its volume. */
	record AmbientSource(String sound, int x, int y, int z, double chance, double soundX, double soundY, double soundZ, float volume) { }

	/** A server sound packet addressed to this player. Called on the server thread. */
	static void heardPacket(ServerPlayer player, String soundId, String category, Entity source, double x, double y, double z,
			float volume, double range) {
		double dx = x - player.getX();
		double dy = y - player.getEyeY();
		double dz = z - player.getZ();
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (!(volume > 0) || !(range > 0) || distance > range) return;
		String sourceType = source == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(source.getType()).toString();
		String key = soundId + (source != null ? "#" + source.getId() : "@" + cell(x, 8) + "," + cell(y, 8) + "," + cell(z, 8));
		long tick = player.level().getGameTime();
		listener(player).at(player.level().dimension().identifier().toString())
				.record(key, soundId, sourceType, x, y, z, loudness(volume, distance, range), weight(soundId, category), tick, 1);
	}

	/** Brings the ambient emulation up to date and returns the most salient heard sounds (empty when silent). */
	public static JsonArray observe(ServerPlayer player) {
		Listener listener = update(player);
		return listener.top(player.getX(), player.getEyeY(), player.getZ(), player.getYRot(), player.level().getGameTime(), MAX_REPORTED);
	}

	/** Whether lava this agent currently hears (or remembers hearing) lies within {@code radius} of the block. */
	public static boolean lavaHeardNear(ServerPlayer player, BlockPos target, double radius) {
		return update(player).lavaHeardNear(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, radius,
				player.level().getGameTime());
	}

	private static Listener update(ServerPlayer player) {
		ServerLevel level = player.level();
		long tick = level.getGameTime();
		Listener listener = listener(player).at(level.dimension().identifier().toString());
		BlockPos center = player.blockPosition();
		long revision = level instanceof WorldMutationRevisionAccess access
				? access.arenaagents$worldMutationRevision(center, AMBIENT_RADIUS) : tick;
		if (listener.needsScan(center.asLong(), revision, tick)) {
			listener.scanned(center.getX(), center.getY(), center.getZ(), revision, tick, scan(level, center));
		}
		listener.accumulate(tick, player.getX(), player.getEyeY(), player.getZ());
		mood(level, player, listener, tick);
		listener.expire(tick);
		return listener;
	}

	private static Listener listener(ServerPlayer player) {
		synchronized (LISTENERS) { return LISTENERS.computeIfAbsent(player, ignored -> new Listener(player.getUUID().getLeastSignificantBits())); }
	}

	/** Walks only chunk sections whose palette can hold an emitter, within the client's audible 16-block reach. */
	static List<AmbientSource> scan(ServerLevel level, BlockPos center) {
		ArrayList<AmbientSource> sources = new ArrayList<>();
		boolean dryDripLava = level.environmentAttributes().getValue(EnvironmentAttributes.DEFAULT_DRIPSTONE_PARTICLE, center)
				== ParticleTypes.DRIPPING_DRIPSTONE_LAVA;
		boolean mudEvaporates = level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, center);
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		StateLookup lookup = (x, y, z) -> level.getBlockState(cursor.set(x, y, z));
		int r = AMBIENT_RADIUS;
		int minSection = Math.max(level.getMinSectionY(), (center.getY() - r) >> 4);
		int maxSection = Math.min(level.getMaxSectionY(), (center.getY() + r) >> 4);
		for (int chunkX = (center.getX() - r) >> 4; chunkX <= (center.getX() + r) >> 4; chunkX++) {
			for (int chunkZ = (center.getZ() - r) >> 4; chunkZ <= (center.getZ() + r) >> 4; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) continue;
				for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
					LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sectionY));
					if (section.hasOnlyAir() || !section.maybeHas(HearingPerception::mayEmit)) continue;
					int x0 = Math.max(chunkX << 4, center.getX() - r), x1 = Math.min((chunkX << 4) + 15, center.getX() + r);
					int y0 = Math.max(sectionY << 4, center.getY() - r), y1 = Math.min((sectionY << 4) + 15, center.getY() + r);
					int z0 = Math.max(chunkZ << 4, center.getZ() - r), z1 = Math.min((chunkZ << 4) + 15, center.getZ() + r);
					for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
						BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
						if (mayEmit(state)) classify(state, x, y, z, lookup, dryDripLava, mudEvaporates, sources);
					}
				}
			}
		}
		return sources;
	}

	interface StateLookup {
		BlockState get(int x, int y, int z);
	}

	/** States whose client animateTick can play a sound; a cheap palette filter before reading any neighbour. */
	static boolean mayEmit(BlockState state) {
		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty()) {
			if (Fluids.LAVA.isSame(fluid.getType())) return true;
			if (Fluids.WATER.isSame(fluid.getType()) && flowingNotFalling(fluid)) return true;
		}
		return state.is(Blocks.BUBBLE_COLUMN) || state.getBlock() instanceof BaseFireBlock || state.is(Blocks.NETHER_PORTAL)
				|| state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT)
				|| PointedDripstoneBlock.canDrip(state);
	}

	private static boolean flowingNotFalling(FluidState fluid) {
		return !fluid.isSource() && !(fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING));
	}

	/** The vanilla client chance rules (LavaFluid, WaterFluid, BubbleColumn, BaseFire, Campfire, NetherPortal, PointedDripstone). */
	static void classify(BlockState state, int x, int y, int z, StateLookup lookup, boolean dryDripLava, boolean mudEvaporates,
			List<AmbientSource> out) {
		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty() && Fluids.LAVA.isSame(fluid.getType())) {
			// LavaFluid.animateTick: only lava open to air above pops (1/100) and rumbles (1/200), at 0.2-0.4 volume.
			if (lookup.get(x, y + 1, z).isAir()) {
				out.add(new AmbientSource(LAVA_POP, x, y, z, 1.0D / 100.0D, x + 0.5D, y + 1.0D, z + 0.5D, 0.3F));
				out.add(new AmbientSource(LAVA_AMBIENT, x, y, z, 1.0D / 200.0D, x, y, z, 0.3F));
			}
		} else if (!fluid.isEmpty() && Fluids.WATER.isSame(fluid.getType()) && flowingNotFalling(fluid)) {
			out.add(new AmbientSource(WATER_AMBIENT, x, y, z, 1.0D / 64.0D, x + 0.5D, y + 0.5D, z + 0.5D, 0.875F));
		}
		if (state.is(Blocks.BUBBLE_COLUMN)) {
			boolean down = state.getValue(BubbleColumnBlock.DRAG_DOWN);
			out.add(new AmbientSource(down ? BUBBLE_WHIRLPOOL : BUBBLE_UPWARDS, x, y, z, 1.0D / 200.0D, x + 0.5D, y + 0.5D, z + 0.5D, 0.3F));
		} else if (state.getBlock() instanceof BaseFireBlock) {
			out.add(new AmbientSource(FIRE_AMBIENT, x, y, z, 1.0D / 24.0D, x + 0.5D, y + 0.5D, z + 0.5D, 1.5F));
		} else if (state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT)) {
			out.add(new AmbientSource(CAMPFIRE_CRACKLE, x, y, z, 1.0D / 10.0D, x + 0.5D, y + 0.5D, z + 0.5D, 1.0F));
		} else if (state.is(Blocks.NETHER_PORTAL)) {
			out.add(new AmbientSource(PORTAL_AMBIENT, x, y, z, 1.0D / 100.0D, x + 0.5D, y + 0.5D, z + 0.5D, 0.5F));
		} else if (PointedDripstoneBlock.canDrip(state)) {
			dripstone(x, y, z, lookup, dryDripLava, mudEvaporates, out);
		}
	}

	/**
	 * PointedDripstoneBlock.animateTick: a stalactite tip drips (12% of samples) when lava or water sits above its root
	 * within 11 blocks, else 2% with the dimension's dry drip; the drip sounds where the drop lands.
	 */
	private static void dripstone(int x, int y, int z, StateLookup lookup, boolean dryDripLava, boolean mudEvaporates,
			List<AmbientSource> out) {
		int rootY = y;
		boolean found = false;
		for (int step = 1; step <= 11; step++) {
			BlockState above = lookup.get(x, y + step, z);
			if (above.is(Blocks.POINTED_DRIPSTONE) && above.getValue(PointedDripstoneBlock.TIP_DIRECTION) == Direction.DOWN) {
				rootY = y + step;
				continue;
			}
			found = !above.is(Blocks.POINTED_DRIPSTONE);
			break;
		}
		if (!found) return;
		BlockState source = lookup.get(x, rootY + 1, z);
		FluidState fluid = source.getFluidState();
		boolean lava = !fluid.isEmpty() && Fluids.LAVA.isSame(fluid.getType());
		boolean water = source.is(Blocks.MUD) && !mudEvaporates || !fluid.isEmpty() && Fluids.WATER.isSame(fluid.getType());
		double chance = lava || water ? 0.12D : 0.02D;
		String sound = lava || !water && dryDripLava ? DRIP_LAVA : DRIP_WATER;
		int landY = y - 1;
		while (landY > y - 32 && lookup.get(x, landY, z).isAir()) landY--;
		if (landY <= y - 32) return;
		out.add(new AmbientSource(sound, x, y, z, chance, x + 0.5D, landY + 1.0D, z + 0.5D, 0.65F));
	}

	private static void mood(ServerLevel level, ServerPlayer player, Listener listener, long tick) {
		AmbientMoodSettings mood = level.environmentAttributes().getValue(EnvironmentAttributes.AMBIENT_SOUNDS, player.position()).mood().orElse(null);
		long elapsed = listener.moodElapsed(tick);
		if (mood == null || elapsed <= 0) return;
		int samples = (int) Math.min(elapsed, MOOD_SAMPLES_PER_UPDATE);
		double ticksPerSample = (double) elapsed / samples;
		int extent = mood.blockSearchExtent();
		int span = extent * 2 + 1;
		BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
		for (int index = 0; index < samples; index++) {
			sample.set(BlockPos.containing(player.getX() + listener.random.nextInt(span) - extent,
					player.getEyeY() + listener.random.nextInt(span) - extent, player.getZ() + listener.random.nextInt(span) - extent));
			if (!level.hasChunkAt(sample)) continue;
			int sky = level.getBrightness(LightLayer.SKY, sample);
			int block = sky > 0 ? 0 : level.getBrightness(LightLayer.BLOCK, sample);
			if (listener.moodStep(sky, block, mood.tickDelay(), ticksPerSample)) {
				// BiomeAmbientSoundsHandler places the mood sound past the dark block it sampled.
				double dx = sample.getX() + 0.5D - player.getX();
				double dy = sample.getY() + 0.5D - player.getEyeY();
				double dz = sample.getZ() + 0.5D - player.getZ();
				double length = Math.max(1.0E-3D, Math.sqrt(dx * dx + dy * dy + dz * dz));
				double reach = (length + mood.soundPositionOffset()) / length;
				String sound = mood.soundEvent().value().location().toString();
				listener.record("mood", sound, null, player.getX() + dx * reach, player.getEyeY() + dy * reach, player.getZ() + dz * reach,
						0.5D, 0.5D, tick, 1);
			}
		}
	}

	// ---- pure helpers (verified without a world) ----

	/**
	 * Expected client animateTick samples per tick landing on a block at this offset from the listener's block:
	 * per pass, offset = nextInt(r) - nextInt(r) on each axis, so P(d) = (r - |d|) / r^2; 667 passes at r=16 and r=32.
	 */
	static double sampleRate(int dx, int dy, int dz) {
		return 667.0D * (triangle(dx, 16) * triangle(dy, 16) * triangle(dz, 16) + triangle(dx, 32) * triangle(dy, 32) * triangle(dz, 32));
	}

	private static double triangle(int offset, int radius) {
		return Math.max(0, radius - Math.abs(offset)) / (double) (radius * radius);
	}

	/** Linear attenuation as the client's sound engine applies it (full at the source, silent at the range). */
	static double loudness(float volume, double distance, double range) {
		return Math.max(0.0D, Math.min(1.0D, volume) * (1.0D - distance / range));
	}

	/** Sound range for a volume, as SoundEvent.getRange: 16 blocks, more only for volumes above one. */
	static double range(float volume) {
		return volume > 1.0F ? 16.0F * volume : 16.0D;
	}

	static double weight(String soundId, String category) {
		if (isLava(soundId) || soundId.contains("explode") || soundId.contains("primed") || soundId.contains("fire.ambient")
				|| soundId.contains("warden") || soundId.contains(".shoot")) return 3.0D;
		return switch (category == null ? "" : category) {
			case "hostile" -> 3.0D;
			case "player", "players" -> 2.0D;
			case "block", "blocks", "neutral", "ambient" -> 1.0D;
			default -> 0.5D;
		};
	}

	static boolean isLava(String soundId) {
		return soundId.contains("lava");
	}

	static String direction(double dx, double dz, float yaw) {
		if (dx * dx + dz * dz < 1.0D) return "here";
		double bearing = Math.toDegrees(Math.atan2(-dx, dz)) - yaw;
		return DIRECTIONS[Math.floorMod((int) Math.round(bearing / 45.0D), DIRECTIONS.length)];
	}

	/** Relative to the eyes, banded around the body: a sound at the listener's own floor (a mob's feet) is level. */
	static String elevation(double dy) {
		return dy > 0.9D ? "above" : dy < -2.1D ? "below" : "level";
	}

	private static int cell(double value, int size) {
		return Math.floorDiv((int) Math.floor(value), size);
	}

	static String shortSound(String soundId) {
		return soundId.startsWith("minecraft:") ? soundId.substring("minecraft:".length()) : soundId;
	}

	/** Per-player hearing state: merged recent sounds plus the ambient emulation cache and accumulators. */
	static final class Listener {
		private final LinkedHashMap<String, Heard> heard = new LinkedHashMap<>();
		private final Map<String, Double> accumulators = new HashMap<>();
		private List<Group> groups = List.of();
		private List<AmbientSource> sources = List.of();
		private String dimension;
		private long scanCenter = Long.MIN_VALUE;
		private long scanRevision = Long.MIN_VALUE;
		private long scanTick = Long.MIN_VALUE;
		private long accumulatedTick = Long.MIN_VALUE;
		private long moodTick = Long.MIN_VALUE;
		private double moodiness;
		private double pending;
		final Random random;
		int scans;

		Listener(long seed) { random = new Random(seed); }

		/** Hearing resets with the dimension, as a client does when it changes levels. */
		Listener at(String dimension) {
			if (!dimension.equals(this.dimension)) {
				this.dimension = dimension;
				heard.clear();
				accumulators.clear();
				groups = List.of();
				sources = List.of();
				scanTick = scanCenter = scanRevision = accumulatedTick = moodTick = Long.MIN_VALUE;
				moodiness = 0.0D;
				pending = 0.0D;
			}
			return this;
		}

		boolean needsScan(long center, long revision, long tick) {
			if (scanTick == Long.MIN_VALUE || tick < scanTick || tick - scanTick >= MAX_SCAN_AGE_TICKS) return true;
			return (center != scanCenter || revision != scanRevision) && tick - scanTick >= MIN_RESCAN_TICKS;
		}

		/** Groups emitters per sound and 4-block cell; a cell's rate is the sum of its blocks' expected sounds per tick. */
		void scanned(int centerX, int centerY, int centerZ, long revision, long tick, List<AmbientSource> found) {
			scans++;
			scanCenter = BlockPos.asLong(centerX, centerY, centerZ);
			scanRevision = revision;
			scanTick = tick;
			ArrayList<AmbientSource> ranked = new ArrayList<>(found);
			ranked.sort(Comparator.comparingDouble((AmbientSource source) -> -source.chance()
					* sampleRate(source.x() - centerX, source.y() - centerY, source.z() - centerZ)));
			if (ranked.size() > MAX_SOURCES) ranked.subList(MAX_SOURCES, ranked.size()).clear();
			LinkedHashMap<String, Group> grouped = new LinkedHashMap<>();
			for (AmbientSource source : ranked) {
				double rate = source.chance() * sampleRate(source.x() - centerX, source.y() - centerY, source.z() - centerZ);
				if (rate <= 0.0D) continue;
				String key = groupKey(source);
				Group group = grouped.computeIfAbsent(key, ignored -> new Group(key, source));
				group.rate += rate;
			}
			groups = List.copyOf(grouped.values());
			sources = List.copyOf(ranked);
			accumulators.keySet().retainAll(grouped.keySet());
		}

		private static String groupKey(AmbientSource source) {
			return source.sound() + "@" + (source.x() >> 2) + "," + (source.y() >> 2) + "," + (source.z() >> 2);
		}

		/**
		 * Adds the expected sounds since the last update (capped at the window). Each whole expected sound across all
		 * audible emitters is heard, attributed to the cell owed the most (deficit round-robin), so the total matches
		 * the client's rate even when lava is spread thinly over many cells.
		 */
		void accumulate(long tick, double listenerX, double listenerEyeY, double listenerZ) {
			long elapsed = accumulatedTick == Long.MIN_VALUE || tick < accumulatedTick ? 0 : Math.min(WINDOW_TICKS, tick - accumulatedTick);
			accumulatedTick = tick;
			if (elapsed == 0) return;
			ArrayList<Group> audible = new ArrayList<>();
			ArrayList<Double> distances = new ArrayList<>();
			for (Group group : groups) {
				AmbientSource at = group.loudest;
				double dx = at.soundX() - listenerX, dy = at.soundY() - listenerEyeY, dz = at.soundZ() - listenerZ;
				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (distance > range(at.volume())) continue;
				audible.add(group);
				distances.add(distance);
				accumulators.merge(group.key, group.rate * elapsed, Double::sum);
				pending += group.rate * elapsed;
			}
			if (audible.isEmpty()) {
				pending = Math.min(pending, 1.0D);
				return;
			}
			int[] counts = new int[audible.size()];
			for (int emitted = 0; pending >= 1.0D && emitted < MAX_ENTRIES; emitted++) {
				int owed = 0;
				for (int index = 1; index < audible.size(); index++) {
					if (accumulators.get(audible.get(index).key) > accumulators.get(audible.get(owed).key)) owed = index;
				}
				accumulators.merge(audible.get(owed).key, -1.0D, Double::sum);
				counts[owed]++;
				pending -= 1.0D;
			}
			pending = Math.min(pending, 1.0D);
			for (int index = 0; index < audible.size(); index++) {
				if (counts[index] == 0) continue;
				AmbientSource at = audible.get(index).loudest;
				record("ambient:" + audible.get(index).key, at.sound(), null, at.soundX(), at.soundY(), at.soundZ(),
						loudness(at.volume(), distances.get(index), range(at.volume())), weight(at.sound(), "blocks"), tick, counts[index]);
			}
		}

		long moodElapsed(long tick) {
			long elapsed = moodTick == Long.MIN_VALUE || tick < moodTick ? 0 : Math.min(WINDOW_TICKS, tick - moodTick);
			moodTick = tick;
			return elapsed;
		}

		/** One BiomeAmbientSoundsHandler mood step scaled to the ticks it stands for; true when the mood sound plays. */
		boolean moodStep(int skyLight, int blockLight, int tickDelay, double ticks) {
			if (skyLight > 0) moodiness -= skyLight / 15.0D * 0.001D * ticks;
			else moodiness -= (blockLight - 1) / (double) tickDelay * ticks;
			if (moodiness >= 1.0D) {
				moodiness = 0.0D;
				return true;
			}
			moodiness = Math.max(moodiness, 0.0D);
			return false;
		}

		void record(String key, String sound, String source, double x, double y, double z, double loudness, double weight,
				long tick, int count) {
			Heard entry = heard.get(key);
			if (entry == null || tick - entry.tick > retention(entry.sound)) {
				heard.remove(key);
				entry = new Heard(sound, source);
				heard.put(key, entry);
			}
			entry.x = x;
			entry.y = y;
			entry.z = z;
			entry.count += count;
			entry.loudness = entry.tick == tick ? Math.max(entry.loudness, loudness) : loudness;
			entry.weight = weight;
			entry.tick = tick;
			while (heard.size() > MAX_ENTRIES) heard.remove(heard.keySet().iterator().next());
		}

		void expire(long tick) {
			for (Iterator<Heard> iterator = heard.values().iterator(); iterator.hasNext(); ) {
				Heard entry = iterator.next();
				if (tick - entry.tick > retention(entry.sound) || tick < entry.tick) iterator.remove();
			}
		}

		private static long retention(String sound) {
			return isLava(sound) ? LAVA_MEMORY_TICKS : WINDOW_TICKS;
		}

		boolean lavaHeardNear(double x, double y, double z, double radius, long tick) {
			for (Map.Entry<String, Heard> entry : heard.entrySet()) {
				Heard value = entry.getValue();
				if (!isLava(value.sound) || tick - value.tick > LAVA_MEMORY_TICKS) continue;
				if (near(value.x, value.y, value.z, x, y, z, radius)) return true;
				if (!entry.getKey().startsWith("ambient:")) continue;
				// An ambient group spans a 4-block cell: check every lava emitter that fed it.
				String group = entry.getKey().substring("ambient:".length());
				for (AmbientSource source : sources) {
					if (isLava(source.sound()) && groupKey(source).equals(group)
							&& near(source.x() + 0.5D, source.y() + 0.5D, source.z() + 0.5D, x, y, z, radius)) return true;
				}
			}
			return false;
		}

		private static boolean near(double ax, double ay, double az, double bx, double by, double bz, double radius) {
			double dx = ax - bx, dy = ay - by, dz = az - bz;
			return dx * dx + dy * dy + dz * dz <= radius * radius;
		}

		/** Most salient first: danger weight, loudness, recency and repetition. Relative to the current pose. */
		JsonArray top(double listenerX, double listenerEyeY, double listenerZ, float yaw, long tick, int limit) {
			record Ranked(Heard heard, double salience, double dx, double dy, double dz, double distance) { }
			ArrayList<Ranked> ranked = new ArrayList<>();
			for (Heard entry : heard.values()) {
				long age = Math.max(0, tick - entry.tick);
				if (age > retention(entry.sound)) continue;
				double dx = entry.x - listenerX, dy = entry.y - listenerEyeY, dz = entry.z - listenerZ;
				double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
				double recency = 1.0D - 0.5D * Math.min(1.0D, age / (double) retention(entry.sound));
				double salience = entry.weight * (0.25D + entry.loudness) * recency * (1.0D + 0.15D * Math.min(4, entry.count - 1));
				ranked.add(new Ranked(entry, salience, dx, dy, dz, distance));
			}
			ranked.sort(Comparator.comparingDouble(Ranked::salience).reversed());
			// What a listener can tell apart: the same sound from the same direction and height reads as one entry.
			LinkedHashMap<String, JsonObject> merged = new LinkedHashMap<>();
			for (Ranked value : ranked) {
				String sound = ObservationDetails.bounded(shortSound(value.heard().sound), 128);
				String source = value.heard().source;
				String direction = direction(value.dx(), value.dz(), yaw);
				String elevation = elevation(value.dy());
				String key = sound + "|" + source + "|" + direction + "|" + elevation;
				int distance = (int) Math.round(value.distance());
				long seconds = (tick - value.heard().tick) / 20L;
				JsonObject json = merged.get(key);
				if (json == null) {
					if (merged.size() >= limit) continue;
					json = new JsonObject();
					json.addProperty("sound", sound);
					if (source != null && !sound.contains(shortSound(source))) json.addProperty("source", source);
					json.addProperty("direction", direction);
					json.addProperty("elevation", elevation);
					json.addProperty("distance", distance);
					json.addProperty("count", value.heard().count);
					json.addProperty("secondsAgo", seconds);
					merged.put(key, json);
					continue;
				}
				json.addProperty("distance", Math.min(distance, json.get("distance").getAsInt()));
				json.addProperty("count", json.get("count").getAsInt() + value.heard().count);
				json.addProperty("secondsAgo", Math.min(seconds, json.get("secondsAgo").getAsLong()));
			}
			JsonArray values = new JsonArray();
			for (JsonObject json : merged.values()) {
				int count = Math.min(json.get("count").getAsInt(), 99);
				json.remove("count");
				if (count > 1) json.addProperty("count", count);
				long seconds = json.get("secondsAgo").getAsLong();
				json.remove("secondsAgo");
				if (seconds >= 5) json.addProperty("secondsAgo", seconds);
				values.add(json);
			}
			return values;
		}

		private static final class Group {
			final String key;
			final AmbientSource loudest;
			double rate;

			Group(String key, AmbientSource loudest) {
				this.key = key;
				this.loudest = loudest;
			}
		}

		private static final class Heard {
			final String sound;
			final String source;
			double x, y, z, loudness, weight;
			int count;
			long tick = Long.MIN_VALUE;

			Heard(String sound, String source) {
				this.sound = sound;
				this.source = source;
			}
		}
	}
}
