package dev.agaminggod.arenaagents.scenario.runtime;

import dev.agaminggod.arenaagents.scenario.ScenarioPresets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Exercises the live preparation step without creating a level or loading chunks. */
public final class ScenarioArenaResetPreparationVerification {
	private ScenarioArenaResetPreparationVerification() { }

	public static int verify() {
		int assertions = verifyLootContents();
		assertions += verifyPreparation(new ScenarioArenaResetJob(List.of()), List.of(), false);
		Random random = new Random(64L);
		var source = new ArrayList<ScenarioArenaBlueprint.Placement>();
		var states = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
				Blocks.WATER.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
				Blocks.CHEST.defaultBlockState(), Blocks.BARREL.defaultBlockState(),
				Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
		for (int index = 0; index < 4_000; index++) {
			source.add(new ScenarioArenaBlueprint.Placement(
					new BlockPos(random.nextInt(41) - 20, random.nextInt(12) - 6, random.nextInt(41) - 20),
					states.get(random.nextInt(states.size()))));
		}
		assertions += verifyPreparation(new ScenarioArenaResetJob(source), source, false);
		var air = List.of(new ScenarioArenaBlueprint.Placement(new BlockPos(-17, 70, 32), Blocks.AIR.defaultBlockState()));
		var airBlueprint = ScenarioArenaBlueprint.fromPlacements(BlockPos.ZERO, air);
		assertions += verifyPreparation(new ScenarioArenaResetJob(airBlueprint), air, true);
		var preset = ScenarioPresets.require("citadel-collapse");
		for (int roster = preset.minimumAgents(); roster <= preset.maximumAgents(); roster++) {
			var origin = new BlockPos(-113, 70, 239);
			var blueprint = ScenarioArenaBlueprint.create(preset, origin, roster);
			assertions += verifyDungeonGeometry(blueprint, origin);
			if (roster == 16) assertions += verifyPreparation(new ScenarioArenaResetJob(blueprint), blueprint.placements(), true);
		}
		return assertions;
	}

	private static int verifyPreparation(ScenarioArenaResetJob job, List<ScenarioArenaBlueprint.Placement> source, boolean clearSite) {
		try {
			Method step = ScenarioArenaResetJob.class.getDeclaredMethod("canonicalizeOne");
			step.setAccessible(true);
			Field sourceIndex = field("sourceIndex"), collected = field("canonicalCollected"), ordered = field("orderedPlacements");
			Field chunks = field("managedChunkBuilder");
			int calls = 0, work = 0;
			while (job.phase() == ScenarioArenaResetJob.Phase.CANONICALIZE) {
				int before = sourceIndex.getInt(job) + collected.getInt(job) + ordered.getInt(job) + ((List<?>) chunks.get(job)).size();
				boolean worked = (boolean) step.invoke(job);
				int after = sourceIndex.getInt(job) + collected.getInt(job) + ordered.getInt(job) + ((List<?>) chunks.get(job)).size();
				check(after - before == (worked ? 1 : 0), "each preparation call consumes exactly one entry or a constant-time transition");
				check(job.completedWork() <= job.totalPlacements(), "progress remains valid including filtered air");
				work += after - before;
				check(++calls <= source.size() * 4 + 1_000, "preparation converges");
			}
			var expected = ScenarioArenaResetJob.canonicalize(source).stream()
					.filter(placement -> !clearSite || !placement.state().isAir()).toList();
			check(expected.equals(job.canonicalPlacements()), "last-write-wins canonical geometry survives preparation");
			check(ScenarioArenaResetJob.hash(expected).equals(field("blueprintHash").get(job)), "canonical hash is unchanged");
			@SuppressWarnings("unchecked")
			var actualOrder = new ArrayList<>((TreeSet<ScenarioArenaBlueprint.Placement>) field("applicationOrder").get(job));
			check(ScenarioArenaResetJob.applicationOrder(expected).equals(actualOrder),
					"incremental ordering equals the previous complete spatial order, including air and waterlogged blocks");
			var bounds = (ScenarioArenaBlueprint.SiteBounds) field("siteBounds").get(job);
			var expectedChunks = bounds == null ? ScenarioArenaResetJob.managedChunks(expected) : ScenarioArenaResetJob.managedChunks(bounds);
			check(expectedChunks.equals(field("managedChunks").get(job)), "ticket chunks retain exact sorted membership");
			var expectedContainers = expected.stream()
					.filter(placement -> placement.state().is(Blocks.CHEST) || placement.state().is(Blocks.BARREL)).toList();
			check(expectedContainers.equals(job.canonicalContainerPlacements()),
					"prepared container index preserves final canonical order and excludes overwritten containers");
			try {
				job.canonicalContainerPlacements().add(null);
				throw new AssertionError("container index must be immutable");
			} catch (UnsupportedOperationException expectedFailure) { }
			check(work == source.size() + ScenarioArenaResetJob.canonicalize(source).size() + expected.size() + expectedChunks.size(),
					"all ordering and chunk work is charged, including discarded air");
			check(((java.util.Set<?>) field("retainedChunks").get(job)).isEmpty(), "preparation cannot retain world tickets");
			if (!actualOrder.isEmpty()) {
				Method apply = ScenarioArenaResetJob.class.getDeclaredMethod("applyOne", net.minecraft.server.level.ServerLevel.class);
				apply.setAccessible(true);
				for (int attempt = 0; attempt < 2; attempt++) {
					try {
						// Inject a failure at the first level access, without constructing a world.
						apply.invoke(job, new Object[]{null});
						throw new AssertionError("missing level must fail");
					} catch (java.lang.reflect.InvocationTargetException expectedFailure) {
						check(expectedFailure.getCause() instanceof NullPointerException, "injected level access failure reached");
					}
					check(actualOrder.getFirst().equals(field("pendingApplication").get(job)), "retry cannot skip the failed application entry");
					check(field("applied").getInt(job) == 0, "failed application cannot advance progress");
				}
			}
			try {
				job.canonicalPlacements().add(source.isEmpty() ? null : source.getFirst());
				throw new AssertionError("canonical publication must be immutable");
			} catch (UnsupportedOperationException expectedFailure) { }
			return 9 + calls * 3 + (actualOrder.isEmpty() ? 0 : 6);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("real reset preparation could not be exercised", exception);
		}
	}

	private static int verifyDungeonGeometry(ScenarioArenaBlueprint blueprint, BlockPos origin) {
		Map<BlockPos, ScenarioArenaBlueprint.Placement> cells = new HashMap<>();
		for (var placement : blueprint.placements()) cells.put(placement.position(), placement);
		check(cells.values().stream().filter(placement -> placement.state().is(Blocks.CHEST)
				|| placement.state().is(Blocks.BARREL)).count() == 33L,
				"every legal PvP roster has the same small 33-container completion pass");
		int assertions = 1;
		for (int[] dungeon : new int[][]{{-42, -35}, {43, -31}, {-39, 40}, {41, 38}, {0, -49}, {0, 49}}) {
			BlockPos center = origin.offset(dungeon[0], 0, dungeon[1]);
			check(cells.get(center.below(5)).state().is(Blocks.CHEST), "dungeon chest remains authored");
			check(cells.get(center.below(4)).state().isAir(), "chest lid has clear headroom");
			Direction outward = Math.abs(dungeon[0]) >= Math.abs(dungeon[1]) && dungeon[0] != 0
					? dungeon[0] > 0 ? Direction.WEST : Direction.EAST
					: dungeon[1] > 0 ? Direction.NORTH : Direction.SOUTH;
			for (int distance = 1; distance <= 6; distance++) {
				BlockPos stair = center.relative(outward, distance).offset(0, distance - 6, 0);
				check(cells.get(stair).state().is(Blocks.COBBLESTONE_STAIRS), "continuous six-rise route joins floor to surface");
				check(cells.get(stair).state().getValue(BlockStateProperties.HORIZONTAL_FACING) == outward,
						"each stair rises toward the surface entrance");
				check(cells.get(stair.above()).state().isAir() && cells.get(stair.above(2)).state().isAir(),
						"each stair has two blocks of clear headroom through the wall and roof");
				assertions += 3;
			}
			BlockPos approach = center.relative(outward, 7);
			check(!cells.get(approach).state().isAir(), "surface entrance has supporting terrain");
			check(cells.get(approach.above()).state().isAir() && cells.get(approach.above(2)).state().isAir(),
					"surface cover cannot obstruct the entrance");
			for (int x = -2; x <= 2; x++) {
				for (int z = -2; z <= 2; z++) {
					for (int y = -5; y <= -2; y++) {
						var state = cells.get(center.offset(x, y, z)).state();
						check(state.isAir() || state.is(Blocks.CHEST) || state.is(Blocks.COBBLESTONE_STAIRS),
								"room is hollow, with only its chest and descending stairs inside");
						assertions++;
					}
				}
			}
			// A contestant can step sideways from the lowest stair onto the floor beside the chest.
			for (int distance = 0; distance <= 1; distance++) {
				BlockPos feet = center.relative(outward, distance).relative(outward.getClockWise()).below(5);
				check(cells.get(feet).state().isAir() && cells.get(feet.above()).state().isAir()
						&& cells.get(feet.below()).state().is(Blocks.COBBLESTONE), "clear supported route reaches the chest's side");
				assertions++;
			}
			assertions += 4;
		}
		return assertions;
	}

	private static int verifyLootContents() {
		Map<net.minecraft.world.item.Item, net.minecraft.core.component.DataComponentMap> originals = new HashMap<>();
		try {
			Method populate = ScenarioRuntimeService.class.getDeclaredMethod("populateArenaContainer",
					net.minecraft.world.Container.class, BlockPos.class, BlockPos.class, long.class);
			populate.setAccessible(true);
			BlockPos origin = new BlockPos(0, 70, 0);
			var positions = List.of(new BlockPos(0, 72, 0), new BlockPos(-42, 65, -35), new BlockPos(90, 74, 90));
			bindLootItem(originals, net.minecraft.world.item.Items.DIAMOND);
			var fixturePositions = new ArrayList<>(positions);
			fixturePositions.add(origin);
			for (BlockPos position : fixturePositions) {
				for (var entry : ScenarioLootManifest.forContainer(origin, position, 93L).entries()) {
					bindLootItem(originals, net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
							net.minecraft.resources.Identifier.parse(entry.itemId())));
				}
			}
			int assertions = 0;
			for (BlockPos position : positions) {
				var writes = new java.util.concurrent.atomic.AtomicInteger();
				var container = new net.minecraft.world.SimpleContainer(27) {
					@Override public void setItem(int slot, net.minecraft.world.item.ItemStack stack) {
						writes.incrementAndGet();
						super.setItem(slot, stack);
					}
				};
				for (int slot = 0; slot < 27; slot++) container.setItem(slot,
						new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 64));
				populate.invoke(null, container, origin, position, 93L);
				var manifest = ScenarioLootManifest.forContainer(origin, position, 93L);
				for (int slot = 0; slot < 27; slot++) {
					int currentSlot = slot;
					var entry = manifest.entries().stream().filter(value -> value.slot() == currentSlot).findFirst();
					var actual = container.getItem(slot);
					check(entry.isEmpty() ? actual.isEmpty() : actual.getCount() == entry.get().count()
							&& net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(actual.getItem()).toString().equals(entry.get().itemId()),
							"real runtime population keeps exact tier/seed contents and clears every stale slot");
					assertions++;
				}
				writes.set(0);
				populate.invoke(null, container, origin, position, 93L);
				check(writes.get() == 0, "populating an already correct container remains idempotent");
				assertions++;
			}
			for (var container : List.of(new net.minecraft.world.SimpleContainer(1), new net.minecraft.world.SimpleContainer(27) {
				@Override public void setItem(int slot, net.minecraft.world.item.ItemStack stack) { }
			})) {
				try {
					populate.invoke(null, container, origin, origin, 93L);
					throw new AssertionError("invalid or non-writable loot container must fail");
				} catch (java.lang.reflect.InvocationTargetException expectedFailure) {
					String code = container.getContainerSize() == 1 ? "LOOT_SLOT_OUT_OF_RANGE_AT_" : "LOOT_VERIFICATION_FAILED_AT_";
					check(expectedFailure.getCause() instanceof IllegalStateException
							&& expectedFailure.getCause().getMessage().startsWith(code), "loot validation failures retain their existing code");
					assertions++;
				}
			}
			return assertions;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("real runtime loot population could not be exercised", exception);
		} finally {
			try {
				Field components = net.minecraft.core.Holder.Reference.class.getDeclaredField("components");
				components.setAccessible(true);
				for (var entry : originals.entrySet()) components.set(entry.getKey().builtInRegistryHolder(), entry.getValue());
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError("temporary loot item components could not be restored", exception);
			}
		}
	}

	private static void bindLootItem(Map<net.minecraft.world.item.Item, net.minecraft.core.component.DataComponentMap> originals,
			net.minecraft.world.item.Item item) {
		var holder = item.builtInRegistryHolder();
		if (holder.areComponentsBound()) return;
		// Pure bootstrap has no data-pack item components; install only the stack-size fixture and restore it afterwards.
		originals.put(item, null);
		holder.bindComponents(net.minecraft.core.component.DataComponentMap.builder()
				.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64).build());
	}

	private static Field field(String name) throws NoSuchFieldException {
		Field result = ScenarioArenaResetJob.class.getDeclaredField(name);
		result.setAccessible(true);
		return result;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
