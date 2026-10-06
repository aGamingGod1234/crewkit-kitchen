package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.pov.AgentPovPosePayload;
import dev.agaminggod.arenaagents.pov.PovInventory;
import dev.agaminggod.arenaagents.pov.PovMenu;
import dev.agaminggod.arenaagents.pov.PovVitals;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class PovStatePublisherVerification {
	private static int passed;

	private PovStatePublisherVerification() {
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		passed = 0;
		// Item components are data-driven in 26.1 and unbound without a server; bind minimal ones and restore after.
		Map<Holder.Reference<Item>, DataComponentMap> originals = new IdentityHashMap<>();
		try {
			for (Item item : List.of(Items.OAK_LOG, Items.STONE_PICKAXE, Items.SHIELD, Items.IRON_CHESTPLATE, Items.BREAD, Items.DIAMOND)) {
				bind(originals, item, DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64));
			}
			bind(originals, Items.SHULKER_BOX, DataComponentMap.builder()
					.set(DataComponents.MAX_STACK_SIZE, 1)
					.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY));
			verifyStateChangeDetector();
			verifyMenuChangeDetector();
			verifyPoseAndAbsentState();
			verifyWireStacks();
			verifySummaryContent();
			verifySummaryOmissionAndCap();
		} finally {
			restore(originals);
		}
		return passed;
	}

	private static void bind(Map<Holder.Reference<Item>, DataComponentMap> originals, Item item, DataComponentMap.Builder overrides) {
		Holder.Reference<Item> holder = item.builtInRegistryHolder();
		DataComponentMap original = holder.areComponentsBound() ? holder.components() : null;
		if (!originals.containsKey(holder)) originals.put(holder, original);
		DataComponentMap.Builder components = DataComponentMap.builder();
		if (original != null) components.addAll(original);
		components.addAll(overrides.build());
		holder.bindComponents(components.build());
	}

	private static void restore(Map<Holder.Reference<Item>, DataComponentMap> originals) {
		try {
			Field components = Holder.Reference.class.getDeclaredField("components");
			components.setAccessible(true);
			for (Map.Entry<Holder.Reference<Item>, DataComponentMap> entry : originals.entrySet()) {
				if (entry.getValue() == null) components.set(entry.getKey(), null);
				else entry.getKey().bindComponents(entry.getValue());
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not restore temporary item components", exception);
		}
	}

	private static void verifyStateChangeDetector() {
		PovAgentSnapshot base = snapshot(vitals(20.0F), inventory(0, 3), List.of(speed(0, 400)), Optional.empty(), 10.0F);
		PovAgentSnapshot same = snapshot(vitals(20.0F), inventory(0, 3), List.of(speed(0, 400)), Optional.empty(), 10.0F);
		assertTrue(base.stateDiffers(null), "first publication always sends state");
		assertTrue(!same.stateDiffers(base), "equal stacks in fresh instances do not resend state");
		assertTrue(snapshot(vitals(14.0F), inventory(0, 3), List.of(speed(0, 400)), Optional.empty(), 10.0F).stateDiffers(base),
				"health change resends state");
		assertTrue(snapshot(vitals(20.0F), inventory(0, 4), List.of(speed(0, 400)), Optional.empty(), 10.0F).stateDiffers(base),
				"hotbar count change resends state");
		assertTrue(snapshot(vitals(20.0F), inventory(2, 3), List.of(speed(0, 400)), Optional.empty(), 10.0F).stateDiffers(base),
				"selected slot change resends state");
		PovInventory armored = new PovInventory(0, inventory(0, 3).hotbar(), ItemStack.EMPTY,
				List.of(ItemStack.EMPTY, ItemStack.EMPTY, new ItemStack(Items.IRON_CHESTPLATE), ItemStack.EMPTY));
		assertTrue(snapshot(vitals(20.0F), armored, List.of(speed(0, 400)), Optional.empty(), 10.0F).stateDiffers(base),
				"armor change resends state");
		assertTrue(!snapshot(vitals(20.0F), inventory(0, 3), List.of(speed(0, 419)), Optional.empty(), 10.0F).stateDiffers(base),
				"effect countdown inside the same second does not resend state");
		assertTrue(snapshot(vitals(20.0F), inventory(0, 3), List.of(speed(0, 399)), Optional.empty(), 10.0F).stateDiffers(base),
				"effect countdown crossing a second resends state");
		assertTrue(snapshot(vitals(20.0F), inventory(0, 3), List.of(speed(1, 400)), Optional.empty(), 10.0F).stateDiffers(base),
				"effect amplifier change resends state");
		assertTrue(snapshot(vitals(20.0F), inventory(0, 3), List.of(), Optional.empty(), 10.0F).stateDiffers(base),
				"effect expiry resends state");
		assertTrue(!snapshot(vitals(20.0F), inventory(0, 3), List.of(speed(0, 400)), Optional.empty(), 95.0F).stateDiffers(base),
				"look changes travel in the pose payload, not the state payload");
	}

	private static void verifyMenuChangeDetector() {
		PovAgentSnapshot closed = snapshot(vitals(20.0F), inventory(0, 3), List.of(), Optional.empty(), 0.0F);
		PovAgentSnapshot chest = chest(5, 1, "Chest", 7, ItemStack.EMPTY, List.of());
		assertTrue(!closed.menuDiffers(null), "no open menu never sends a menu payload");
		assertTrue(chest.stateDiffers(closed) && chest.menuDiffers(closed), "opening a menu sends state and contents");
		PovAgentSnapshot sameChest = chest(5, 1, "Chest", 7, ItemStack.EMPTY, List.of());
		assertTrue(!sameChest.stateDiffers(chest) && !sameChest.menuDiffers(chest), "unchanged menu sends nothing");
		PovAgentSnapshot newStateId = chest(5, 2, "Chest", 7, ItemStack.EMPTY, List.of());
		assertTrue(!newStateId.stateDiffers(chest) && newStateId.menuDiffers(chest),
				"stateId bump resends contents only");
		PovAgentSnapshot moreItems = chest(5, 1, "Chest", 8, ItemStack.EMPTY, List.of());
		assertTrue(!moreItems.stateDiffers(chest) && moreItems.menuDiffers(chest), "slot change resends contents only");
		PovAgentSnapshot carrying = chest(5, 1, "Chest", 7, new ItemStack(Items.BREAD), List.of());
		assertTrue(carrying.menuDiffers(chest), "carried stack change resends contents");
		PovAgentSnapshot burning = chest(5, 1, "Chest", 7, ItemStack.EMPTY, List.of(40));
		assertTrue(burning.menuDiffers(chest), "data slot change resends contents");
		PovAgentSnapshot renamed = chest(5, 1, "Loot", 7, ItemStack.EMPTY, List.of());
		assertTrue(renamed.stateDiffers(chest), "title change resends the menu descriptor");
		PovAgentSnapshot otherContainer = chest(6, 1, "Chest", 7, ItemStack.EMPTY, List.of());
		assertTrue(otherContainer.stateDiffers(chest) && otherContainer.menuDiffers(chest),
				"a different container id resends descriptor and contents");
		assertThrows(() -> new PovAgentSnapshot(0, Level.OVERWORLD, vitals(20.0F), PovInventory.empty(), List.of(),
						Optional.of(new PovMenu(5, 1, Optional.of(MenuType.GENERIC_9x3), Component.literal("Chest"))),
						Optional.empty(), new PovAgentSnapshot.Pose(0.0F, 0.0F, 1.0F, 0)),
				"menu descriptor without contents is rejected");
		assertThrows(() -> new PovAgentSnapshot.MenuContents(1, 1, Collections.nCopies(257, ItemStack.EMPTY),
						ItemStack.EMPTY, List.of()),
				"menu contents above the slot bound are rejected");
	}

	private static void verifyPoseAndAbsentState() {
		assertEquals(AgentPovPosePayload.FLAG_SNEAKING | AgentPovPosePayload.FLAG_ON_GROUND,
				PovAgentSnapshot.poseFlags(true, false, false, false, true, false, false, false),
				"pose flags combine sneaking and on-ground");
		assertEquals(AgentPovPosePayload.FLAG_DEAD | AgentPovPosePayload.FLAG_BLOCKING,
				PovAgentSnapshot.poseFlags(false, false, false, false, false, false, true, true),
				"pose flags carry blocking and death");
		PovAgentSnapshot alive = chest(5, 1, "Chest", 7, ItemStack.EMPTY, List.of());
		PovAgentSnapshot gone = PovAgentSnapshot.absent(alive, Level.NETHER);
		assertEquals(0.0F, gone.vitals().health(), "absent agent reports zero health");
		assertEquals(alive.vitals().food(), gone.vitals().food(), "absent agent keeps last known food");
		assertTrue(PovAgentSnapshot.sameInventory(alive.inventory(), gone.inventory()), "absent agent keeps last known inventory");
		assertTrue(gone.menu().isEmpty() && gone.menuContents().isEmpty(), "absent agent has no open menu");
		assertTrue(gone.pose().dead(), "absent agent pose is dead");
		assertEquals(Level.OVERWORLD, gone.dimension(), "absent agent keeps last known dimension");
		assertTrue(gone.stateDiffers(alive), "losing the body resends state");
		PovAgentSnapshot never = PovAgentSnapshot.absent(null, Level.NETHER);
		assertEquals(Level.NETHER, never.dimension(), "never-seen agent uses the fallback dimension");
		assertTrue(never.pose().dead() && never.inventory().hotbar().stream().allMatch(ItemStack::isEmpty),
				"never-seen agent is dead with an empty inventory");
	}

	private static void verifyWireStacks() {
		ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
		shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 5))));
		ItemStack wire = PovAgentSnapshot.wireStack(shulker);
		ItemContainerContents prototype = shulker.getPrototype().get(DataComponents.CONTAINER);
		assertTrue(Objects.equals(prototype, wire.get(DataComponents.CONTAINER)), "wire copy drops container contents");
		assertTrue(!Objects.equals(prototype, shulker.get(DataComponents.CONTAINER)), "source stack keeps its contents");
		assertTrue(wire.is(Items.SHULKER_BOX) && wire.getCount() == 1, "wire copy keeps item and count");
		ItemStack logs = new ItemStack(Items.OAK_LOG, 12);
		assertTrue(ItemStack.matches(logs, PovAgentSnapshot.wireStack(logs)), "plain stacks are copied unchanged");
		assertTrue(PovAgentSnapshot.wireStack(ItemStack.EMPTY).isEmpty(), "empty stays empty");
	}

	private static void verifySummaryContent() {
		Map<String, Integer> before = new HashMap<>();
		before.put("minecraft:oak_log", 2);
		before.put("minecraft:bread", 3);
		before.put("minecraft:dirt", 10);
		before.put("minecraft:stick", 1);
		Map<String, Integer> after = new HashMap<>();
		after.put("minecraft:oak_log", 5);
		after.put("minecraft:bread", 2);
		after.put("minecraft:dirt", 6);
		after.put("minecraft:cobblestone", 7);
		after.put("minecraft:stick", 1);
		PovTakeoverSummary.Snapshot start = summary(Level.OVERWORLD, new Vec3(12.5D, 64.0D, -7.5D), 20.0F, 0.0F, 20, 3, before);
		PovTakeoverSummary.Snapshot end = summary(Level.OVERWORLD, new Vec3(40.5D, 70.0D, 4.5D), 14.0F, 0.0F, 17, 5, after);
		String text = PovTakeoverSummary.describe(start, end, 42_000L, 1, 1, List.of("Fell into lava.", "  picked up\n3 oak_log "));
		assertTrue(text.startsWith("An operator controlled your body for 42 s."), "summary opens with the duration");
		assertTrue(text.contains(" Moved 31 blocks (from 12,64,-8 to 40,70,4)."), "summary reports straight-line distance and block positions");
		assertTrue(text.contains(" Health 20 to 14."), "summary reports the health delta");
		assertTrue(!text.contains("absorption"), "unchanged zero absorption is omitted");
		assertTrue(text.contains(" Food 20 to 17.") && text.contains(" XP level 3 to 5."), "summary reports food and XP changes");
		assertTrue(text.contains(" Items: +7 cobblestone, +3 oak_log, -4 dirt, -1 bread."),
				"item deltas list gains then losses, largest first, without the minecraft namespace");
		assertTrue(!text.contains("stick"), "unchanged items are omitted");
		assertTrue(text.contains(" Died 1 time, respawned 1 time."), "summary reports deaths and respawns");
		assertTrue(text.endsWith(" Events: Fell into lava; picked up 3 oak_log."), "events are sanitized and joined");
		assertEquals("2 min 5 s", PovTakeoverSummary.duration(125_000L), "long durations use minutes");
		assertEquals("3 min", PovTakeoverSummary.duration(180_000L), "whole minutes omit seconds");
		PovTakeoverSummary.Snapshot nether = summary(Level.NETHER, new Vec3(5.2D, 70.0D, -3.9D), 20.0F, 4.0F, 20, 3, before);
		String travelled = PovTakeoverSummary.describe(start, nether, 9_000L, 0, 0, List.of());
		assertTrue(travelled.contains(" Moved from overworld to the_nether (now at 5,70,-4)."), "dimension change is reported");
		assertTrue(travelled.contains(" Absorption 0 to 4."), "absorption change alone is reported");
		assertTrue(!travelled.contains("Health") && !travelled.contains("Items") && !travelled.contains("Died"),
				"unchanged health, items and lives are omitted");
		assertThrows(() -> summary(Level.OVERWORLD, Vec3.ZERO, 20.0F, 0.0F, 20, 0, Map.of("minecraft:dirt", -1)),
				"negative item counts are rejected");
		assertTrue(!summary(Level.OVERWORLD, Vec3.ZERO, 20.0F, 0.0F, 20, 0, Map.of("minecraft:dirt", 0))
				.itemCounts().containsKey("minecraft:dirt"), "zero item counts are dropped");
	}

	private static void verifySummaryOmissionAndCap() {
		PovTakeoverSummary.Snapshot still = summary(Level.OVERWORLD, new Vec3(1.0D, 64.0D, 1.0D), 20.0F, 0.0F, 20, 0, Map.of());
		PovTakeoverSummary.Snapshot nudged = summary(Level.OVERWORLD, new Vec3(1.3D, 64.0D, 1.0D), 20.0F, 0.0F, 20, 0, Map.of());
		assertEquals("An operator controlled your body for 5 s. No other changes.",
				PovTakeoverSummary.describe(still, nudged, 5_000L, 0, 0, List.of(" ", "\n")),
				"nothing changed reads as one short sentence");
		Map<String, Integer> many = new HashMap<>();
		for (int index = 0; index < 60; index++) many.put("examplemod:very_long_item_identifier_number_" + index, index + 1);
		List<String> events = new ArrayList<>();
		for (int index = 0; index < 40; index++) events.add("event " + index + " " + "x".repeat(200));
		String capped = PovTakeoverSummary.describe(still, summary(Level.OVERWORLD, new Vec3(90.0D, 64.0D, 1.0D),
				3.5F, 0.0F, 2, 9, many), 61_000L, 2, 0, events);
		assertTrue(capped.length() <= PovTakeoverSummary.MAX_LENGTH, "summary is capped at 512 characters");
		assertTrue(capped.contains(" Moved 89 blocks") && capped.contains(" Health 20 to 3.5."), "fixed facts survive the cap");
		assertTrue(capped.contains(" Died 2 times."), "deaths survive the cap");
		assertTrue(capped.contains(" more."), "dropped items or events are counted instead of silently cut");
		assertTrue(capped.contains("+60 examplemod:very_long_item_identifier_number_59"), "largest gains are kept first");
	}

	private static PovAgentSnapshot chest(int containerId, int stateId, String title, int diamonds, ItemStack carried, List<Integer> data) {
		List<ItemStack> slots = new ArrayList<>(Collections.nCopies(27, ItemStack.EMPTY));
		slots.set(0, new ItemStack(Items.DIAMOND, diamonds));
		return new PovAgentSnapshot(42, Level.OVERWORLD, vitals(20.0F), inventory(0, 3), List.of(),
				Optional.of(new PovMenu(containerId, stateId, Optional.of(MenuType.GENERIC_9x3), Component.literal(title))),
				Optional.of(new PovAgentSnapshot.MenuContents(containerId, stateId, slots, carried, data)),
				new PovAgentSnapshot.Pose(0.0F, 0.0F, 1.0F, 0));
	}

	private static PovAgentSnapshot snapshot(PovVitals vitals, PovInventory inventory, List<MobEffectInstance> effects, Optional<PovMenu> menu, float yaw) {
		return new PovAgentSnapshot(42, Level.OVERWORLD, vitals, inventory, effects, menu, Optional.empty(),
				new PovAgentSnapshot.Pose(yaw, 0.0F, 1.0F, AgentPovPosePayload.FLAG_ON_GROUND));
	}

	private static PovVitals vitals(float health) {
		return new PovVitals(health, 20.0F, 0.0F, 0, 18, 2.5F, 300, 300, 4, 0.25F, GameType.SURVIVAL);
	}

	private static PovInventory inventory(int selected, int logs) {
		List<ItemStack> hotbar = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
		hotbar.set(0, new ItemStack(Items.OAK_LOG, logs));
		hotbar.set(1, new ItemStack(Items.STONE_PICKAXE));
		return new PovInventory(selected, hotbar, new ItemStack(Items.SHIELD), Collections.nCopies(4, ItemStack.EMPTY));
	}

	private static MobEffectInstance speed(int amplifier, int duration) {
		return new MobEffectInstance(MobEffects.SPEED, duration, amplifier);
	}

	private static PovTakeoverSummary.Snapshot summary(
			ResourceKey<Level> dimension,
			Vec3 position,
			float health,
			float absorption,
			int food,
			int xpLevel,
			Map<String, Integer> items
	) {
		return new PovTakeoverSummary.Snapshot(dimension, position, health, absorption, food, xpLevel, items, 0L);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		passed++;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
		passed++;
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
		} catch (IllegalArgumentException expected) {
			passed++;
			return;
		}
		throw new AssertionError(label + " did not throw IllegalArgumentException");
	}
}
