package dev.agaminggod.arenaagents.pov;

import dev.agaminggod.arenaagents.agent.AgentId;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

public final class PovContractsVerification {
	private static final UUID AGENT_UUID = UUID.fromString("12345678-1234-5678-9abc-123456789abc");
	private static final UUID OPERATOR_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
	private static final UUID SECOND_OPERATOR_UUID = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
	private static int assertions;

	private PovContractsVerification() {
	}

	public static int verify() {
		var out = System.out;
		var err = System.err;
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.setOut(out);
		System.setErr(err);
		assertions = 0;
		List<Item> boundItems = bindTestItemComponents();
		try {
			verifyInventoryBounds();
			verifyVitalsBounds();
			verifyDescriptorBounds();
			verifyStateBounds();
			verifyPoseMenuAndStopBounds();
			verifyOperatorInputBounds();
			verifyOperatorActionBounds();
			verifyCodecRoundTrips();
			verifyViewAnchors();
			verifyBodyControllers();
		} finally {
			unbindTestItemComponents(boundItems);
		}
		return assertions;
	}

	/** Plain Bootstrap leaves item components unbound until data packs load; bind the minimum ItemStack needs. */
	private static List<Item> bindTestItemComponents() {
		List<Item> bound = new ArrayList<>();
		for (Item item : List.of(Items.DIAMOND, Items.SHIELD, Items.IRON_HELMET)) {
			Holder.Reference<Item> holder = item.builtInRegistryHolder();
			if (holder.areComponentsBound()) continue;
			holder.bindComponents(DataComponentMap.builder()
					.set(DataComponents.MAX_STACK_SIZE, item == Items.DIAMOND ? 64 : 1).build());
			bound.add(item);
		}
		return bound;
	}

	private static void unbindTestItemComponents(List<Item> bound) {
		try {
			Field components = Holder.Reference.class.getDeclaredField("components");
			components.setAccessible(true);
			for (Item item : bound) components.set(item.builtInRegistryHolder(), null);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Could not restore bootstrap item components", exception);
		}
	}

	private static void verifyInventoryBounds() {
		PovInventory empty = PovInventory.empty();
		assertEquals(9, empty.hotbar().size(), "empty inventory has a full hotbar");
		assertEquals(4, empty.armor().size(), "empty inventory has four armor entries");
		assertTrue(empty.offhand().isEmpty() && empty.selectedSlot() == 0, "empty inventory selects slot 0 with no offhand");
		expectFailure(() -> inventory(0, stacks(8), stacks(4)), "hotbar with 8 stacks");
		expectFailure(() -> inventory(0, stacks(10), stacks(4)), "hotbar with 10 stacks");
		expectFailure(() -> inventory(0, stacks(9), stacks(3)), "armor with 3 stacks");
		expectFailure(() -> inventory(-1, stacks(9), stacks(4)), "selected slot -1");
		expectFailure(() -> inventory(9, stacks(9), stacks(4)), "selected slot 9");
		List<ItemStack> withNull = new ArrayList<>(stacks(9));
		withNull.set(4, null);
		expectFailure(() -> inventory(0, withNull, stacks(4)), "null hotbar stack");
		expectFailure(() -> new PovInventory(0, stacks(9), null, stacks(4)), "null offhand");

		ItemStack diamonds = new ItemStack(Items.DIAMOND, 5);
		List<ItemStack> hotbar = new ArrayList<>(stacks(9));
		hotbar.set(2, diamonds);
		PovInventory inventory = inventory(2, hotbar, stacks(4));
		diamonds.setCount(1);
		hotbar.set(3, new ItemStack(Items.SHIELD));
		assertEquals(5, inventory.hotbar().get(2).getCount(), "inventory copies stacks instead of aliasing them");
		assertTrue(inventory.hotbar().get(3).isEmpty(), "inventory copies the hotbar list");
		expectUnsupported(() -> inventory.hotbar().set(0, ItemStack.EMPTY), "hotbar list is immutable");
		expectUnsupported(() -> inventory.armor().clear(), "armor list is immutable");

		List<ItemStack> sameHotbar = new ArrayList<>(stacks(9));
		sameHotbar.set(2, new ItemStack(Items.DIAMOND, 5));
		assertTrue(inventory.matches(inventory(2, sameHotbar, stacks(4))), "equal contents match");
		sameHotbar.set(2, new ItemStack(Items.DIAMOND, 4));
		assertTrue(!inventory.matches(inventory(2, sameHotbar, stacks(4))), "a changed count does not match");
	}

	private static void verifyVitalsBounds() {
		assertEquals(20, vitals(20f, 20, 300, 0.5f).food(), "valid vitals are accepted");
		assertEquals(-20, vitals(20f, 20, -20, 0f).air(), "drowning air supply is accepted");
		expectFailure(() -> vitals(20f, 21, 300, 0f), "food 21");
		expectFailure(() -> vitals(20f, -1, 300, 0f), "food -1");
		expectFailure(() -> vitals(Float.NaN, 20, 300, 0f), "NaN health");
		expectFailure(() -> vitals(-1f, 20, 300, 0f), "negative health");
		expectFailure(() -> vitals(20f, 20, 301, 0f), "air above max air");
		expectFailure(() -> vitals(20f, 20, -21, 0f), "air below the drowning floor");
		expectFailure(() -> vitals(20f, 20, 300, 1.01f), "xp progress above 1");
		expectFailure(() -> new PovVitals(20f, 20f, 0f, 0, 20, 5f, 300, 300, 0, 0f, null), "null game mode");
	}

	private static void verifyDescriptorBounds() {
		assertEquals(256, new PovMenu(1, 0, Optional.of(MenuType.GENERIC_9x3), Component.literal("a".repeat(256)))
				.title().getString().length(), "256-character title is accepted");
		expectFailure(() -> new PovMenu(1, 0, Optional.empty(), Component.literal("a".repeat(257))), "257-character title");
		expectFailure(() -> new PovMenu(-1, 0, Optional.empty(), Component.literal("Inventory")), "negative container id");
		assertTrue(new PovDeath(Component.literal("d".repeat(1024)), false).message() != null,
				"1024-character death message is accepted");
		expectFailure(() -> new PovDeath(Component.literal("d".repeat(1025)), true), "1025-character death message");
		assertEquals("n".repeat(64), identity(PovMode.SPECTATE, "n".repeat(64)).agentName(), "64-character name is accepted");
		expectFailure(() -> identity(PovMode.SPECTATE, "   "), "blank agent name");
		expectFailure(() -> identity(PovMode.SPECTATE, "n".repeat(65)), "65-character agent name");
		expectFailure(() -> new PovIdentity(1L, -1, PovMode.TAKEOVER, AGENT_UUID, 7, Level.OVERWORLD, "Builder", 0),
				"negative revision");
	}

	private static void verifyStateBounds() {
		MobEffectInstance speed = new MobEffectInstance(MobEffects.SPEED, 200, 1);
		AgentPovStatePayload state = state(Collections.nCopies(32, speed));
		assertEquals(32, state.effects().size(), "32 effects are accepted");
		assertTrue(state.effects().get(0) != speed && state.effects().get(0).equals(speed),
				"effects are copied with equal contents");
		expectUnsupported(() -> state.effects().clear(), "effect list is immutable");
		expectFailure(() -> state(Collections.nCopies(33, speed)), "33 effects");
		assertEquals(1L, state.sessionId(), "state exposes its session id");
	}

	private static void verifyPoseMenuAndStopBounds() {
		AgentPovPosePayload pose = new AgentPovPosePayload(1L, 720f, 90f, 1f,
				AgentPovPosePayload.FLAG_SNEAKING | AgentPovPosePayload.FLAG_DEAD);
		assertTrue(pose.hasFlag(AgentPovPosePayload.FLAG_DEAD) && !pose.hasFlag(AgentPovPosePayload.FLAG_SWIMMING),
				"pose flags are readable");
		expectFailure(() -> new AgentPovPosePayload(1L, 0f, 90.5f, 0f, 0), "pose pitch above 90");
		expectFailure(() -> new AgentPovPosePayload(1L, Float.POSITIVE_INFINITY, 0f, 0f, 0), "infinite pose yaw");
		expectFailure(() -> new AgentPovPosePayload(1L, 0f, 0f, 1.5f, 0), "attack strength above 1");
		expectFailure(() -> new AgentPovPosePayload(1L, 0f, 0f, -0.1f, 0), "negative attack strength");
		expectFailure(() -> new AgentPovPosePayload(1L, 0f, 0f, 0f, 256), "unknown pose flag");

		assertEquals(256, menuPayload(256, 32).slots().size(), "256 menu slots are accepted");
		expectFailure(() -> menuPayload(257, 0), "257 menu slots");
		expectFailure(() -> menuPayload(0, 33), "33 data slots");
		List<Integer> nullData = new ArrayList<>(List.of(1, 2));
		nullData.set(1, null);
		expectFailure(() -> new AgentPovMenuPayload(1L, 1, 0, List.of(), ItemStack.EMPTY, nullData), "null data slot");
		expectFailure(() -> new AgentPovMenuPayload(1L, 1, 0, List.of(), null, List.of()), "null carried stack");

		assertEquals(256, new PovStopPayload(1L, "r".repeat(256)).reason().length(), "256-character stop reason is accepted");
		expectFailure(() -> new PovStopPayload(1L, "r".repeat(257)), "257-character stop reason");
	}

	private static void verifyOperatorInputBounds() {
		OperatorInputPayload frame = input(0, 1f, -1f, 90f, OperatorInputPayload.HELD_JUMP | OperatorInputPayload.HELD_USE, 8);
		assertTrue(frame.isHeld(OperatorInputPayload.HELD_USE) && !frame.isHeld(OperatorInputPayload.HELD_SNEAK),
				"held flags are readable");
		expectFailure(() -> input(0, 1.01f, 0f, 0f, 0, 0), "forward above 1");
		expectFailure(() -> input(0, -1.01f, 0f, 0f, 0, 0), "forward below -1");
		expectFailure(() -> input(0, 0f, Float.NaN, 0f, 0, 0), "NaN strafe");
		expectFailure(() -> input(0, 0f, 0f, -91f, 0, 0), "input pitch below -90");
		expectFailure(() -> new OperatorInputPayload(1L, 0, 0f, 0f, Float.NaN, 0f, 0, 0), "NaN input yaw");
		expectFailure(() -> input(0, 0f, 0f, 0f, 32, 0), "unknown held flag");
		expectFailure(() -> input(0, 0f, 0f, 0f, 0, 9), "input selected slot 9");
		expectFailure(() -> input(0, 0f, 0f, 0f, 0, -1), "input selected slot -1");
		expectFailure(() -> input(-1, 0f, 0f, 0f, 0, 0), "negative input sequence");
	}

	private static void verifyOperatorActionBounds() {
		OperatorActionPayload click = new OperatorActionPayload(1L, 4, OperatorAction.MENU_CLICK, 12, 1,
				ContainerInput.QUICK_MOVE.ordinal());
		assertEquals(ContainerInput.QUICK_MOVE, click.containerInput(), "menu click exposes its container input");
		assertEquals(-999, new OperatorActionPayload(1L, 0, OperatorAction.MENU_CLICK, -999, 0, 0).a(),
				"menu click outside the window keeps the vanilla -999 slot");
		assertEquals(77, new OperatorActionPayload(1L, 0, OperatorAction.ATTACK_CLICK, 77, -3, 99).a(),
				"actions without arguments ignore a, b and c");
		expectFailure(() -> new OperatorActionPayload(1L, 0, null, 0, 0, 0), "null action");
		expectFailure(() -> new OperatorActionPayload(1L, -1, OperatorAction.DROP_ITEM, 0, 0, 0), "negative action sequence");
		expectFailure(() -> new OperatorActionPayload(1L, 0, OperatorAction.MENU_CLICK, 0, 0,
				ContainerInput.values().length), "unknown container input ordinal");
		expectFailure(() -> new OperatorActionPayload(1L, 0, OperatorAction.MENU_CLICK, 0, 0, -1), "negative container input");
		expectThrows(IllegalStateException.class,
				() -> new OperatorActionPayload(1L, 0, OperatorAction.RESPAWN, 0, 0, 0).containerInput(),
				"non-click actions carry no container input");
	}

	private static void verifyCodecRoundTrips() {
		RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		List<ItemStack> hotbar = new ArrayList<>(stacks(9));
		hotbar.set(2, new ItemStack(Items.DIAMOND, 5));
		List<ItemStack> armor = new ArrayList<>(stacks(4));
		armor.set(3, new ItemStack(Items.IRON_HELMET));
		PovInventory inventory = new PovInventory(2, hotbar, new ItemStack(Items.SHIELD), armor);
		AgentPovStatePayload state = new AgentPovStatePayload(
				new PovIdentity(-42L, 9, PovMode.TAKEOVER, AGENT_UUID, 1234, Level.NETHER, "Builder", 3),
				new PovVitals(13.5f, 20f, 4f, 7, 17, 2.5f, -5, 300, 12, 0.25f, GameType.ADVENTURE),
				inventory,
				List.of(new MobEffectInstance(MobEffects.SPEED, 200, 1), new MobEffectInstance(MobEffects.HASTE, 40)),
				Optional.of(new PovMenu(3, 17, Optional.of(MenuType.GENERIC_9x3), Component.literal("Chest"))),
				Optional.of(new PovDeath(Component.literal("Builder fell from a high place"), true)));
		AgentPovStatePayload decodedState = roundTrip(AgentPovStatePayload.CODEC, state, registries, "state");
		assertEquals(state.identity(), decodedState.identity(), "state identity round trips");
		assertEquals(state.vitals(), decodedState.vitals(), "state vitals round trip");
		assertTrue(state.inventory().matches(decodedState.inventory()), "state inventory round trips");
		assertEquals(state.effects(), decodedState.effects(), "state effects round trip");
		assertEquals(state.menu(), decodedState.menu(), "state menu descriptor round trips");
		assertEquals(state.death(), decodedState.death(), "state death round trips");

		AgentPovStatePayload inventoryScreen = new AgentPovStatePayload(state.identity(), state.vitals(),
				PovInventory.empty(), List.of(),
				Optional.of(new PovMenu(0, 1, Optional.empty(), Component.literal("Inventory"))), Optional.empty());
		AgentPovStatePayload decodedScreen = roundTrip(AgentPovStatePayload.CODEC, inventoryScreen, registries, "inventory screen");
		assertEquals(inventoryScreen.menu(), decodedScreen.menu(), "inventory screen keeps an empty menu type");
		assertEquals(Optional.empty(), decodedScreen.death(), "absent death round trips");

		AgentPovPosePayload pose = new AgentPovPosePayload(-42L, -170.25f, -33.5f, 0.75f,
				AgentPovPosePayload.FLAG_ON_GROUND | AgentPovPosePayload.FLAG_BLOCKING);
		assertEquals(pose, roundTrip(AgentPovPosePayload.CODEC, pose, registries, "pose"), "pose round trips");

		List<ItemStack> slots = new ArrayList<>(stacks(90));
		slots.set(0, new ItemStack(Items.DIAMOND, 64));
		AgentPovMenuPayload menu = new AgentPovMenuPayload(-42L, 3, 17, slots, new ItemStack(Items.SHIELD), List.of(0, 200, -1));
		AgentPovMenuPayload decodedMenu = roundTrip(AgentPovMenuPayload.CODEC, menu, registries, "menu");
		assertTrue(decodedMenu.sessionId() == -42L && decodedMenu.containerId() == 3 && decodedMenu.stateId() == 17,
				"menu header round trips");
		assertTrue(ItemStack.listMatches(menu.slots(), decodedMenu.slots())
				&& ItemStack.matches(menu.carried(), decodedMenu.carried()), "menu stacks round trip");
		assertEquals(menu.dataSlots(), decodedMenu.dataSlots(), "menu data slots round trip");

		PovStopPayload stop = new PovStopPayload(-42L, "Your body took 2 hearts of damage");
		assertEquals(stop, roundTrip(PovStopPayload.CODEC, stop, registries, "stop"), "stop round trips");
		OperatorInputPayload frame = new OperatorInputPayload(-42L, 812, 0.98f, -0.3f, 1234.5f, 45f,
				OperatorInputPayload.HELD_ATTACK | OperatorInputPayload.HELD_SPRINT, 6);
		assertEquals(frame, roundTrip(OperatorInputPayload.CODEC, frame, registries, "input"), "input round trips");
		OperatorActionPayload action = new OperatorActionPayload(-42L, 5, OperatorAction.MENU_CLICK, -999, 1,
				ContainerInput.PICKUP_ALL.ordinal());
		assertEquals(action, roundTrip(OperatorActionPayload.CODEC, action, registries, "action"), "action round trips");

		RegistryFriendlyByteBuf unknownAction = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		try {
			unknownAction.writeLong(1L);
			unknownAction.writeVarInt(0);
			unknownAction.writeVarInt(OperatorAction.values().length);
			unknownAction.writeVarInt(0).writeVarInt(0).writeVarInt(0);
			expectThrows(DecoderException.class, () -> OperatorActionPayload.CODEC.decode(unknownAction),
					"unknown action ordinal is rejected on decode");
		} finally {
			unknownAction.release();
		}
		RegistryFriendlyByteBuf badPitch = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		try {
			badPitch.writeLong(1L);
			badPitch.writeVarInt(0);
			badPitch.writeFloat(0f).writeFloat(0f).writeFloat(0f).writeFloat(120f);
			badPitch.writeVarInt(0).writeVarInt(0);
			expectFailure(() -> OperatorInputPayload.CODEC.decode(badPitch), "decoded input is revalidated");
		} finally {
			badPitch.release();
		}
	}

	private static void verifyViewAnchors() {
		PovViewAnchors.clearAll();
		assertTrue(!PovViewAnchors.hasAnchor(OPERATOR_UUID), "no anchor before set");
		PovViewAnchors.set(OPERATOR_UUID, AGENT_UUID);
		assertTrue(PovViewAnchors.hasAnchor(OPERATOR_UUID), "set creates an anchor");
		assertTrue(!PovViewAnchors.hasAnchor(AGENT_UUID), "the agent itself is not anchored");
		PovViewAnchors.set(SECOND_OPERATOR_UUID, AGENT_UUID);
		PovViewAnchors.clear(OPERATOR_UUID);
		assertTrue(!PovViewAnchors.hasAnchor(OPERATOR_UUID), "clear removes one operator's anchor");
		assertTrue(PovViewAnchors.hasAnchor(SECOND_OPERATOR_UUID), "clear leaves other operators anchored");
		PovViewAnchors.clear(OPERATOR_UUID);
		assertTrue(!PovViewAnchors.hasAnchor(OPERATOR_UUID), "clearing twice is harmless");
		PovViewAnchors.set(OPERATOR_UUID, AGENT_UUID);
		PovViewAnchors.clearAll();
		assertTrue(!PovViewAnchors.hasAnchor(OPERATOR_UUID) && !PovViewAnchors.hasAnchor(SECOND_OPERATOR_UUID),
				"clearAll removes every anchor");
		expectFailure(() -> PovViewAnchors.set(OPERATOR_UUID, OPERATOR_UUID), "self anchor");
		expectFailure(() -> PovViewAnchors.set(null, AGENT_UUID), "null operator anchor");
		expectFailure(() -> PovViewAnchors.set(OPERATOR_UUID, null), "null agent anchor");
		assertTrue(!PovViewAnchors.hasAnchor(OPERATOR_UUID), "rejected anchors are not stored");
	}

	private static void verifyBodyControllers() {
		AgentId agentId = new AgentId(AGENT_UUID);
		try {
			OperatorBodyController defaultController = OperatorBodyControllers.create(null, agentId);
			assertTrue(defaultController != null && !defaultController.active(), "default controller is an inactive no-op");
			defaultController.begin(null);
			defaultController.applyFrame(input(0, 1f, 0f, 0f, OperatorInputPayload.HELD_JUMP, 0));
			defaultController.applyAction(null, new OperatorActionPayload(1L, 0, OperatorAction.ATTACK_CLICK, 0, 0, 0));
			defaultController.tick();
			defaultController.end();
			defaultController.end();
			assertTrue(!defaultController.active(), "default controller never acquires the body");
			expectFailure(() -> OperatorBodyControllers.create(null, null), "null agent id");
			expectFailure(() -> OperatorBodyControllers.install(null), "null factory");

			AtomicReference<AgentId> requested = new AtomicReference<>();
			RecordingController installed = new RecordingController();
			OperatorBodyControllers.install((server, id) -> {
				requested.set(id);
				return installed;
			});
			OperatorBodyController created = OperatorBodyControllers.create(null, agentId);
			assertTrue(created == installed, "installed factory supplies controllers");
			assertEquals(agentId, requested.get(), "installed factory receives the agent id");
			created.begin(null);
			assertTrue(created.active(), "installed controller becomes active");
			created.end();
			created.end();
			assertTrue(!created.active(), "installed controller ends idempotently");

			OperatorBodyControllers.install((server, id) -> null);
			expectFailure(() -> OperatorBodyControllers.create(null, agentId), "factory returning null");
		} finally {
			OperatorBodyControllers.reset();
		}
		assertTrue(!OperatorBodyControllers.create(null, agentId).active(), "reset restores the no-op default");
	}

	private static List<ItemStack> stacks(int count) {
		return Collections.nCopies(count, ItemStack.EMPTY);
	}

	private static PovInventory inventory(int selectedSlot, List<ItemStack> hotbar, List<ItemStack> armor) {
		return new PovInventory(selectedSlot, hotbar, ItemStack.EMPTY, armor);
	}

	private static PovVitals vitals(float health, int food, int air, float xpProgress) {
		return new PovVitals(health, 20f, 0f, 0, food, 5f, air, 300, 3, xpProgress, GameType.SURVIVAL);
	}

	private static PovIdentity identity(PovMode mode, String name) {
		return new PovIdentity(1L, 0, mode, AGENT_UUID, 7, Level.OVERWORLD, name, 0);
	}

	private static AgentPovStatePayload state(List<MobEffectInstance> effects) {
		return new AgentPovStatePayload(identity(PovMode.SPECTATE, "Builder"), vitals(20f, 20, 300, 0f),
				PovInventory.empty(), effects, Optional.empty(), Optional.empty());
	}

	private static AgentPovMenuPayload menuPayload(int slotCount, int dataSlotCount) {
		return new AgentPovMenuPayload(1L, 1, 0, stacks(slotCount), ItemStack.EMPTY,
				Collections.nCopies(dataSlotCount, 0));
	}

	private static OperatorInputPayload input(int sequence, float forward, float strafe, float pitch, int held, int slot) {
		return new OperatorInputPayload(1L, sequence, forward, strafe, 0f, pitch, held, slot);
	}

	private static <T> T roundTrip(
			StreamCodec<RegistryFriendlyByteBuf, T> codec,
			T value,
			RegistryAccess registries,
			String label
	) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		try {
			codec.encode(buffer, value);
			T decoded = codec.decode(buffer);
			assertEquals(0, buffer.readableBytes(), label + " codec consumes exactly what it wrote");
			return decoded;
		} finally {
			buffer.release();
		}
	}

	private static void expectFailure(Runnable operation, String label) {
		try {
			operation.run();
		} catch (IllegalArgumentException | NullPointerException expected) {
			assertions++;
			return;
		}
		throw new AssertionError(label + " should be rejected");
	}

	private static void expectUnsupported(Runnable operation, String label) {
		expectThrows(UnsupportedOperationException.class, operation, label);
	}

	private static void expectThrows(Class<? extends RuntimeException> type, Runnable operation, String label) {
		try {
			operation.run();
		} catch (RuntimeException exception) {
			if (!type.isInstance(exception)) {
				throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
			}
			assertions++;
			return;
		}
		throw new AssertionError(label + " should throw " + type.getSimpleName());
	}

	private static void assertTrue(boolean actual, String label) {
		if (!actual) {
			throw new AssertionError(label);
		}
		assertions++;
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
		assertions++;
	}

	private static final class RecordingController implements OperatorBodyController {
		private boolean active;

		@Override
		public void begin(ServerPlayer operator) {
			active = true;
		}

		@Override
		public void applyFrame(OperatorInputPayload frame) {
		}

		@Override
		public void applyAction(ServerPlayer operator, OperatorActionPayload action) {
		}

		@Override
		public void tick() {
		}

		@Override
		public void end() {
			active = false;
		}

		@Override
		public boolean active() {
			return active;
		}
	}
}
