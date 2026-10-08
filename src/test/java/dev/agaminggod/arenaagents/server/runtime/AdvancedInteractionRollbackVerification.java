package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import dev.agaminggod.arenaagents.server.runtime.menu.AgentInventoryView;
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
			verifyMultiCraftTransaction(components);
			verifyNativePickaxeTransaction(components);
			verifyCraftDeath(components);
			verifyHorizontalFacingPlacement(components);
			verifyPacedFurnaceTransaction(components);
			verifyPacedToolSelection(components);
			verifyCraftPickupDuringPacing(components);
			verifyCraftFailureWithFullInventory(components);
			verifyExternallyClosedCraftMenu(components);
			if (failure != null) throw failure;
		}
		return 174;
	}

	/** Tick a table pickaxe craft until both planks cells are filled and one plank is still on the cursor. */
	private static ServerTransactionAdapter.ActiveTransaction halfFilledPickaxe(ComponentBindings components, Fixture fixture) {
		fixture.inventory().setItem(0, components.stack(Items.OAK_PLANKS, 3, 0));
		fixture.inventory().setItem(1, components.stack(Items.STICK, 2, 0));
		var args = json("recipeId", "minecraft:wooden_pickaxe", "count", 1, "timeoutMs", 5000, "x", 0, "y", 64, "z", 0);
		var transaction = service().begin(fixture.player(), request(ActionType.CRAFT_TABLE, args), args);
		for (int tick = 0; tick < 200; tick++) {
			transaction.tick(System.currentTimeMillis());
			if (fixture.player().containerMenu instanceof net.minecraft.world.inventory.CraftingMenu crafting
					&& crafting.getInputGridSlots().stream().filter(Slot::hasItem).count() == 2) {
				return transaction;
			}
		}
		throw new AssertionError("pickaxe craft never reached a half-filled grid");
	}

	private static void verifyCraftDeath(ComponentBindings components) {
		components.stack(Items.STICK, 1, 0);
		components.stack(Items.WOODEN_PICKAXE, 1, 59);
		// Death mid-fill: the inventory already dropped as death loot, so the grid and cursor drop at the body.
		Fixture dying = craftFixture();
		var transaction = halfFilledPickaxe(components, dying);
		var menu = dying.player().containerMenu;
		assertEquals(1, menu.getCarried().getCount(), "one plank is on the cursor when the agent dies");
		dying.inventory().clearContent();
		dying.player().dead = true;
		dying.player().recordDrops = true;
		var died = tickToEnd(dying, transaction).result();
		assertEquals("AGENT_DEAD", died.reasonCode(), "death fails an uncommitted craft");
		transaction.cleanup();
		int droppedPlanks = dying.player().dropped.stream().filter(stack -> stack.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum();
		assertEquals(3, droppedPlanks, "grid and cursor planks drop at the body instead of vanishing");
		assertEquals(0, count(dying, Items.OAK_PLANKS), "nothing is put back into the dead player's inventory");
		assertTrue(menu.getCarried().isEmpty(), "dead agent's cursor is cleared");
		assertTrue(((net.minecraft.world.inventory.CraftingMenu) menu).getInputGridSlots().stream().noneMatch(Slot::hasItem), "dead agent's grid is cleared");

		// Death after the result was taken: the craft really happened and is reported as a success.
		Fixture late = craftFixture();
		var committed = halfFilledPickaxe(components, late);
		for (int tick = 0; tick < 200 && count(late, Items.WOODEN_PICKAXE) == 0; tick++) {
			assertEquals(ServerTransactionAdapter.TickState.RUNNING, committed.tick(System.currentTimeMillis()).state(), "craft runs until commit");
		}
		assertEquals(1, count(late, Items.WOODEN_PICKAXE), "pickaxe committed before the menu closes");
		late.player().dead = true;
		var settled = committed.tick(System.currentTimeMillis());
		assertEquals("CRAFT_CONFIRMED", settled.reasonCode(), "death while the menu closes keeps the committed success");
		committed.cleanup();

		// Cancel during the linger after the take: the pickaxe exists, so the craft reports success, not a cancel.
		Fixture lingering = craftFixture();
		var cancelled = halfFilledPickaxe(components, lingering);
		for (int tick = 0; tick < 200 && count(lingering, Items.WOODEN_PICKAXE) == 0; tick++) cancelled.tick(System.currentTimeMillis());
		assertEquals("CRAFT_CONFIRMED", cancelled.committedResult().reasonCode(), "the lingering craft exposes its committed result");
		cancelled.cancel("operator stop");
		var reported = cancelled.tick(System.currentTimeMillis());
		assertEquals(ServerTransactionAdapter.TickState.SUCCEEDED, reported.state(), "a cancel in the linger reports the craft that happened");
		assertEquals(1, count(lingering, Items.WOODEN_PICKAXE), "and the pickaxe is kept");
	}

	/**
	 * Directional placement used to write the facing yaw in the placing tick (a north piston from a view facing
	 * north snapped the camera 170 degrees). Now the needed look is found by same-tick prediction (rotation
	 * restored), reached through the same eased steps and AimGate as every other aim, and eased back afterwards.
	 */
	private static void verifyHorizontalFacingPlacement(ComponentBindings components) {
		Fixture fixture = craftFixture();
		ItemStack piston = components.stack(Items.PISTON, 1, 0);
		// Floor placement next to the agent: the aim at the support's top face pitches the view well down.
		var hit = new net.minecraft.world.phys.BlockHitResult(new net.minecraft.world.phys.Vec3(0.5D, 63.999D, 0.5D),
				net.minecraft.core.Direction.UP, new net.minecraft.core.BlockPos(0, 63, 0), false);
		var north = DesiredBlockState.parse("minecraft:piston[facing=north]", "minecraft:piston");
		fixture.player().setYRot(170.0F);
		fixture.player().setXRot(58.0F);
		var faceAim = new ServerActionExecutor.Look(-135.0F, 66.0F);
		var look = ServerActionExecutor.requiredPlacementLook(fixture.player(), (net.minecraft.world.item.BlockItem) Items.PISTON,
				piston, hit, north, faceAim);
		assertEquals(170.0F, fixture.player().getYRot(), "choosing the look leaves the yaw untouched");
		assertEquals(58.0F, fixture.player().getXRot(), "choosing the look leaves the pitch untouched");
		assertTrue(look != null && Math.abs(look.pitch()) <= ServerActionExecutor.PLACEMENT_LEVEL_PITCH_DEGREES,
				"horizontal facings are placed from a leveled view, was " + look);
		assertTrue(ServerActionExecutor.placementMatchesAt(fixture.player(), (net.minecraft.world.item.BlockItem) Items.PISTON,
				piston, hit, north, look), "vanilla places a north piston from the chosen look");
		assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(look.yaw())) <= ServerActionExecutor.PLACEMENT_LOOK_OFFSET_DEGREES,
				"the chosen yaw stays as close to the face aim as the facing allows, was " + look.yaw());

		// The executor's aim phase: eased steps through AimGate, an exact final step, then easing back to the face aim.
		float yaw = fixture.player().getYRot();
		float pitch = fixture.player().getXRot();
		float maxStep = 0.0F;
		AimGate gate = new AimGate();
		AimGate.State state = AimGate.State.AIMING;
		for (int tick = 0; tick < AimGate.MAX_TICKS && state == AimGate.State.AIMING; tick++) {
			float nextYaw = dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates.turnYaw(yaw, look.yaw());
			float nextPitch = dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates.turnPitch(pitch, look.pitch());
			maxStep = Math.max(maxStep, Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(nextYaw - yaw)), Math.abs(nextPitch - pitch)));
			yaw = nextYaw;
			pitch = nextPitch;
			state = gate.observe(yaw, pitch, look.yaw(), look.pitch());
		}
		assertEquals(AimGate.State.READY, state, "the view settles on the placement look");
		float finalStep = Math.abs(net.minecraft.util.Mth.wrapDegrees(look.yaw() - yaw)) + Math.abs(look.pitch() - pitch);
		assertTrue(finalStep <= 2.0F * AimGate.TOLERANCE_DEGREES, "the exact final step is within the gate tolerance");
		yaw = look.yaw();
		pitch = look.pitch();
		for (int tick = 0; tick < 40 && (yaw != faceAim.yaw() || pitch != faceAim.pitch()); tick++) {
			float nextYaw = dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates.turnYaw(yaw, faceAim.yaw());
			float nextPitch = dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates.turnPitch(pitch, faceAim.pitch());
			maxStep = Math.max(maxStep, Math.max(Math.abs(net.minecraft.util.Mth.wrapDegrees(nextYaw - yaw)), Math.abs(nextPitch - pitch)));
			yaw = nextYaw;
			pitch = nextPitch;
		}
		assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - faceAim.yaw())) < 0.01F && pitch == faceAim.pitch(),
				"after placing, the view eases back onto the support face");
		assertTrue(maxStep <= dev.agaminggod.arenaagents.server.runtime.input.AgentInputStates.MAX_TURN_STEP_DEGREES,
				"no tick of the placement turns faster than the player flick limit, max " + maxStep
						+ " (the old in-tick write jumped " + Math.abs(net.minecraft.util.Mth.wrapDegrees(170.0F - 0.0F)) + ")");

		boolean rejected = false;
		try {
			ServerActionExecutor.requiredPlacementLook(fixture.player(), (net.minecraft.world.item.BlockItem) Items.PISTON,
					piston, hit, DesiredBlockState.parse("minecraft:piston[facing=north,extended=true]", "minecraft:piston"), faceAim);
		} catch (dev.agaminggod.arenaagents.agent.AgentDomainException expected) {
			rejected = "PLACEMENT_STATE_MISMATCH".equals(expected.code());
		}
		assertTrue(rejected, "an impossible state is still rejected as PLACEMENT_STATE_MISMATCH");
		assertEquals(170.0F, fixture.player().getYRot(), "a rejected state leaves the yaw untouched");
		assertEquals(58.0F, fixture.player().getXRot(), "a rejected state leaves the pitch untouched");
	}

	private static void verifyNativeCraftTransaction(ComponentBindings components) {
		// Real RecipeManager, InventoryMenu, vanilla slot clicks and quickMoveStack; only player/world effects are fixtures.
		components.stack(Items.OAK_PLANKS, 1, 0);
		Fixture fixture = craftFixture();
		fixture.inventory().setItem(0, components.stack(Items.OAK_LOG, 2, 0));
		var args = json("recipeId", "minecraft:oak_planks", "count", 4, "timeoutMs", 5000);
		var transaction = service().begin(fixture.player(), request(ActionType.CRAFT_INVENTORY, args), args);
		var first = transaction.tick(System.currentTimeMillis());
		assertEquals(ServerTransactionAdapter.TickState.RUNNING, first.state(), "inventory craft opens its screen before clicking");
		assertTrue(AgentInventoryView.isOpen(fixture.player()), "agent inventory screen is mirrored while crafting");
		assertEquals(0, count(fixture, Items.OAK_PLANKS), "no output appears on the opening tick");
		assertEquals(2, count(fixture, Items.OAK_LOG), "planning dry run leaves the inventory untouched");
		CraftTrace trace = tickToEnd(fixture, transaction);
		assertEquals("CRAFT_CONFIRMED", trace.result().reasonCode(), "native log recipe reaches commit: " + trace.result().message());
		assertTrue(trace.maxCarried() > 0, "the log is carried on the cursor between slots");
		assertEquals(1, trace.maxFilledCells(), "the 2x2 grid visibly holds the log before the result is taken");
		assertTrue(trace.ticks() >= 8, "inventory craft is paced over several ticks, was " + trace.ticks());
		transaction.cleanup();
		assertTrue(!AgentInventoryView.isOpen(fixture.player()), "inventory screen closes after crafting");
		assertEquals(1, count(fixture, Items.OAK_LOG), "one native craft consumes one log");
		assertEquals(4, count(fixture, Items.OAK_PLANKS), "one native craft owns four planks");
		assertTrue(fixture.player().inventoryMenu.getInputGridSlots().stream().allMatch(slot -> !slot.hasItem()), "native grid cleaned");
		assertTrue(fixture.player().inventoryMenu.getCarried().isEmpty(), "native cursor cleaned");
		assertEquals(trace.result(), transaction.tick(System.currentTimeMillis()), "terminal craft retry is stable");
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
		var rejected = tickToEnd(failed, fault).result();
		assertEquals("CRAFT_POSTCOMMIT_EXCEPTION", rejected.reasonCode(), "postcommit exception restores native transaction: " + rejected.message());
		fault.cleanup();
		assertEquals(2, count(failed, Items.OAK_LOG), "postcommit rollback restores original logs");
		assertEquals(0, count(failed, Items.OAK_PLANKS), "postcommit rollback removes crafted output");
		assertEquals(rejected, fault.tick(System.currentTimeMillis()), "failed terminal retry is stable");
		assertEquals(1, commits[0], "failed transaction cannot recommit");
		var retry = service().begin(failed.player(), request(ActionType.CRAFT_INVENTORY, args), args);
		assertEquals("CRAFT_CONFIRMED", tickToEnd(failed, retry).result().reasonCode(), "new craft succeeds after rollback");
		retry.cleanup();
		assertEquals(1, count(failed, Items.OAK_LOG), "retry consumes only one original log");
		assertEquals(4, count(failed, Items.OAK_PLANKS), "retry produces only one native result");

		Fixture missing = craftFixture();
		var noLogs = service().begin(missing.player(), request(ActionType.CRAFT_INVENTORY, args), args);
		var unavailable = tickToEnd(missing, noLogs).result();
		assertEquals(ServerTransactionAdapter.TickState.FAILED, unavailable.state(), "craft without ingredients fails honestly");
		assertEquals("RECIPE_INPUTS_UNAVAILABLE", unavailable.reasonCode(), "missing ingredients are named: " + unavailable.message());
		noLogs.cleanup();
		assertTrue(!AgentInventoryView.isOpen(missing.player()), "failed craft closes the inventory screen");
	}

	/** A count above one craft's output stacks several crafts in the grid and takes the result once, as a player does. */
	private static void verifyMultiCraftTransaction(ComponentBindings components) {
		components.stack(Items.OAK_PLANKS, 1, 0);
		components.stack(Items.COBBLESTONE, 1, 0);

		Fixture batch = craftFixture();
		batch.inventory().setItem(0, components.stack(Items.OAK_LOG, 5, 0));
		var sixteen = json("recipeId", "minecraft:oak_planks", "count", 16, "timeoutMs", 5000);
		var transaction = service().begin(batch.player(), request(ActionType.CRAFT_INVENTORY, sixteen), sixteen);
		CraftTrace trace = tickToEnd(batch, transaction);
		transaction.cleanup();
		assertEquals("CRAFT_CONFIRMED", trace.result().reasonCode(), "16 planks craft in one action: " + trace.result().message());
		assertTrue(trace.result().message().contains("Crafted 16 minecraft:oak_planks"), "the result names the full output: " + trace.result().message());
		assertEquals(1, count(batch, Items.OAK_LOG), "four logs were consumed, exactly as four crafts would");
		assertEquals(16, count(batch, Items.OAK_PLANKS), "four crafts made sixteen planks");
		assertEquals(4, trace.maxGridItems(), "the cell visibly holds all four logs before the result is taken");
		assertEquals(1, trace.maxFilledCells(), "one cell holds the stack");
		assertTrue(batch.player().inventoryMenu.getInputGridSlots().stream().noneMatch(Slot::hasItem), "grid cleaned after the batch");
		assertTrue(batch.player().inventoryMenu.getCarried().isEmpty(), "cursor cleaned after the batch");

		Fixture separate = craftFixture();
		separate.inventory().setItem(0, components.stack(Items.OAK_LOG, 5, 0));
		var four = json("recipeId", "minecraft:oak_planks", "count", 4, "timeoutMs", 5000);
		int separateTicks = 0;
		for (int craft = 0; craft < 4; craft++) {
			var single = service().begin(separate.player(), request(ActionType.CRAFT_INVENTORY, four), four);
			separateTicks += tickToEnd(separate, single).ticks();
			single.cleanup();
		}
		assertEquals(16, count(separate, Items.OAK_PLANKS), "four separate crafts make the same sixteen planks");
		assertTrue(trace.ticks() < separateTicks, "one batched action (" + trace.ticks() + " ticks) is quicker than four separate ones ("
				+ separateTicks + " ticks)");

		Fixture short3 = craftFixture();
		short3.inventory().setItem(0, components.stack(Items.OAK_LOG, 3, 0));
		var partialIngredients = service().begin(short3.player(), request(ActionType.CRAFT_INVENTORY, sixteen), sixteen);
		var partial = tickToEnd(short3, partialIngredients).result();
		partialIngredients.cleanup();
		assertEquals("CRAFT_PARTIAL", partial.reasonCode(), "ingredients for three crafts report a partial result: " + partial.message());
		assertEquals(ServerTransactionAdapter.TickState.SUCCEEDED, partial.state(), "the crafts that were possible succeed");
		assertTrue(partial.message().contains("Crafted 12 minecraft:oak_planks") && partial.message().contains("16 were requested")
				&& partial.message().contains("ingredients for only 3 crafts"), "the shortfall is stated: " + partial.message());
		assertEquals(0, count(short3, Items.OAK_LOG), "the three logs were used");
		assertEquals(12, count(short3, Items.OAK_PLANKS), "three crafts made twelve planks");

		Fixture slow = craftFixture();
		slow.inventory().setItem(0, components.stack(Items.OAK_LOG, 64, 0));
		var sixtyFour = json("recipeId", "minecraft:oak_planks", "count", 64, "timeoutMs", 2000);
		var timed = service().begin(slow.player(), request(ActionType.CRAFT_INVENTORY, sixtyFour), sixtyFour);
		var timedResult = tickToEnd(slow, timed).result();
		timed.cleanup();
		assertEquals("CRAFT_PARTIAL", timedResult.reasonCode(), "a batch too slow for timeoutMs is trimmed, not abandoned: " + timedResult.message());
		assertTrue(timedResult.message().contains("timeoutMs 2000 allows only 10 crafts"), "the time limit is named: " + timedResult.message());
		assertEquals(40, count(slow, Items.OAK_PLANKS), "ten crafts made forty planks");
		assertEquals(54, count(slow, Items.OAK_LOG), "ten logs were used");

		Fixture crowded = craftFixture();
		crowded.inventory().setItem(0, components.stack(Items.OAK_LOG, 5, 0));
		crowded.inventory().setItem(1, components.stack(Items.OAK_PLANKS, 60, 0));
		for (int slot = 2; slot < 36; slot++) crowded.inventory().setItem(slot, components.stack(Items.COBBLESTONE, 64, 0));
		var eight = json("recipeId", "minecraft:oak_planks", "count", 8, "timeoutMs", 5000);
		var cramped = service().begin(crowded.player(), request(ActionType.CRAFT_INVENTORY, eight), eight);
		var crampedResult = tickToEnd(crowded, cramped).result();
		cramped.cleanup();
		assertEquals("CRAFT_PARTIAL", crampedResult.reasonCode(), "room for one craft reports a partial result: " + crampedResult.message());
		assertTrue(crampedResult.message().contains("room for only 1 craft"), "the room limit is named: " + crampedResult.message());
		assertEquals(64, count(crowded, Items.OAK_PLANKS), "only the fitting craft was taken");
		assertEquals(4, count(crowded, Items.OAK_LOG), "the unused logs went back to the inventory");
	}

	private static int count(Fixture fixture, Item item) {
		int count = 0;
		for (int slot = 0; slot < fixture.inventory().getContainerSize(); slot++) {
			ItemStack stack = fixture.inventory().getItem(slot);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	private record CraftTrace(ServerTransactionAdapter.TickResult result, int ticks, int maxCarried, int maxFilledCells,
			int maxGridItems) {
	}

	/** Ticks a paced craft like the server does, recording what a spectator of the open menu would see. */
	private static CraftTrace tickToEnd(Fixture fixture, ServerTransactionAdapter.ActiveTransaction transaction) {
		int maxCarried = 0;
		int maxFilled = 0;
		int maxItems = 0;
		for (int tick = 1; tick <= 400; tick++) {
			var result = transaction.tick(System.currentTimeMillis());
			var menu = fixture.player().containerMenu;
			maxCarried = Math.max(maxCarried, menu.getCarried().getCount());
			if (menu instanceof net.minecraft.world.inventory.AbstractCraftingMenu crafting) {
				int filled = (int) crafting.getInputGridSlots().stream().filter(Slot::hasItem).count();
				maxFilled = Math.max(maxFilled, filled);
				maxItems = Math.max(maxItems, crafting.getInputGridSlots().stream().mapToInt(slot -> slot.getItem().getCount()).sum());
			}
			if (result.terminal()) return new CraftTrace(result, tick, maxCarried, maxFilled, maxItems);
		}
		throw new AssertionError("craft transaction did not finish within 400 ticks");
	}

	private static void verifyNativePickaxeTransaction(ComponentBindings components) {
		components.stack(Items.STICK, 1, 0);
		components.stack(Items.WOODEN_PICKAXE, 1, 59);
		Fixture fixture = craftFixture();
		fixture.inventory().setItem(0, components.stack(Items.OAK_PLANKS, 3, 0));
		fixture.inventory().setItem(1, components.stack(Items.STICK, 2, 0));
		var args = json("recipeId", "minecraft:wooden_pickaxe", "count", 1, "timeoutMs", 5000, "x", 0, "y", 64, "z", 0);
		var transaction = service().begin(fixture.player(), request(ActionType.CRAFT_TABLE, args), args);
		assertEquals(ServerTransactionAdapter.TickState.RUNNING, transaction.tick(System.currentTimeMillis()).state(),
				"table craft opens the table before clicking");
		assertEquals(net.minecraft.world.inventory.CraftingMenu.class, fixture.player().containerMenu.getClass(), "native crafting table menu used");
		assertEquals(1, fixture.player().swings, "opening the table swings the arm");
		assertEquals(0, count(fixture, Items.WOODEN_PICKAXE), "no pickaxe on the opening tick");
		CraftTrace trace = tickToEnd(fixture, transaction);
		assertEquals("CRAFT_CONFIRMED", trace.result().reasonCode(), "native pickaxe table recipe commits: " + trace.result().message());
		assertEquals(5, trace.maxFilledCells(), "all five pickaxe cells are visibly filled before the result is taken");
		assertEquals(3, trace.maxCarried(), "the plank stack is carried while it is dealt into the grid");
		assertTrue(trace.ticks() >= 16 && trace.ticks() <= 40, "table craft is watchable but quick, took " + trace.ticks() + " ticks");
		transaction.cleanup();
		assertEquals(0, count(fixture, Items.OAK_PLANKS), "pickaxe consumes three planks");
		assertEquals(0, count(fixture, Items.STICK), "pickaxe consumes two sticks");
		assertEquals(1, count(fixture, Items.WOODEN_PICKAXE), "pickaxe ownership is exact");
		assertTrue(fixture.player().containerMenu == fixture.player().inventoryMenu, "table menu cleanup returns to inventory");
		transaction.cleanup(); transaction.tick(System.currentTimeMillis());
		assertEquals(1, count(fixture, Items.WOODEN_PICKAXE), "table terminal retries cannot duplicate pickaxe");

		// The menu closing halfway must not strand ingredients in the grid or on the cursor.
		Fixture interrupted = craftFixture();
		interrupted.inventory().setItem(0, components.stack(Items.OAK_PLANKS, 3, 0));
		interrupted.inventory().setItem(1, components.stack(Items.STICK, 2, 0));
		var halfway = service().begin(interrupted.player(), request(ActionType.CRAFT_TABLE, args), args);
		var table = (net.minecraft.world.inventory.CraftingMenu) null;
		for (int tick = 0; tick < 200; tick++) {
			assertEquals(ServerTransactionAdapter.TickState.RUNNING, halfway.tick(System.currentTimeMillis()).state(), "craft still running");
			if (interrupted.player().containerMenu instanceof net.minecraft.world.inventory.CraftingMenu crafting
					&& crafting.getInputGridSlots().stream().filter(Slot::hasItem).count() == 2) {
				table = crafting;
				break;
			}
		}
		assertTrue(table != null && !table.getCarried().isEmpty(), "interrupted while planks are in the grid and on the cursor");
		interrupted.player().closeContainer();
		var closed = tickToEnd(interrupted, halfway).result();
		halfway.cleanup();
		assertEquals("MENU_CLOSED", closed.reasonCode(), "closing the menu fails the craft: " + closed.message());
		assertTrue(closed.message().contains("ownership was restored"), "failure reports restored ownership: " + closed.message());
		assertEquals(3, count(interrupted, Items.OAK_PLANKS), "planks return to the inventory");
		assertEquals(2, count(interrupted, Items.STICK), "sticks stay in the inventory");
		assertEquals(0, count(interrupted, Items.WOODEN_PICKAXE), "no partial craft output");
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
			if (player instanceof FaultingServerPlayer fixture && fixture.furnace) {
				player.containerMenu = new net.minecraft.world.inventory.FurnaceMenu(2, player.getInventory());
				return net.minecraft.world.InteractionResult.SUCCESS;
			}
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
		ServerTransactionAdapter.TickResult result = tickUntilTerminal(transaction);

		assertEquals(ServerTransactionAdapter.TickState.FAILED, result.state(), "selection verification fails");
		assertEquals("SELECTION_NOT_CONFIRMED", result.reasonCode(), "selection failure reason is preserved");
		assertEquals("minecraft:iron_pickaxe", itemId(fixture.inventory().getItem(9)),
				"failed selection restores the source tool");
		assertTrue(fixture.inventory().getItem(0).isEmpty(), "failed selection clears the destination hotbar slot");
		assertEquals(2, fixture.inventory().getSelectedSlot(), "failed selection restores the previous selected slot");
	}

	private static ServerTransactionAdapter.TickResult tickUntilTerminal(ServerTransactionAdapter.ActiveTransaction transaction) {
		for (int tick = 0; tick < 200; tick++) {
			ServerTransactionAdapter.TickResult result = transaction.tick(System.currentTimeMillis());
			if (result.terminal()) return result;
		}
		throw new AssertionError("transaction did not finish within 200 ticks");
	}

	/**
	 * Real vanilla FurnaceMenu: the play-test furnace_transaction opened the menu, moved the stack and closed it in
	 * one tick, so spectators saw items appear in the furnace with no screen. Now the screen is shown, the stack
	 * moves while it is open, and the screen stays a moment before closing.
	 */
	private static void verifyPacedFurnaceTransaction(ComponentBindings components) {
		Fixture fixture = craftFixture();
		fixture.player().furnace = true;
		fixture.inventory().setItem(0, components.stack(Items.RAW_IRON, 4, 0));
		var args = json("x", 0, "y", 64, "z", 0, "operation", "insert_input", "inventorySlot", 0,
				"count", 4, "expectedItemId", "minecraft:raw_iron", "timeoutMs", 5000);
		var transaction = service().begin(fixture.player(), request(ActionType.FURNACE_TRANSACTION, args), args);
		assertEquals(ServerTransactionAdapter.TickState.RUNNING, transaction.tick(System.currentTimeMillis()).state(),
				"the furnace opens on the first tick without finishing");
		assertTrue(fixture.player().containerMenu instanceof net.minecraft.world.inventory.FurnaceMenu,
				"the real vanilla furnace menu is the open screen");
		var furnace = fixture.player().containerMenu;
		assertEquals(1, fixture.player().swings, "opening the furnace swings the arm");
		assertTrue(!furnace.getSlot(0).hasItem(), "nothing moves on the opening tick");
		int ticks = 1;
		int openTicksBeforeMove = 1;
		int openTicksAfterMove = 0;
		ServerTransactionAdapter.TickResult result;
		do {
			result = transaction.tick(System.currentTimeMillis());
			ticks++;
			boolean open = fixture.player().containerMenu == furnace;
			if (!result.terminal()) assertTrue(open, "the furnace stays open while the move is shown");
			if (open && !furnace.getSlot(0).hasItem()) openTicksBeforeMove++;
			if (open && furnace.getSlot(0).hasItem() && !result.terminal()) openTicksAfterMove++;
		} while (!result.terminal() && ticks < 200);
		assertEquals("TRANSACTION_CONFIRMED", result.reasonCode(), "the paced furnace move succeeds: " + result.message());
		assertTrue(openTicksBeforeMove >= 2, "the empty furnace screen is shown before the move for " + openTicksBeforeMove + " ticks");
		assertTrue(openTicksAfterMove >= 2, "the filled furnace screen is shown after the move for " + openTicksAfterMove + " ticks");
		assertTrue(ticks >= 5 && ticks <= 12, "the furnace move is watchable but quick, took " + ticks + " ticks");
		assertEquals(4, furnace.getSlot(0).getItem().getCount(), "all four raw iron are in the input slot");
		transaction.cleanup();
		assertTrue(fixture.player().containerMenu == fixture.player().inventoryMenu, "cleanup closes the furnace");
		assertEquals(0, count(fixture, Items.RAW_IRON), "the raw iron left the inventory exactly once");
		assertEquals(result, transaction.tick(System.currentTimeMillis()), "terminal furnace retry is stable");

		Fixture interrupted = craftFixture();
		interrupted.player().furnace = true;
		interrupted.inventory().setItem(0, components.stack(Items.RAW_IRON, 4, 0));
		var closing = service().begin(interrupted.player(), request(ActionType.FURNACE_TRANSACTION, args), args);
		closing.tick(System.currentTimeMillis());
		interrupted.player().closeContainer();
		var closed = tickUntilTerminal(closing);
		closing.cleanup();
		assertEquals("MENU_CLOSED", closed.reasonCode(), "a furnace closed before the move fails without moving");
		assertEquals(4, count(interrupted, Items.RAW_IRON), "the raw iron stays in the inventory");
	}

	/** select_tool moving a tool from the main inventory shows the inventory screen around the move. */
	private static void verifyPacedToolSelection(ComponentBindings components) {
		Fixture fixture = withPlayerHand(fixture());
		fixture.inventory().setItem(9, components.stack(Items.IRON_PICKAXE, 1, 250));
		JsonObject arguments = json("sourceSlot", 9, "hotbarSlot", 0,
				"expectedItemId", "minecraft:iron_pickaxe", "minRemainingDurability", 1);
		var transaction = service().begin(fixture.player(), request(ActionType.SELECT_TOOL, arguments), arguments);
		assertEquals(ServerTransactionAdapter.TickState.RUNNING, transaction.tick(System.currentTimeMillis()).state(),
				"the inventory opens before the tool moves");
		assertTrue(AgentInventoryView.isOpen(fixture.player()), "spectators see the inventory screen");
		assertTrue(fixture.inventory().getItem(0).isEmpty(), "the tool is not in the hand on the opening tick");
		boolean movedWhileOpen = false;
		ServerTransactionAdapter.TickResult result;
		int ticks = 1;
		do {
			result = transaction.tick(System.currentTimeMillis());
			ticks++;
			if (!result.terminal() && !fixture.inventory().getItem(0).isEmpty()) {
				movedWhileOpen |= AgentInventoryView.isOpen(fixture.player());
			}
		} while (!result.terminal() && ticks < 200);
		assertEquals("TOOL_SELECTED", result.reasonCode(), "the paced tool selection succeeds: " + result.message());
		assertTrue(movedWhileOpen, "the pickaxe moves to the hotbar while the inventory screen is shown");
		assertTrue(ticks >= 5, "the move is paced over " + ticks + " ticks");
		transaction.cleanup();
		assertTrue(!AgentInventoryView.isOpen(fixture.player()), "the inventory screen closes after the move");
		assertEquals("minecraft:iron_pickaxe", itemId(fixture.inventory().getItem(0)), "the pickaxe is in hotbar slot 0");
		assertEquals(0, fixture.inventory().getSelectedSlot(), "and it is selected");

		Fixture onHotbar = withPlayerHand(fixture());
		onHotbar.inventory().setItem(2, components.stack(Items.IRON_PICKAXE, 1, 250));
		JsonObject select = json("sourceSlot", 2, "hotbarSlot", 2,
				"expectedItemId", "minecraft:iron_pickaxe", "minRemainingDurability", 1);
		var keyPress = service().begin(onHotbar.player(), request(ActionType.SELECT_TOOL, select), select);
		assertEquals("TOOL_SELECTED", keyPress.tick(System.currentTimeMillis()).reasonCode(),
				"a tool already on the hotbar is one number key, no screen");
		assertTrue(!AgentInventoryView.isOpen(onHotbar.player()), "no inventory screen for a number key");
		keyPress.cleanup();
	}

	/** Real player equipment, whose main hand is the selected hotbar slot (the plain fixture keeps it separate). */
	private static Fixture withPlayerHand(Fixture fixture) {
		try {
			Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			setField((sun.misc.Unsafe) unsafeField.get(null), fixture.player(), LivingEntity.class, "equipment",
					new net.minecraft.world.entity.player.PlayerEquipment(fixture.player()));
			return fixture;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	/** Starts a table pickaxe craft with five planks and ticks until the plank stack is on the cursor. */
	private static ServerTransactionAdapter.ActiveTransaction pickaxeWithPlanksCarried(ComponentBindings components, Fixture fixture) {
		fixture.inventory().setItem(0, components.stack(Items.OAK_PLANKS, 5, 0));
		fixture.inventory().setItem(1, components.stack(Items.STICK, 2, 0));
		var args = json("recipeId", "minecraft:wooden_pickaxe", "count", 1, "timeoutMs", 5000, "x", 0, "y", 64, "z", 0);
		var transaction = service().begin(fixture.player(), request(ActionType.CRAFT_TABLE, args), args);
		for (int tick = 0; tick < 200; tick++) {
			assertEquals(ServerTransactionAdapter.TickState.RUNNING, transaction.tick(System.currentTimeMillis()).state(), "craft running");
			if (fixture.player().containerMenu instanceof net.minecraft.world.inventory.CraftingMenu crafting
					&& crafting.getCarried().is(Items.OAK_PLANKS) && fixture.inventory().getItem(0).isEmpty()) {
				return transaction;
			}
		}
		throw new AssertionError("the plank stack was never carried");
	}

	/**
	 * Play-test ROLLBACK_FAILED ("the leftover ingredient could not be put back"): the agent had just mined, and a
	 * drop it walked over landed in the slot the ingredient stack had been lifted from. Putting the rest back into
	 * that slot swapped stacks, and the ownership check then counted the pickup as a rollback failure.
	 */
	private static void verifyCraftPickupDuringPacing(ComponentBindings components) {
		components.stack(Items.STICK, 1, 0);
		components.stack(Items.WOODEN_PICKAXE, 1, 59);
		Fixture fixture = craftFixture();
		var transaction = pickaxeWithPlanksCarried(components, fixture);
		fixture.inventory().add(components.stack(Items.COBBLESTONE, 3, 0));
		assertEquals("minecraft:cobblestone", itemId(fixture.inventory().getItem(0)),
				"the pickup lands in the slot the planks were lifted from");
		var result = tickToEnd(fixture, transaction).result();
		transaction.cleanup();
		assertEquals("CRAFT_CONFIRMED", result.reasonCode(), "a pickup during the craft no longer fails it: " + result.message());
		assertEquals(1, count(fixture, Items.WOODEN_PICKAXE), "the pickaxe is crafted");
		assertEquals(2, count(fixture, Items.OAK_PLANKS), "the two leftover planks went back to another slot");
		assertEquals(3, count(fixture, Items.COBBLESTONE), "the picked-up cobblestone is kept");
		assertTrue(fixture.player().containerMenu.getCarried().isEmpty(), "nothing is left on the cursor");
	}

	/**
	 * Review finding: when someone else closed the table menu, vanilla dropped the grid and cursor planks at the body
	 * (full inventory), the craft absorbed that loss as an external change, and MENU_CLOSED then claimed the exact
	 * ownership was restored. The loss is now reported as dropped at the body.
	 */
	private static void verifyExternallyClosedCraftMenu(ComponentBindings components) {
		components.stack(Items.DIRT, 1, 0);
		Fixture fixture = craftFixture();
		fixture.player().recordDrops = true;
		var transaction = halfFilledPickaxe(components, fixture);
		for (int slot = 0; slot < 36; slot++) {
			if (fixture.inventory().getItem(slot).isEmpty()) fixture.inventory().setItem(slot, components.stack(Items.DIRT, 64, 0));
		}
		fixture.player().closeContainer();
		var result = tickToEnd(fixture, transaction).result();
		transaction.cleanup();
		assertEquals("MENU_CLOSED", result.reasonCode(), "the external close fails the craft: " + result.message());
		assertTrue(!result.message().contains("was restored"), "it never claims a restore that did not happen: " + result.message());
		assertTrue(result.message().contains("3 dropped at the body"), "the dropped planks are reported: " + result.message());
	}

	/** A failure with a full inventory returns what fits and drops the rest at the body, like vanilla closing a menu. */
	private static void verifyCraftFailureWithFullInventory(ComponentBindings components) {
		components.stack(Items.DIRT, 1, 0);
		Fixture fixture = craftFixture();
		fixture.player().recordDrops = true;
		var transaction = pickaxeWithPlanksCarried(components, fixture);
		fixture.inventory().setItem(1, ItemStack.EMPTY);
		for (int slot = 0; slot < 36; slot++) {
			if (fixture.inventory().getItem(slot).isEmpty()) fixture.inventory().setItem(slot, components.stack(Items.DIRT, 64, 0));
		}
		var result = tickToEnd(fixture, transaction).result();
		transaction.cleanup();
		assertEquals("RECIPE_INPUTS_UNAVAILABLE", result.reasonCode(), "the vanished sticks fail the craft honestly: " + result.message());
		assertTrue(result.message().contains("dropped at the body"), "the drop is reported: " + result.message());
		int dropped = fixture.player().dropped == null ? 0
				: fixture.player().dropped.stream().filter(stack -> stack.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum();
		assertEquals(5, dropped + count(fixture, Items.OAK_PLANKS), "every plank is in the inventory or dropped at the body");
		assertEquals(5, dropped, "with no room, all five planks drop at the body");
		assertTrue(fixture.player().containerMenu == fixture.player().inventoryMenu, "the table menu is closed");
		assertTrue(fixture.player().inventoryMenu.getCarried().isEmpty(), "the cursor is empty");
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
		private int swings;
		private boolean dead;
		private boolean recordDrops;
		/** The fixture block opens a vanilla furnace menu instead of a crafting table. */
		private boolean furnace;
		private java.util.List<ItemStack> dropped; // allocated lazily: fixtures skip constructors

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
			return !dead;
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
		@Override public void swing(net.minecraft.world.InteractionHand hand) { swings++; }
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

		@Override
		public ItemEntity drop(ItemStack stack, boolean randomly, boolean includeThrower) {
			if (recordDrops) {
				if (dropped == null) dropped = new java.util.ArrayList<>();
				dropped.add(stack.copy());
				return null;
			}
			return super.drop(stack, randomly, includeThrower);
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
