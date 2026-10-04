package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuInspection;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuStackIdentity;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.ArrayList;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

public final class AdvancedInteractionRollbackVerification {
	private AdvancedInteractionRollbackVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PASS: " + verify() + " advanced interaction rollback assertions");
	}

	public static int verify() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		ElapsedTimeAccumulator elapsed = new ElapsedTimeAccumulator(1_000L);
		assertEquals(100L, elapsed.advance(1_100L), "transaction elapsed time advances normally");
		assertEquals(100L, elapsed.advance(900L), "transaction elapsed time survives clock rollback");
		assertEquals(Long.MAX_VALUE, new ElapsedTimeAccumulator(Long.MIN_VALUE).advance(Long.MAX_VALUE),
				"transaction elapsed time saturates timestamp overflow");
		try (ComponentBindings components = new ComponentBindings()) {
			AssertionError failure = null;
			try {
				verifyToolSelectionRollback(components);
			} catch (AssertionError assertionFailure) {
				failure = assertionFailure;
			}
			try {
				verifyDropExceptionRollback(components);
			} catch (AssertionError assertionFailure) {
				if (failure == null) failure = assertionFailure;
				else failure.addSuppressed(assertionFailure);
			}
			verifyMenuCursorAndRevision(components);
			verifyConsumingResultInput(components);
			verifyStackIdentity(components);
			verifyBeaconEffects(components);
			verifyPartialMenuInput(components);
			verifyNativeCraftTransaction(components);
			verifyNativePickaxeTransaction(components);
			if (failure != null) throw failure;
		}
		return 76;
	}

	private static void verifyNativeCraftTransaction(ComponentBindings components) {
		// Real RecipeManager, InventoryMenu, placement and quickMoveStack; only player/world effects are fixtures.
		components.stack(Items.OAK_PLANKS, 1, 0);
		Fixture fixture = craftFixture();
		fixture.inventory().setItem(0, components.stack(Items.OAK_LOG, 2, 0));
		var args = json("recipeId", "minecraft:oak_planks", "count", 4, "timeoutMs", 5000);
		var transaction = service().begin(fixture.player(), request(ActionType.CRAFT_INVENTORY, args), args);
		var result = transaction.tick(System.currentTimeMillis());
		assertEquals("CRAFT_CONFIRMED", result.reasonCode(), "native log recipe reaches commit: " + result.message());
		transaction.cleanup();
		assertEquals(1, count(fixture, Items.OAK_LOG), "one native craft consumes one log");
		assertEquals(4, count(fixture, Items.OAK_PLANKS), "one native craft owns four planks");
		assertTrue(fixture.player().inventoryMenu.getInputGridSlots().stream().allMatch(slot -> !slot.hasItem()), "native grid cleaned");
		assertTrue(fixture.player().inventoryMenu.getCarried().isEmpty(), "native cursor cleaned");
		assertEquals(result, transaction.tick(System.currentTimeMillis()), "terminal craft retry is stable");
		transaction.cleanup();
		assertEquals(4, count(fixture, Items.OAK_PLANKS), "terminal retry and cleanup cannot duplicate output");

		Fixture failed = craftFixture();
		failed.inventory().setItem(0, components.stack(Items.OAK_LOG, 2, 0));
		int[] commits = {0};
		AdvancedInteractionService faulting = new AdvancedInteractionService(
				ServerProtectionPolicy.TRUSTED_LOCAL_OPERATOR, new ResourceLeaseManager(), (menu, player, slot) -> {
					ItemStack moved = menu.quickMoveStack(player, slot);
					assertEquals(4, moved.getCount(), "fault happens after actual native result movement");
					commits[0]++;
					throw new IllegalStateException("owned postcommit craft fault");
				});
		var fault = faulting.begin(failed.player(), request(ActionType.CRAFT_INVENTORY, args), args);
		var rejected = fault.tick(System.currentTimeMillis());
		assertEquals("CRAFT_POSTCOMMIT_EXCEPTION", rejected.reasonCode(), "postcommit exception restores native transaction: " + rejected.message());
		fault.cleanup();
		assertEquals(2, count(failed, Items.OAK_LOG), "postcommit rollback restores original logs");
		assertEquals(0, count(failed, Items.OAK_PLANKS), "postcommit rollback removes crafted output");
		assertEquals(rejected, fault.tick(System.currentTimeMillis()), "failed terminal retry is stable");
		assertEquals(1, commits[0], "failed transaction cannot recommit");
		var retry = service().begin(failed.player(), request(ActionType.CRAFT_INVENTORY, args), args);
		assertEquals("CRAFT_CONFIRMED", retry.tick(System.currentTimeMillis()).reasonCode(), "new craft succeeds after rollback");
		retry.cleanup();
		assertEquals(1, count(failed, Items.OAK_LOG), "retry consumes only one original log");
		assertEquals(4, count(failed, Items.OAK_PLANKS), "retry produces only one native result");
	}

	private static int count(Fixture fixture, Item item) {
		int count = 0;
		for (int slot = 0; slot < fixture.inventory().getContainerSize(); slot++) {
			ItemStack stack = fixture.inventory().getItem(slot);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	private static void verifyNativePickaxeTransaction(ComponentBindings components) {
		components.stack(Items.STICK, 1, 0);
		components.stack(Items.WOODEN_PICKAXE, 1, 59);
		Fixture fixture = craftFixture();
		fixture.inventory().setItem(0, components.stack(Items.OAK_PLANKS, 3, 0));
		fixture.inventory().setItem(1, components.stack(Items.STICK, 2, 0));
		var args = json("recipeId", "minecraft:wooden_pickaxe", "count", 1, "timeoutMs", 5000, "x", 0, "y", 64, "z", 0);
		var transaction = service().begin(fixture.player(), request(ActionType.CRAFT_TABLE, args), args);
		var result = transaction.tick(System.currentTimeMillis());
		assertEquals("CRAFT_CONFIRMED", result.reasonCode(), "native pickaxe table recipe commits: " + result.message());
		assertEquals(net.minecraft.world.inventory.CraftingMenu.class, fixture.player().containerMenu.getClass(), "native crafting table menu used");
		transaction.cleanup();
		assertEquals(0, count(fixture, Items.OAK_PLANKS), "pickaxe consumes three planks");
		assertEquals(0, count(fixture, Items.STICK), "pickaxe consumes two sticks");
		assertEquals(1, count(fixture, Items.WOODEN_PICKAXE), "pickaxe ownership is exact");
		assertTrue(fixture.player().containerMenu == fixture.player().inventoryMenu, "table menu cleanup returns to inventory");
		transaction.cleanup(); transaction.tick(System.currentTimeMillis());
		assertEquals(1, count(fixture, Items.WOODEN_PICKAXE), "table terminal retries cannot duplicate pickaxe");
	}

	private static Fixture craftFixture() {
		Fixture fixture = fixture();
		fixture.player().fixtureLevel.recipes = new FixtureRecipes();
		fixture.player().fixtureLevel.rules = new net.minecraft.world.level.gamerules.GameRules(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			fixture.player().connection = (FixtureConnection) unsafe.allocateInstance(FixtureConnection.class);
			FixtureServer server = (FixtureServer) unsafe.allocateInstance(FixtureServer.class);
			server.recipes = fixture.player().fixtureLevel.recipes;
			fixture.player().fixtureLevel.server = server;
			setField(unsafe, fixture.player().fixtureLevel, net.minecraft.world.level.Level.class, "dimension", net.minecraft.world.level.Level.OVERWORLD);
			setField(unsafe, fixture.player(), ServerPlayer.class, "gameMode", new FixtureGameMode(fixture.player()));
			setField(unsafe, fixture.player(), net.minecraft.world.entity.Entity.class, "position", new net.minecraft.world.phys.Vec3(0, 64, 1));
			fixture.player().setBoundingBox(new net.minecraft.world.phys.AABB(-0.3, 64, 0.7, 0.3, 65.8, 1.3));
			InventoryMenu menu = new InventoryMenu(fixture.inventory(), true, fixture.player());
			setField(unsafe, fixture.player(), Player.class, "inventoryMenu", menu);
			fixture.player().containerMenu = menu;
			return fixture;
		} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
	}

	private static final class FixtureRecipes extends RecipeManager {
		FixtureRecipes() {
			super(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
			var recipe = new ShapelessRecipe(new Recipe.CommonInfo(false),
					new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.BUILDING, "planks"),
					new ItemStackTemplate(Items.OAK_PLANKS, 4), java.util.List.of(Ingredient.of(Items.OAK_LOG)));
			var holder = new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, Identifier.parse("minecraft:oak_planks")), recipe);
			var pickaxe = new ShapedRecipe(new Recipe.CommonInfo(false),
					new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.EQUIPMENT, ""),
					ShapedRecipePattern.of(Map.of('P', Ingredient.of(Items.OAK_PLANKS), 'S', Ingredient.of(Items.STICK)), "PPP", " S ", " S "),
					new ItemStackTemplate(Items.WOODEN_PICKAXE));
			var pickaxeHolder = new RecipeHolder<>(ResourceKey.create(Registries.RECIPE, Identifier.parse("minecraft:wooden_pickaxe")), pickaxe);
			apply(RecipeMap.create(java.util.List.of(holder, pickaxeHolder)), null, net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
		}
	}

	private static final class FixtureConnection extends net.minecraft.server.network.ServerGamePacketListenerImpl {
		private FixtureConnection() { super(null, null, null, null); }
		@Override public void send(net.minecraft.network.protocol.Packet<?> packet) { }
	}

	private static final class FixtureGameMode extends net.minecraft.server.level.ServerPlayerGameMode {
		private FixtureGameMode(ServerPlayer player) { super(player); }
		@Override public net.minecraft.world.InteractionResult useItemOn(ServerPlayer player, net.minecraft.world.level.Level level,
				ItemStack stack, net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
			player.containerMenu = new net.minecraft.world.inventory.CraftingMenu(1, player.getInventory(),
					net.minecraft.world.inventory.ContainerLevelAccess.create(level, hit.getBlockPos()));
			return net.minecraft.world.InteractionResult.SUCCESS;
		}
	}

	/** Allocated without running a server constructor or starting any threads. */
	private static final class FixtureServer extends net.minecraft.server.dedicated.DedicatedServer {
		private RecipeManager recipes;
		private FixtureServer() { super(null, null, null, null, java.util.Optional.empty(), null, null, null); }
		@Override public RecipeManager getRecipeManager() { return recipes; }
	}

	private static void verifyPartialMenuInput(ComponentBindings components) {
		Fixture fixture = fixture();
		TestMenu menu = new TestMenu(false);
		fixture.player().containerMenu = menu;
		menu.getSlot(0).set(components.stack(Items.IRON_INGOT, 2, 0));
		menu.failAfterInput = true;
		JsonObject arguments = clickArguments(menu, 0, 0, "PICKUP", "minecraft:iron_ingot", 2);
		assertEquals("MENU_INPUT_PARTIAL", run(fixture, ActionType.MENU_CLICK, arguments).reasonCode(), "exception after vanilla input reports partial state");
		assertTrue(menu.getSlot(0).getItem().isEmpty(), "partial input does not fabricate a source rollback");
		assertEquals(2, menu.getCarried().getCount(), "partial input preserves the actual cursor owner");
		assertTrue(menu.getStateId() != arguments.get("stateId").getAsInt(), "partial mutation invalidates the old observed revision");
		assertEquals("MENU_STATE_CHANGED", run(fixture, ActionType.MENU_CLICK, arguments).reasonCode(), "old input cannot replay after a partial exception");
	}

	private static void verifyBeaconEffects(ComponentBindings components) {
		Fixture fixture = fixture();
		BeaconMenu menu = new BeaconMenu(11, fixture.inventory());
		fixture.player().containerMenu = menu;
		menu.setData(0, 1);
		menu.getSlot(0).set(components.stack(Items.IRON_INGOT, 1, 0));
		assertEquals("BEACON_EFFECT_UNAVAILABLE", run(fixture, ActionType.BEACON_EFFECTS,
				beaconArguments(menu, "minecraft:strength", "none")).reasonCode(), "level one cannot buy a higher tier effect");
		assertEquals(1, menu.getSlot(0).getItem().getCount(), "unavailable effect does not consume payment");
		assertEquals("BEACON_EFFECT_UNAVAILABLE", run(fixture, ActionType.BEACON_EFFECTS,
				beaconArguments(menu, "minecraft:speed", "minecraft:regeneration")).reasonCode(), "secondary effects need a full beacon");
		assertEquals("BEACON_PRIMARY_REQUIRED", run(fixture, ActionType.BEACON_EFFECTS,
				beaconArguments(menu, "none", "none")).reasonCode(), "confirmation needs a selected primary");
		menu.setData(0, 4);
		var paymentHolder = Items.IRON_INGOT.builtInRegistryHolder();
		var originalTags = paymentHolder.tags().toList();
		try {
			var bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
			bindTags.setAccessible(true);
			var tags = new ArrayList<>(originalTags);
			tags.add(net.minecraft.tags.ItemTags.BEACON_PAYMENT_ITEMS);
			bindTags.invoke(paymentHolder, tags);
			try {
				JsonObject arguments = beaconArguments(menu, "minecraft:speed", "minecraft:speed");
				ServerTransactionAdapter.ActiveTransaction transaction = service().begin(fixture.player(), request(ActionType.BEACON_EFFECTS, arguments), arguments);
				assertEquals("BEACON_EFFECTS_SET", transaction.tick(System.currentTimeMillis()).reasonCode(), "actual vanilla beacon accepts a legal selection");
				transaction.cleanup();
				assertEquals(net.minecraft.world.effect.MobEffects.SPEED, menu.getPrimaryEffect(), "vanilla primary effect matches the model choice");
				assertEquals(net.minecraft.world.effect.MobEffects.SPEED, menu.getSecondaryEffect(), "matching secondary upgrades the primary");
				assertTrue(menu.getSlot(0).getItem().isEmpty(), "vanilla consumed exactly one payment");
				assertEquals("BEACON_EFFECTS_SET", transaction.tick(System.currentTimeMillis()).reasonCode(), "beacon result replay is terminal");
				assertEquals("BEACON_PAYMENT_REQUIRED", run(fixture, ActionType.BEACON_EFFECTS,
						beaconArguments(menu, "minecraft:haste", "none")).reasonCode(), "next selection requires another actual payment");
				assertEquals(net.minecraft.world.effect.MobEffects.SPEED, menu.getPrimaryEffect(), "missing payment preserves the earlier effect");
			} finally {
				bindTags.invoke(paymentHolder, originalTags);
			}
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not bind the isolated beacon payment fixture", exception);
		}
		assertTrue(menu.getSlot(0).getItem().isEmpty(), "failed beacon request cannot restore spent payment");
	}

	private static JsonObject beaconArguments(AbstractContainerMenu menu, String primary, String secondary) {
		return json("menuId", "minecraft:beacon", "containerId", menu.containerId, "stateId", menu.getStateId(),
				"primaryEffectId", primary, "secondaryEffectId", secondary);
	}

	private static void verifyStackIdentity(ComponentBindings components) {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		ItemStack stack = components.stack(Items.IRON_SWORD, 1, 250);
		String original = MenuStackIdentity.fingerprint(stack, registries);
		stack.setCount(2);
		assertEquals(original, MenuStackIdentity.fingerprint(stack, registries), "stack count is separate from variant identity");
		stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Player choice"));
		String renamed = MenuStackIdentity.fingerprint(stack, registries);
		assertTrue(!renamed.equals(original), "component mutation invalidates the cached fingerprint");
		assertEquals(renamed, MenuStackIdentity.fingerprint(stack.copy(), registries), "equivalent stack copies share a stable identity");
		assertEquals("", MenuStackIdentity.fingerprint(ItemStack.EMPTY, registries), "empty slot has an explicit empty identity");
	}

	private static void verifyMenuCursorAndRevision(ComponentBindings components) {
		Fixture fixture = fixture();
		TestMenu menu = new TestMenu(false);
		fixture.player().containerMenu = menu;
		menu.getSlot(0).set(components.stack(Items.IRON_INGOT, 5, 0));
		menu.getSlot(1).set(components.stack(Items.GOLD_INGOT, 2, 0));
		JsonObject pickup = clickArguments(menu, 0, 1, "PICKUP", "minecraft:iron_ingot", 5);
		ServerTransactionAdapter.ActiveTransaction transaction = service().begin(fixture.player(), request(ActionType.MENU_CLICK, pickup), pickup);
		ServerTransactionAdapter.TickResult pickupResult = transaction.tick(System.currentTimeMillis());
		assertEquals(ServerTransactionAdapter.TickState.SUCCEEDED, pickupResult.state(), "vanilla right-click pickup succeeds: " + pickupResult);
		transaction.cleanup();
		assertEquals(3, menu.getCarried().getCount(), "cursor survives transaction cleanup for the next model choice");
		assertEquals(2, menu.getSlot(0).getItem().getCount(), "right-click leaves the correct half in the source");
		assertEquals(ServerTransactionAdapter.TickState.SUCCEEDED, transaction.tick(System.currentTimeMillis()).state(), "same transaction returns its terminal result");
		assertEquals(3, menu.getCarried().getCount(), "terminal replay does not apply input twice");
		ServerTransactionAdapter.TickResult stale = run(fixture, ActionType.MENU_CLICK, pickup);
		assertEquals("MENU_STATE_CHANGED", stale.reasonCode(), "old observation cannot apply another input");
		assertEquals(3, menu.getCarried().getCount(), "stale input preserves the cursor");
		assertEquals(ServerTransactionAdapter.TickState.SUCCEEDED,
				run(fixture, ActionType.MENU_CLICK, clickArguments(menu, 1, 0, "PICKUP", "minecraft:gold_ingot", 2)).state(),
				"occupied slot swaps through vanilla input");
		assertEquals("minecraft:gold_ingot", itemId(menu.getCarried()), "old destination becomes the carried stack");
		assertEquals(3, menu.getSlot(1).getItem().getCount(), "chosen carried variant reaches the occupied destination");
		JsonObject wrongInstance = clickArguments(menu, 2, 0, "PICKUP", "minecraft:air", 0);
		wrongInstance.addProperty("containerId", menu.containerId + 1);
		assertEquals("MENU_MISMATCH", run(fixture, ActionType.MENU_CLICK, wrongInstance).reasonCode(), "reopened menu identity must match");
		assertEquals("minecraft:gold_ingot", itemId(menu.getCarried()), "instance failure preserves carried ownership");
		JsonObject incompleteSession = json("menuId", MenuInspection.menuId(menu), "containerId", menu.containerId, "buttonId", 0, "timeoutMs", 5000);
		assertEquals("MENU_SESSION_REQUIRED", run(fixture, ActionType.MENU_BUTTON, incompleteSession).reasonCode(), "legacy button revision fields are both or neither");
		JsonObject staleRename = json("menuId", MenuInspection.menuId(menu), "containerId", menu.containerId,
				"stateId", menu.getStateId() + 1, "name", "stale", "timeoutMs", 5000);
		assertEquals("MENU_STATE_CHANGED", run(fixture, ActionType.ANVIL_RENAME, staleRename).reasonCode(), "legacy rename enforces a supplied revision before mutation");
	}

	private static void verifyConsumingResultInput(ComponentBindings components) {
		Fixture fixture = fixture();
		TestMenu menu = new TestMenu(true);
		fixture.player().containerMenu = menu;
		menu.getSlot(0).set(components.stack(Items.IRON_INGOT, 1, 0));
		menu.getSlot(1).set(components.stack(Items.IRON_SWORD, 1, 250));
		JsonObject transfer = json("menuId", "minecraft:anvil", "sourceSlot", 1, "destinationSlot", 2,
				"expectedItemId", "minecraft:iron_sword", "count", 1, "timeoutMs", 5000);
		transfer.addProperty("containerId", menu.containerId);
		transfer.addProperty("stateId", menu.getStateId() + 1);
		assertEquals("MENU_STATE_CHANGED", run(fixture, ActionType.MENU_TRANSFER, transfer).reasonCode(), "legacy transfer enforces supplied session state");
		assertEquals(1, menu.getSlot(0).getItem().getCount(), "stale consuming result request leaves inputs intact");
		transfer.addProperty("stateId", menu.getStateId());
		ServerTransactionAdapter.TickResult result = run(fixture, ActionType.MENU_TRANSFER, transfer);
		assertEquals("TRANSACTION_CONFIRMED", result.reasonCode(), "consuming result operation accepts vanilla input consumption");
		assertTrue(menu.getSlot(0).getItem().isEmpty(), "vanilla result callback consumed its input");
		assertTrue(menu.getSlot(1).getItem().isEmpty(), "result was taken once");
		assertEquals("minecraft:iron_sword", itemId(menu.getSlot(2).getItem()), "output reaches the selected destination");
	}

	private static JsonObject clickArguments(AbstractContainerMenu menu, int slot, int button, String input, String itemId, int count) {
		return json("menuId", MenuInspection.menuId(menu), "containerId", menu.containerId, "stateId", menu.getStateId(),
				"slot", slot, "button", button, "clickType", input, "expectedItemId", itemId, "expectedCount", count);
	}

	private static ServerTransactionAdapter.TickResult run(Fixture fixture, ActionType type, JsonObject arguments) {
		ServerTransactionAdapter.ActiveTransaction transaction = service().begin(fixture.player(), request(type, arguments), arguments);
		ServerTransactionAdapter.TickResult result = transaction.tick(System.currentTimeMillis());
		transaction.cleanup();
		return result;
	}

	private static final class TestMenu extends AbstractContainerMenu {
		private boolean failAfterInput;
		private TestMenu(boolean consuming) {
			super(consuming ? MenuType.ANVIL : MenuType.GENERIC_9x1, 7);
			SimpleContainer container = new SimpleContainer(3);
			addSlot(new Slot(container, 0, 0, 0));
			addSlot(consuming ? new Slot(container, 1, 0, 0) {
				@Override
				public boolean mayPlace(ItemStack stack) { return false; }
				@Override
				public void onTake(Player player, ItemStack stack) {
					container.removeItem(0, 1);
					super.onTake(player, stack);
				}
			} : new Slot(container, 1, 0, 0));
			addSlot(new Slot(container, 2, 0, 0));
		}
		@Override
		public boolean stillValid(Player player) { return true; }
		@Override
		public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
		@Override
		public void clicked(int slot, int button, ContainerInput input, Player player) {
			super.clicked(slot, button, input, player);
			if (failAfterInput) throw new IllegalStateException("injected exception after vanilla menu mutation");
		}
	}

	private static void verifyToolSelectionRollback(ComponentBindings components) {
		Fixture fixture = fixture();
		ItemStack pickaxe = components.stack(Items.IRON_PICKAXE, 1, 250);
		fixture.inventory().setItem(9, pickaxe);
		fixture.inventory().setSelectedSlot(2);
		fixture.player().failMainHandVerification = true;

		JsonObject arguments = json(
				"sourceSlot", 9,
				"hotbarSlot", 0,
				"expectedItemId", "minecraft:iron_pickaxe",
				"minRemainingDurability", 1
		);
		ServerActionRequest request = request(ActionType.SELECT_TOOL, arguments);
		ServerTransactionAdapter.ActiveTransaction transaction = service().begin(fixture.player(), request, arguments);
		ServerTransactionAdapter.TickResult result = transaction.tick(System.currentTimeMillis());

		assertEquals(ServerTransactionAdapter.TickState.FAILED, result.state(), "selection verification fails");
		assertEquals("SELECTION_NOT_CONFIRMED", result.reasonCode(), "selection failure reason is preserved");
		assertEquals("minecraft:iron_pickaxe", itemId(fixture.inventory().getItem(9)),
				"failed selection restores the source tool");
		assertTrue(fixture.inventory().getItem(0).isEmpty(), "failed selection clears the destination hotbar slot");
		assertEquals(2, fixture.inventory().getSelectedSlot(), "failed selection restores the previous selected slot");
	}

	private static void verifyDropExceptionRollback(ComponentBindings components) {
		Fixture fixture = fixture();
		fixture.inventory().setItem(4, components.stack(Items.OAK_LOG, 4, 0));
		fixture.player().throwOnDrop = true;

		AdvancedInteractionService.Result result;
		try {
			result = service().drop(fixture.player(), 4, 2);
		} catch (RuntimeException exception) {
			throw new AssertionError("throwing drop must report failure without leaking an exception", exception);
		}

		assertTrue(!result.succeeded(), "throwing drop reports failure");
		assertEquals("ITEM_DROP_FAILED", result.reasonCode(), "throwing drop has a stable failure reason");
		assertEquals("minecraft:oak_log", itemId(fixture.inventory().getItem(4)),
				"throwing drop restores the item identity");
		assertEquals(4, fixture.inventory().getItem(4).getCount(), "throwing drop restores the debited count");
	}

	private static AdvancedInteractionService service() {
		return new AdvancedInteractionService(ServerProtectionPolicy.TRUSTED_LOCAL_OPERATOR, new ResourceLeaseManager());
	}

	private static ServerActionRequest request(ActionType type, JsonObject arguments) {
		return new ServerActionRequest(
				AgentId.random(),
				1,
				"rollback-verification",
				type,
				arguments,
				new ActionProvenance("test", "test", "test", "test", "program", 1, "step", 1)
		);
	}

	private static JsonObject json(Object... fields) {
		JsonObject object = new JsonObject();
		for (int index = 0; index < fields.length; index += 2) {
			String name = (String) fields[index];
			Object value = fields[index + 1];
			if (value instanceof String text) object.addProperty(name, text);
			else if (value instanceof Number number) object.addProperty(name, number);
			else throw new IllegalArgumentException("Unsupported fixture value: " + value);
		}
		return object;
	}

	private static Fixture fixture() {
		try {
			Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
			FaultingServerPlayer player = (FaultingServerPlayer) unsafe.allocateInstance(FaultingServerPlayer.class);
			player.fixtureLevel = (MenuFixtureLevel) unsafe.allocateInstance(MenuFixtureLevel.class);
			EntityEquipment equipment = new EntityEquipment();
			Inventory inventory = new Inventory(player, equipment);
			setField(unsafe, player, Player.class, "inventory", inventory);
			setField(unsafe, player, LivingEntity.class, "equipment", equipment);
			InventoryMenu inventoryMenu = new InventoryMenu(inventory, false, player);
			setField(unsafe, player, Player.class, "inventoryMenu", inventoryMenu);
			player.containerMenu = inventoryMenu;
			return new Fixture(player, inventory);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not create server player fixture", exception);
		}
	}

	private static void setField(sun.misc.Unsafe unsafe, Object target, Class<?> owner, String name, Object value)
			throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		unsafe.putObject(target, unsafe.objectFieldOffset(field), value);
	}

	private static String itemId(ItemStack stack) {
		return stack.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private record Fixture(FaultingServerPlayer player, Inventory inventory) {
	}

	private static final class ComponentBindings implements AutoCloseable {
		private final IdentityHashMap<Holder.Reference<Item>, DataComponentMap> originals = new IdentityHashMap<>();

		private ItemStack stack(Item item, int count, int maxDamage) {
			Holder.Reference<Item> holder = item.builtInRegistryHolder();
			if (!originals.containsKey(holder)) {
				DataComponentMap original = holder.areComponentsBound() ? holder.components() : null;
				originals.put(holder, original);
				DataComponentMap.Builder components = DataComponentMap.builder();
				if (original != null) components.addAll(original);
				components.set(DataComponents.MAX_STACK_SIZE, maxDamage > 0 ? 1 : 64);
				if (maxDamage > 0) components.set(DataComponents.MAX_DAMAGE, maxDamage);
				holder.bindComponents(components.build());
			}
			return new ItemStack(item, count);
		}

		@Override
		public void close() {
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
	}

	private static final class FaultingServerPlayer extends ServerPlayer {
		private MenuFixtureLevel fixtureLevel;
		private boolean failMainHandVerification;
		private boolean throwOnDrop;

		private FaultingServerPlayer(
				MinecraftServer server,
				ServerLevel level,
				GameProfile profile,
				ClientInformation clientInformation
		) {
			super(server, level, profile, clientInformation);
		}

		@Override
		public boolean isAlive() {
			return true;
		}

		@Override
		public ServerLevel level() { return fixtureLevel; }

		@Override
		public boolean isSpectator() { return false; }

		@Override
		public boolean isUsingItem() { return false; }

		@Override public boolean isCreative() { return false; }
		@Override public net.minecraft.stats.ServerRecipeBook getRecipeBook() {
			return new net.minecraft.stats.ServerRecipeBook((key, output) -> { });
		}
		@Override public void awardStat(net.minecraft.stats.Stat<?> stat, int amount) { }
		@Override public int awardRecipes(java.util.Collection<RecipeHolder<?>> recipes) { return 0; }
		@Override public void triggerRecipeCrafted(RecipeHolder<?> recipe, java.util.List<ItemStack> ingredients) { }
		@Override public boolean isWithinBlockInteractionRange(net.minecraft.core.BlockPos position, double padding) { return true; }
		@Override public boolean isDescending() { return false; }
		@Override public void closeContainer() {
			containerMenu.removed(this);
			containerMenu = inventoryMenu;
		}

		@Override
		public void updateTutorialInventoryAction(ItemStack carried, ItemStack target, ClickAction action) {
		}

		@Override
		public ItemStack getMainHandItem() {
			return failMainHandVerification ? ItemStack.EMPTY : super.getMainHandItem();
		}

		@Override
		public ItemEntity drop(ItemStack stack, boolean randomDirection) {
			if (throwOnDrop) throw new IllegalStateException("injected drop failure after inventory debit");
			return super.drop(stack, randomDirection);
		}
	}

	private static final class MenuFixtureLevel extends ServerLevel {
		private RecipeManager recipes;
		private MinecraftServer server;
		private net.minecraft.world.level.gamerules.GameRules rules;
		private MenuFixtureLevel() {
			super(null, Runnable::run, null, null, net.minecraft.world.level.Level.OVERWORLD, null, false, 0L, java.util.List.of(), false);
		}

		@Override
		public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
			return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
		}
		@Override public RecipeManager recipeAccess() { return recipes; }
		@Override public MinecraftServer getServer() { return server; }
		@Override public net.minecraft.world.level.gamerules.GameRules getGameRules() { return rules; }
		@Override public boolean hasChunkAt(net.minecraft.core.BlockPos position) { return true; }
		@Override public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos position) {
			return net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState();
		}
		@Override public net.minecraft.world.phys.BlockHitResult clip(net.minecraft.world.level.ClipContext context) {
			return new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(0.5, 64.5, 0.5),
					net.minecraft.core.Direction.SOUTH, new net.minecraft.core.BlockPos(0, 64, 0), false);
		}
	}
}
