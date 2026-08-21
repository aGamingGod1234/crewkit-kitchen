package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.controller.ServerRangedUseController;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import dev.agaminggod.arenaagents.server.runtime.transaction.TransactionPostcondition;
import dev.agaminggod.arenaagents.server.runtime.transaction.TransactionSnapshot;
import dev.agaminggod.arenaagents.server.runtime.transaction.UseConfirmation;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuCapabilityRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

public final class AdvancedInteractionService implements ServerTransactionAdapter {
	private static final double MAX_INTERACTION_DISTANCE_SQUARED = 36.0D;
	private static final long EQUIPMENT_TIMEOUT_MS = 5_000L;
	private final ServerProtectionPolicy protection;
	private final ResourceLeaseManager leases;

	public AdvancedInteractionService(ServerProtectionPolicy protection, ResourceLeaseManager leases) {
		this.protection = Objects.requireNonNull(protection);
		this.leases = Objects.requireNonNull(leases);
	}

	static String canonicalRecipeId(String recipeId) {
		Objects.requireNonNull(recipeId, "recipeId must not be null");
		return switch (recipeId) {
			case "minecraft:sticks" -> "minecraft:stick";
			default -> recipeId;
		};
	}

	static String resolveGenericPlankRecipeId(
			String requestedRecipeId,
			Map<String, ? extends Collection<String>> loadedRecipeIngredients,
			Collection<String> observedIngredientIds
	) {
		Objects.requireNonNull(requestedRecipeId, "requestedRecipeId must not be null");
		Objects.requireNonNull(loadedRecipeIngredients, "loadedRecipeIngredients must not be null");
		Objects.requireNonNull(observedIngredientIds, "observedIngredientIds must not be null");
		String canonical = canonicalRecipeId(requestedRecipeId);
		if (!canonical.equals("minecraft:planks")) return canonical;

		Set<String> observed = new TreeSet<>();
		for (String observedIngredientId : observedIngredientIds) {
			if (observedIngredientId != null && !observedIngredientId.isBlank()) observed.add(observedIngredientId);
		}
		Set<String> matches = new TreeSet<>();
		for (Map.Entry<String, ? extends Collection<String>> entry : loadedRecipeIngredients.entrySet()) {
			String recipeId = entry.getKey();
			if (!isConcreteVanillaPlankRecipeId(recipeId)) continue;
			Collection<String> ingredientIds = entry.getValue();
			if (ingredientIds == null) continue;
			for (String ingredientId : ingredientIds) {
				if (ingredientId != null && observed.contains(ingredientId)) {
					matches.add(recipeId);
					break;
				}
			}
		}
		if (matches.isEmpty()) {
			throw new AgentDomainException(
					"RECIPE_NOT_FOUND",
					"No loaded vanilla plank recipe matches the agent's current inventory ingredients"
			);
		}
		if (matches.size() > 1) {
			throw new AgentDomainException(
					"RECIPE_AMBIGUOUS",
					"Generic plank request matches multiple loaded recipes: " + String.join(", ", matches)
			);
		}
		return matches.iterator().next();
	}

	private static String resolveGenericPlankRecipeId(ServerLevel level, Inventory inventory) {
		Objects.requireNonNull(level, "level must not be null");
		Objects.requireNonNull(inventory, "inventory must not be null");
		Map<String, Set<String>> loadedRecipeIngredients = new HashMap<>();
		Set<String> observedIngredientIds = new TreeSet<>();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty()) observedIngredientIds.add(itemId(stack));
		}

		RecipeManager recipeManager = level.recipeAccess();
		for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
			Identifier recipeId = holder.id().identifier();
			if (!isConcreteVanillaPlankRecipeId(recipeId.toString())) continue;
			if (!(holder.value() instanceof CraftingRecipe craftingRecipe)) continue;
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				ItemStack ingredient = inventory.getItem(slot);
				if (ingredient.isEmpty()) continue;
				try {
					ItemStack singleIngredient = ingredient.copyWithCount(1);
					CraftingInput input = CraftingInput.of(1, 1, List.of(singleIngredient));
					if (!craftingRecipe.matches(input, level)) continue;
					ItemStack assembled = craftingRecipe.assemble(input);
					if (assembled.isEmpty() || !itemId(assembled).equals(recipeId.toString())) continue;
					loadedRecipeIngredients
							.computeIfAbsent(recipeId.toString(), ignored -> new TreeSet<>())
							.add(itemId(ingredient));
				} catch (RuntimeException invalidRecipe) {
					// An invalid loaded recipe cannot be authoritative evidence for a generic request.
				}
			}
		}
		return resolveGenericPlankRecipeId("minecraft:planks", loadedRecipeIngredients, observedIngredientIds);
	}

	private static boolean isConcreteVanillaPlankRecipeId(String recipeId) {
		if (recipeId == null) return false;
		try {
			Identifier identifier = Identifier.parse(recipeId);
			return identifier.getNamespace().equals("minecraft")
					&& identifier.getPath().endsWith("_planks")
					&& !identifier.getPath().equals("planks");
		} catch (RuntimeException invalidRecipeId) {
			return false;
		}
	}

	static boolean ensureRecipeUnlocked(ServerRecipeBook recipeBook, ResourceKey<Recipe<?>> recipeKey) {
		Objects.requireNonNull(recipeBook, "recipeBook must not be null");
		Objects.requireNonNull(recipeKey, "recipeKey must not be null");
		if (recipeBook.contains(recipeKey)) return false;
		recipeBook.add(recipeKey);
		return true;
	}

	static boolean craftOutputSatisfiesRequest(int outputCount, int requestedCount) {
		return requestedCount > 0 && outputCount >= requestedCount;
	}

	@Override
	public ActiveTransaction begin(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
		Objects.requireNonNull(player, "player must not be null");
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(arguments, "arguments must not be null");
		return switch (request.type()) {
			case TRANSFER_CONTAINER -> new TransferTransaction(player, request, arguments);
			case CRAFT_INVENTORY -> new CraftTransaction(player, request, arguments, false);
			case CRAFT_TABLE -> new CraftTransaction(player, request, arguments, true);
			case FURNACE_TRANSACTION -> new FurnaceTransaction(player, request, arguments);
			case EQUIP_ITEM -> new EquipmentTransaction(player, request, arguments);
			case SELECT_TOOL -> new ToolSelectionTransaction(player, request, arguments);
			case BLOCK_WITH_SHIELD -> new ShieldTransaction(player, request, arguments);
			case USE_RANGED -> new ServerRangedUseController(player, arguments, protection);
			case MENU_TRANSFER -> new MenuTransferTransaction(player, request, arguments);
			case MENU_BUTTON -> new MenuButtonTransaction(player, request, arguments);
			case ANVIL_RENAME -> new AnvilRenameTransaction(player, request, arguments);
			default -> throw new AgentDomainException("UNSUPPORTED_TRANSACTION", "Action is not a transaction adapter action");
		};
	}

	public Result setDoor(AgentId id, ServerPlayer agent, BlockPos position, boolean open) {
		ServerLevel level = (ServerLevel) agent.level();
		String key = "block:" + level.dimension().identifier() + ":" + position.asLong();
		if (!leases.acquire(key, id, System.currentTimeMillis(), 5_000L)) return Result.failed("RESOURCE_BUSY", "Door is leased");
		try {
			if (!protection.mayModifyBlock(agent, level, position)) return Result.failed("PROTECTION_DENIED", "Door mutation denied");
			BlockState state = level.getBlockState(position);
			if (!state.hasProperty(BlockStateProperties.OPEN)) return Result.failed("NOT_A_DOOR", "Block has no open property");
			if (!level.setBlockAndUpdate(position, state.setValue(BlockStateProperties.OPEN, open))) return Result.failed("DOOR_REJECTED", "World rejected door state");
			return Result.succeeded(open ? "Door opened" : "Door closed");
		} finally { leases.release(key, id); }
	}

	public Result validatePickUp(ServerPlayer agent, ItemEntity item) {
		if (!item.isAlive() || item.getItem().isEmpty()) return Result.failed("ITEM_UNAVAILABLE", "Item entity is no longer available");
		if (!protection.mayTakeEntity(agent, item)) return Result.failed("PROTECTION_DENIED", "Item pickup denied");
		return Result.succeeded("Item may be approached for normal player collision pickup");
	}

	public Result drop(ServerPlayer agent, int slot, int count) {
		if (!protection.mayDropItem(agent)) return Result.failed("PROTECTION_DENIED", "Item drop denied");
		if (slot < 0 || slot >= agent.getInventory().getContainerSize() || count <= 0) return Result.failed("INVALID_SLOT", "Invalid inventory slot/count");
		ItemStack removed = agent.getInventory().removeItem(slot, count);
		if (removed.isEmpty()) return Result.failed("EMPTY_SLOT", "Inventory slot is empty");
		if (agent.drop(removed, false) == null) {
			ItemStack slotStack = agent.getInventory().getItem(slot);
			if (slotStack.isEmpty()) agent.getInventory().setItem(slot, removed);
			else slotStack.grow(removed.getCount());
			agent.getInventory().setChanged();
			return Result.failed("ITEM_DROP_REJECTED", "World rejected item drop; inventory was restored");
		}
		return Result.succeeded("Dropped item stack");
	}

	public Result respawn() { return unavailable("RESPAWN_REQUIRES_SERVER_TICK_DEATH_RECONCILER"); }
	public Result chunkTicket() { return unavailable("CHUNK_TICKET_REQUIRES_VERSION_VALIDATED_TICKET_TYPE"); }

	private abstract class Transaction implements ActiveTransaction {
		final ServerPlayer player;
		final ServerActionRequest request;
		final JsonObject arguments;
		final long createdAt = System.currentTimeMillis();
		final long timeoutMs;
		final TerminalGate terminal = new TerminalGate();
		final List<ItemStack> retainedEscrow = new ArrayList<>();
		String leaseKey;
		boolean menuOpened;
		boolean executed;

		Transaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments, long timeoutMs) {
			this.player = player;
			this.request = request;
			this.arguments = arguments.deepCopy();
			this.timeoutMs = Math.max(1L, timeoutMs);
		}

		@Override
		public TickResult tick(long nowEpochMs) {
			TickResult existing = terminal.terminalResult();
			if (existing != null) return existing;
			if (!player.isAlive()) return finish(TickResult.failed("AGENT_DEAD", "Agent player died"));
			if (nowEpochMs - createdAt >= timeoutMs) {
				return finish(TickResult.timedOut("TRANSACTION_TIMED_OUT", "Transaction timed out"));
			}
			try {
				TickResult result = execute(nowEpochMs);
				return result.terminal() ? finish(result) : result;
			} catch (AgentDomainException exception) {
				return finish(TickResult.failed(exception.code(), safeMessage(exception)));
			} catch (RuntimeException exception) {
				return finish(TickResult.failed("TRANSACTION_EXCEPTION", safeMessage(exception)));
			}
		}

		abstract TickResult execute(long nowEpochMs);

		@Override
		public void cancel(String reason) {
			terminal.finish(TickResult.cancelled(reason == null ? "Transaction cancelled" : reason));
			cleanup();
		}

		@Override
		public void cleanup() {
			terminal.cleanupOnce(() -> ServerTransactionAdapter.runBestEffort(
					this::beforeCleanup,
					() -> { if (player.isUsingItem()) player.stopUsingItem(); },
					this::flushRetainedEscrow,
					() -> returnCarried(player, player.containerMenu),
					() -> { if (menuOpened) player.closeContainer(); },
					() -> returnCarried(player, player.inventoryMenu),
					() -> { if (leaseKey != null) leases.release(leaseKey, request.agentId()); }
			));
		}

		void beforeCleanup() {
		}

		void retainEscrow(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return;
			for (ItemStack retained : retainedEscrow) {
				if (retained == stack) return;
			}
			retainedEscrow.add(stack);
		}

		void flushRetainedEscrow() {
			RuntimeException firstFailure = null;
			for (ItemStack stack : new ArrayList<>(retainedEscrow)) {
				RuntimeException stackFailure = null;
				int originalCount = stack.getCount();
				int inventoryBefore = inventoryOwnershipCount(player.getInventory(), stack);
				try {
					player.getInventory().placeItemBackInInventory(stack);
				} catch (RuntimeException exception) {
					int inventoryAfter = inventoryOwnershipCount(player.getInventory(), stack);
					int credited = Math.max(0, inventoryAfter - inventoryBefore);
					int reflectedByEscrow = Math.max(0, originalCount - stack.getCount());
					int unreflectedCredit = Math.min(stack.getCount(), Math.max(0, credited - reflectedByEscrow));
					if (unreflectedCredit > 0) stack.shrink(unreflectedCredit);
					stackFailure = exception;
				}
				if (!stack.isEmpty()) {
					try {
						ItemEntity dropped = player.drop(stack, false);
						if (dropped == null) {
							throw new IllegalStateException("Escrow inventory and drop recovery were rejected");
						}
						retainedEscrow.remove(stack);
					} catch (RuntimeException dropFailure) {
						if (stackFailure == null) stackFailure = dropFailure;
						else stackFailure.addSuppressed(dropFailure);
					}
				} else {
					retainedEscrow.remove(stack);
				}
				if (stackFailure != null) {
					if (firstFailure == null) firstFailure = stackFailure;
					else firstFailure.addSuppressed(stackFailure);
				}
			}
			if (firstFailure != null) throw firstFailure;
		}

		TickResult finish(TickResult result) {
			return terminal.finish(result);
		}

		AbstractContainerMenu openBlockMenu(BlockPos position) {
			ServerLevel level = player.level();
			requireBlockPreflight(player, position);
			if (!protection.mayUseContainer(player, level, position)) {
				throw new AgentDomainException("PROTECTION_DENIED", "Container use was denied");
			}
			leaseKey = containerLeaseKey(level, position);
			if (!leases.acquire(leaseKey, request.agentId(), System.currentTimeMillis(), timeoutMs)) {
				leaseKey = null;
				throw new AgentDomainException("RESOURCE_BUSY", "Target menu is leased by another agent");
			}
			MenuProvider provider = level.getBlockState(position).getMenuProvider(level, position);
			if (provider == null) throw new AgentDomainException("UNSUPPORTED_MENU", "Target block has no server menu provider");
			if (player.openMenu(provider).isEmpty()) {
				throw new AgentDomainException("MENU_OPEN_REJECTED", "Server rejected opening the target menu");
			}
			menuOpened = true;
			return player.containerMenu;
		}
	}

	private final class TransferTransaction extends Transaction {
		TransferTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Transfer executed more than once");
			executed = true;
			String sourceKind = text(arguments, "sourceKind");
			String destinationKind = text(arguments, "destinationKind");
			if (sourceKind.equals(destinationKind)) {
				return TickResult.failed("INVALID_TRANSFER", "Exactly one transfer endpoint must be the container");
			}
			AbstractContainerMenu opened = openBlockMenu(blockPosition(arguments));
			if (opened.getClass() != ChestMenu.class) return unsupportedMenu(opened);
			ChestMenu menu = (ChestMenu) opened;
			int source = menuSlot(menu, sourceKind, integer(arguments, "sourceSlot"));
			int destination = menuSlot(menu, destinationKind, integer(arguments, "destinationSlot"));
			return transfer(this, player, menu, source, destination,
					text(arguments, "expectedItemId"), integer(arguments, "count"), true);
		}
	}

	private final class MenuTransferTransaction extends Transaction {
		MenuTransferTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Menu transfer executed more than once");
			executed = true;
			AbstractContainerMenu menu = requireCurrentSupportedMenu(player, text(arguments, "menuId"));
			return transfer(
					this,
					player,
					menu,
					integer(arguments, "sourceSlot"),
					integer(arguments, "destinationSlot"),
					text(arguments, "expectedItemId"),
					integer(arguments, "count"),
					true
			);
		}
	}

	private final class MenuButtonTransaction extends Transaction {
		MenuButtonTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Menu button executed more than once");
			executed = true;
			AbstractContainerMenu menu = requireCurrentSupportedMenu(player, text(arguments, "menuId"));
			int buttonId = integer(arguments, "buttonId");
			boolean accepted;
			if (menu instanceof MerchantMenu merchant) {
				merchant.setSelectionHint(buttonId);
				merchant.tryMoveItems(buttonId);
				accepted = true;
			} else {
				accepted = menu.clickMenuButton(player, buttonId);
			}
			if (!accepted) return TickResult.failed("MENU_BUTTON_REJECTED", "Vanilla menu rejected the requested button");
			menu.broadcastChanges();
			return TickResult.succeeded("MENU_BUTTON_ACCEPTED", "Vanilla menu accepted the requested option");
		}
	}

	private final class AnvilRenameTransaction extends Transaction {
		AnvilRenameTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Anvil rename executed more than once");
			executed = true;
			AbstractContainerMenu current = requireCurrentSupportedMenu(player, text(arguments, "menuId"));
			if (!(current instanceof AnvilMenu anvil)) return unsupportedMenu(current);
			if (!anvil.setItemName(text(arguments, "name"))) {
				return TickResult.failed("ANVIL_NAME_REJECTED", "Vanilla anvil rejected the requested name");
			}
			anvil.broadcastChanges();
			return TickResult.succeeded("ANVIL_NAME_SET", "Vanilla anvil accepted the requested name");
		}
	}

	private final class FurnaceTransaction extends Transaction {
		FurnaceTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Furnace transaction executed more than once");
			executed = true;
			AbstractContainerMenu opened = openBlockMenu(blockPosition(arguments));
			if (!(opened instanceof AbstractFurnaceMenu) || !vanillaFurnaceMenu(opened)) return unsupportedMenu(opened);
			String operation = text(arguments, "operation");
			int playerSlot = furnacePlayerSlot(integer(arguments, "inventorySlot"));
			int source = operation.equals("take_output") ? 2 : playerSlot;
			int destination = switch (operation) {
				case "insert_input" -> 0;
				case "insert_fuel" -> 1;
				case "take_output" -> playerSlot;
				default -> throw new AgentDomainException("INVALID_OPERATION", "Unsupported furnace operation");
			};
			return transfer(this, player, opened, source, destination, text(arguments, "expectedItemId"),
					integer(arguments, "count"), !operation.equals("take_output"));
		}
	}

	private final class EquipmentTransaction extends Transaction {
		EquipmentTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, EQUIPMENT_TIMEOUT_MS);
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Equipment transaction executed more than once");
			executed = true;
			useInventoryMenu(player);
			int source = inventoryMenuSlot(integer(arguments, "sourceSlot"));
			int destination = switch (text(arguments, "targetSlot")) {
				case "head" -> 5;
				case "chest" -> 6;
				case "legs" -> 7;
				case "feet" -> 8;
				case "offhand" -> 45;
				default -> throw new AgentDomainException("INVALID_SLOT", "Unsupported equipment slot");
			};
			return transfer(this, player, player.inventoryMenu, source, destination,
					text(arguments, "expectedItemId"), 1, true);
		}
	}

	private final class ToolSelectionTransaction extends Transaction {
		ToolSelectionTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, EQUIPMENT_TIMEOUT_MS);
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Tool selection executed more than once");
			executed = true;
			useInventoryMenu(player);
			int inventorySource = integer(arguments, "sourceSlot");
			int hotbarSlot = integer(arguments, "hotbarSlot");
			int source = inventoryMenuSlot(inventorySource);
			int destination = inventoryMenuSlot(hotbarSlot);
			Slot sourceSlot = player.inventoryMenu.getSlot(source);
			requireExpectedStack(sourceSlot.getItem(), text(arguments, "expectedItemId"), 1);
			int remaining = sourceSlot.getItem().getMaxDamage() - sourceSlot.getItem().getDamageValue();
			if (remaining < integer(arguments, "minRemainingDurability")) {
				return TickResult.failed("INSUFFICIENT_DURABILITY", "Selected tool does not meet minimum remaining durability");
			}
			if (source != destination) {
				if (player.inventoryMenu.getSlot(destination).hasItem()) {
					return TickResult.failed("HOTBAR_SLOT_OCCUPIED", "Safe tool selection requires an empty destination hotbar slot");
				}
				TickResult moved = transfer(this, player, player.inventoryMenu, source, destination,
						text(arguments, "expectedItemId"), 1, true);
				if (moved.state() != TickState.SUCCEEDED) return moved;
			}
			player.getInventory().setSelectedSlot(hotbarSlot);
			ItemStack selected = player.getMainHandItem();
			if (!itemId(selected).equals(text(arguments, "expectedItemId"))) {
				return TickResult.failed("SELECTION_NOT_CONFIRMED", "Expected tool was not observed in the selected hand");
			}
			return TickResult.succeeded("TOOL_SELECTED", "Tool moved with vanilla slots and selected");
		}
	}

	private final class CraftTransaction extends Transaction {
		private final boolean table;
		private boolean placementAttempted;

		CraftTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments, boolean table) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
			this.table = table;
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Craft transaction executed more than once");
			executed = true;
			AbstractContainerMenu menu;
			if (table) {
				menu = openBlockMenu(blockPosition(arguments));
				if (menu.getClass() != CraftingMenu.class) return unsupportedMenu(menu);
			} else {
				useInventoryMenu(player);
				menu = player.inventoryMenu;
				if (menu.getClass() != InventoryMenu.class) return unsupportedMenu(menu);
			}
			String requestedRecipeId = canonicalRecipeId(text(arguments, "recipeId"));
			if (requestedRecipeId.equals("minecraft:planks")) {
				requestedRecipeId = resolveGenericPlankRecipeId(player.level(), player.getInventory());
			}
			String lookupRecipeId = requestedRecipeId;
			ResourceKey<Recipe<?>> recipeKey = ResourceKey.create(
					Registries.RECIPE, Identifier.parse(lookupRecipeId));
			RecipeHolder<?> holder = player.level().recipeAccess().byKey(recipeKey).orElseThrow(() ->
					new AgentDomainException("RECIPE_NOT_FOUND", "Recipe " + lookupRecipeId + " is not registered"));
			if (!(holder.value() instanceof CraftingRecipe craftingRecipe)) {
				return TickResult.failed("RECIPE_TYPE_MISMATCH", "Requested recipe is not a crafting recipe");
			}
			ensureRecipeUnlocked(player.getRecipeBook(), holder.id());
			RecipeBookMenu recipeMenu = (RecipeBookMenu) menu;
			AbstractCraftingMenu craftingMenu = (AbstractCraftingMenu) menu;
			List<Slot> gridSlots = craftingMenu.getInputGridSlots();
			if (!menu.getCarried().isEmpty()) {
				return TickResult.failed("TRANSACTION_CONFLICT", "Safe crafting requires an empty carried stack");
			}
			TransactionSnapshot.CraftPlacementGuard placementGuard =
					new TransactionSnapshot.CraftPlacementGuard(ownedStacks(player.getInventory(), gridSlots));
			placementAttempted = true;
			placementGuard.markPlacementAttempted();
			try {
				TransactionSnapshot.CraftPlacementMode placementMode =
						TransactionSnapshot.CraftPlacementMode.oneCraft(player.isCreative());
				RecipeBookMenu.PostPlaceAction placement = recipeMenu.handlePlacement(
						placementMode.useMaxItems(),
						placementMode.allowDroppingItemsToClear(),
						holder,
						player.level(),
						player.getInventory()
				);
				TransactionPostcondition.Verdict placementVerdict = placementGuard.verifyPlacement(
						ownedStacks(player.getInventory(), gridSlots));
				if (placementVerdict instanceof TransactionPostcondition.Verdict.Failed failed) {
					return failureAfterPlacement(failed.reasonCode(), failed.message(), placementGuard, gridSlots);
				}
				if (placement != RecipeBookMenu.PostPlaceAction.NOTHING) {
					return failureAfterPlacement(
							"RECIPE_PLACEMENT_REJECTED",
							"Vanilla recipe placement did not place one craft",
							placementGuard,
							gridSlots
					);
				}
				List<ItemStack> gridStacks = gridSlots.stream().map(slot -> slot.getItem().copy()).toList();
				CraftingInput.Positioned positionedInput = CraftingInput.ofPositioned(
						craftingMenu.getGridWidth(), craftingMenu.getGridHeight(), gridStacks);
				CraftingInput input = positionedInput.input();
				RecipeHolder<CraftingRecipe> exact = player.level().recipeAccess().getRecipeFor(
						RecipeType.CRAFTING, input, player.level(), recipeKey).orElse(null);
				if (!craftingRecipe.matches(input, player.level()) || exact == null || !exact.id().equals(recipeKey)) {
					return failureAfterPlacement(
							"RECIPE_IDENTITY_MISMATCH",
							"Placed ingredients do not exactly match the requested recipe",
							placementGuard,
							gridSlots
					);
				}
				for (ItemStack ingredient : gridStacks) {
					if (!ingredient.isEmpty() && ingredient.getCount() != 1) {
						return failureAfterPlacement(
								"CRAFT_COUNT_UNSUPPORTED",
								"Safe crafting requires exactly one item in every occupied input slot",
								placementGuard,
								gridSlots
						);
					}
				}
				List<ItemStack> remainders;
				try {
					remainders = TransactionSnapshot.expandCraftingRemainders(
							craftingRecipe.getRemainingItems(input),
							gridSlots.size(),
							craftingMenu.getGridWidth(),
							input.width(),
							positionedInput.left(),
							positionedInput.top(),
							ItemStack.EMPTY
					);
				} catch (IllegalArgumentException invalidRemainders) {
					return failureAfterPlacement(
							"CRAFT_REMAINDER_UNSAFE",
							"Recipe remainder layout could not be aligned with the crafting grid",
							placementGuard,
							gridSlots
					);
				}
				Slot resultSlot = menu.getSlot(0);
				ItemStack output = resultSlot.getItem().copy();
				int requestedCount = integer(arguments, "count");
				if (output.isEmpty() || !craftOutputSatisfiesRequest(output.getCount(), requestedCount)) {
					return failureAfterPlacement(
							"CRAFT_COUNT_UNSUPPORTED",
							"One vanilla craft must produce at least the requested count",
							placementGuard,
							gridSlots
					);
				}
				int playerStart = table ? 10 : 9;
				int playerEnd = table ? 45 : 44;
				if (menuCapacity(menu, playerStart, playerEnd, output) < output.getCount()) {
					return failureAfterPlacement(
							"DESTINATION_FULL",
							"Player inventory cannot accept the complete crafting result",
							placementGuard,
							gridSlots
					);
				}
				if (!resultSlot.mayPickup(player)) {
					return failureAfterPlacement(
							"CRAFT_RESULT_LOCKED",
							"Vanilla menu denied taking the crafting result",
							placementGuard,
							gridSlots
					);
				}
				List<TransactionSnapshot.OwnedStack> beforeOwned = ownedStacks(player.getInventory(), gridSlots);
				List<TransactionSnapshot.OwnedStack> consumed = ownedStacks(gridStacks);
				List<TransactionSnapshot.OwnedStack> remainderOwned = ownedStacks(remainders);
				TransactionSnapshot.CraftingAccounting accounting;
				try {
					accounting = TransactionSnapshot.CraftingAccounting.plan(
							beforeOwned, consumed, remainderOwned, ownedStack(output));
				} catch (IllegalArgumentException invalidAccounting) {
					return failureAfterPlacement(
							"CRAFT_ACCOUNTING_UNSAFE",
							safeMessage(invalidAccounting),
							placementGuard,
							gridSlots
					);
				}
				try {
					ItemStack moved = menu.quickMoveStack(player, 0);
					menu.broadcastChanges();
					TransactionPostcondition.Verdict craftVerdict = accounting.verify(
							ownedStacks(player.getInventory(), gridSlots));
					if (!moved.isEmpty() && moved.getCount() == output.getCount()
							&& craftVerdict instanceof TransactionPostcondition.Verdict.Succeeded) {
						return TickResult.succeeded("CRAFT_CONFIRMED",
								"One vanilla recipe transaction completed with remainder handling");
					}
					return TickResult.failed("ROLLBACK_FAILED",
							"Irreversible craft completed without exact ingredient, remainder, and output accounting");
				} catch (RuntimeException mutationFailure) {
					return TickResult.failed("ROLLBACK_FAILED",
							"Irreversible craft mutation raised an exception: " + safeMessage(mutationFailure));
				}
			} catch (RuntimeException placementFailure) {
				return failureAfterPlacement(
						"CRAFT_PLACEMENT_EXCEPTION",
						"Recipe placement or precommit validation raised an exception: " + safeMessage(placementFailure),
						placementGuard,
						gridSlots
				);
			}
		}

		private TickResult failureAfterPlacement(
				String reasonCode,
				String message,
				TransactionSnapshot.CraftPlacementGuard placementGuard,
				List<Slot> gridSlots
		) {
			RuntimeException cleanupFailure = null;
			try {
				cleanup();
			} catch (RuntimeException exception) {
				cleanupFailure = exception;
			}
			boolean restored;
			try {
				restored = placementGuard.mayReportCleanFailure(ownedStacks(player.getInventory(), gridSlots));
			} catch (RuntimeException observationFailure) {
				restored = false;
				if (cleanupFailure != null) cleanupFailure.addSuppressed(observationFailure);
				else cleanupFailure = observationFailure;
			}
			if (restored) {
				return TickResult.failed(reasonCode, message + "; exact pre-placement ownership was restored");
			}
			String suffix = cleanupFailure == null ? "" : ": " + safeMessage(cleanupFailure);
			return TickResult.failed("ROLLBACK_FAILED",
					message + "; exact pre-placement ownership restoration was not proved" + suffix);
		}

		@Override
		void beforeCleanup() {
			if (!table && placementAttempted) player.inventoryMenu.removed(player);
		}
	}

	private final class ShieldTransaction extends Transaction {
		private final long durationMs;
		private final ServerTransactionAdapter.ConfirmedUseTimer useTimer =
				new ServerTransactionAdapter.ConfirmedUseTimer();
		private InteractionHand hand;
		private ItemStack shield;
		private UseConfirmation confirmation = UseConfirmation.initial();

		ShieldTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("durationMs").getAsLong() + 1_000L);
			this.durationMs = arguments.get("durationMs").getAsLong();
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (!executed) {
				executed = true;
				hand = shieldHand(player);
				shield = player.getItemInHand(hand);
				InteractionResult result = player.gameMode.useItem(player, player.level(), shield, hand);
				if (!result.consumesAction() || !player.isUsingItem() || player.getUsedItemHand() != hand) {
					return TickResult.failed("SHIELD_USE_NOT_STARTED", "Vanilla shield use did not enter the observed using state");
				}
				confirmation = confirmation.observeUsing(true);
				useTimer.observeStarted(true, nowEpochMs);
			}
			if (!useTimer.durationElapsed(nowEpochMs, durationMs)) {
				if (!player.isUsingItem() || player.getUsedItemHand() != hand) {
					return TickResult.failed("SHIELD_USE_INTERRUPTED", "Shield use ended before the requested duration");
				}
				return TickResult.running();
			}
			player.stopUsingItem();
			confirmation = confirmation.observeUsing(player.isUsingItem());
			if (!confirmation.confirmed()) {
				return TickResult.failed("SHIELD_RELEASE_NOT_OBSERVED", "Shield release was not observed after use started");
			}
			return TickResult.succeeded("SHIELD_BLOCK_CONFIRMED", "Shield use start and release were both observed");
		}
	}

	private TickResult transfer(
			Transaction owner,
			ServerPlayer player,
			AbstractContainerMenu menu,
			int sourceIndex,
			int destinationIndex,
			String expectedItemId,
			int count,
			boolean rollbackSupported
	) {
		if (!menu.isValidSlotIndex(sourceIndex) || !menu.isValidSlotIndex(destinationIndex)
				|| sourceIndex == destinationIndex) {
			return TickResult.failed("INVALID_SLOT", "Source or destination menu slot is invalid");
		}
		Slot source = menu.getSlot(sourceIndex);
		Slot destination = menu.getSlot(destinationIndex);
		requireExpectedStack(source.getItem(), expectedItemId, count);
		if (!source.mayPickup(player)) return TickResult.failed("SOURCE_LOCKED", "Vanilla menu denied taking the source stack");
		if (capacity(destination, source.getItem()) < count) {
			return TickResult.failed("DESTINATION_REJECTED", "Destination cannot accept the exact requested stack");
		}
		if (!menu.getCarried().isEmpty()) {
			return TickResult.failed("TRANSACTION_CONFLICT", "Safe transfer requires an empty carried stack");
		}
		TransactionSnapshot before = snapshot(menu);
		TransactionSnapshot.TransferMutationEscrow<ItemStack> mutationEscrow =
				new TransactionSnapshot.TransferMutationEscrow<>(stack -> stack != null && !stack.isEmpty());
		try {
			ItemStack taken = mutationEscrow.attempt(() -> source.safeTake(count, count, player));
			if (taken.getCount() != count || !itemId(taken).equals(expectedItemId)) {
				if (recoverTransfer(owner, player, menu, before, sourceIndex, destinationIndex,
						expectedItemId, mutationEscrow, rollbackSupported)) {
					return TickResult.failed("SOURCE_DEBIT_FAILED", "Vanilla menu did not debit the exact requested stack");
				}
				return TickResult.failed("ROLLBACK_FAILED", "Unexpected source debit could not be restored exactly");
			}
			ItemStack debited = taken.copy();
			ItemStack remainder = mutationEscrow.attempt(() -> destination.safeInsert(taken, count));
			int escrowCount = reachableEscrowCount(mutationEscrow.reachable(), expectedItemId);
			int acceptedCount = count - escrowCount;
			if (acceptedCount < 0 || acceptedCount > count) {
				recoverTransfer(owner, player, menu, before, sourceIndex, destinationIndex,
						expectedItemId, mutationEscrow, rollbackSupported);
				return TickResult.failed("ROLLBACK_FAILED", "Destination returned invalid transfer escrow accounting");
			}
			TransactionSnapshot.TransferAccounting transferAccounting =
					new TransactionSnapshot.TransferAccounting(count, debited.getCount(), acceptedCount, escrowCount);
			if (transferAccounting.verifyOwnership() instanceof TransactionPostcondition.Verdict.Failed) {
				recoverTransfer(owner, player, menu, before, sourceIndex, destinationIndex,
						expectedItemId, mutationEscrow, rollbackSupported);
				return TickResult.failed("ROLLBACK_FAILED", "Transfer ownership accounting failed");
			}
			if (escrowCount > 0) {
				if (!rollbackSupported || !recoverTransfer(
						owner, player, menu, before, sourceIndex, destinationIndex,
						expectedItemId, mutationEscrow, true)) {
					if (!rollbackSupported) preserveReachableEscrow(owner, player, mutationEscrow);
					return TickResult.failed("ROLLBACK_FAILED", "Destination rejected the stack and exact source restoration failed");
				}
				return TickResult.failed("DESTINATION_REJECTED", "Destination rejected the stack; source was restored");
			}
			menu.broadcastChanges();
			TransactionPostcondition.Verdict verdict = before.verifyExactTransfer(
					snapshot(menu), sourceIndex, destinationIndex, expectedItemId, count);
			if (verdict instanceof TransactionPostcondition.Verdict.Failed failed) {
				if (rollbackSupported && recoverTransfer(
						owner, player, menu, before, sourceIndex, destinationIndex,
						expectedItemId, mutationEscrow, true)) {
					return TickResult.failed(failed.reasonCode(), failed.message() + "; exact source state was restored");
				}
				return TickResult.failed("ROLLBACK_FAILED", failed.message() + "; exact restoration failed");
			}
			return TickResult.succeeded("TRANSACTION_CONFIRMED", "Exact vanilla menu debit and credit confirmed");
		} catch (RuntimeException mutationFailure) {
			boolean restored = recoverTransfer(
					owner, player, menu, before, sourceIndex, destinationIndex,
					expectedItemId, mutationEscrow, rollbackSupported);
			return TickResult.failed("ROLLBACK_FAILED",
					"Transfer mutation raised an exception after debit began; recovery "
							+ (restored ? "restored the exact snapshot" : "remains incomplete")
							+ ": " + safeMessage(mutationFailure));
		}
	}

	private boolean recoverTransfer(
			Transaction owner,
			ServerPlayer player,
			AbstractContainerMenu menu,
			TransactionSnapshot before,
			int sourceIndex,
			int destinationIndex,
			String expectedItemId,
			TransactionSnapshot.TransferMutationEscrow<ItemStack> mutationEscrow,
			boolean recoverDestination
	) {
		Slot source = menu.getSlot(sourceIndex);
		Slot destination = menu.getSlot(destinationIndex);
		TransactionSnapshot.TransferRecoveryState recoveryState = null;
		try {
			recoveryState = TransactionSnapshot.TransferRecoveryState.observe(
					before,
					snapshot(menu),
					sourceIndex,
					destinationIndex,
					reachableEscrowCount(mutationEscrow.reachable(), expectedItemId)
			);
		} catch (RuntimeException ignored) {
			// Preserve reachable escrow below even when current menu state cannot be classified.
		}
		if (recoverDestination && recoveryState != null && recoveryState.destinationRecoveryCount() > 0) {
			int recoveryCount = recoveryState.destinationRecoveryCount();
			try {
				mutationEscrow.attempt(() -> destination.safeTake(
						recoveryCount, recoveryCount, player));
			} catch (RuntimeException ignored) {
				// A thrown recovery mutation is re-observed below; already reachable escrow is retained.
			}
		}
		for (ItemStack stack : mutationEscrow.reachable()) {
			if (stack.isEmpty()) continue;
			int originalCount = stack.getCount();
			int sourceBeforeInsert = source.getItem().getCount();
			ItemStack leftover = stack;
			try {
				leftover = source.safeInsert(stack, stack.getCount());
			} catch (RuntimeException ignored) {
				// The same mutable stack reference now represents any portion still reachable.
			}
			int sourceAfterInsert = source.getItem().getCount();
			int sourceCredit = Math.max(0, sourceAfterInsert - sourceBeforeInsert);
			int reflectedByEscrow = Math.max(0, originalCount - stack.getCount());
			int unreflectedCredit = Math.min(stack.getCount(), Math.max(0, sourceCredit - reflectedByEscrow));
			if (unreflectedCredit > 0) stack.shrink(unreflectedCredit);
			preserveReachable(owner, player, stack);
			if (leftover != stack) preserveReachable(owner, player, leftover);
		}
		mutationEscrow.clear();
		try {
			menu.broadcastChanges();
		} catch (RuntimeException ignored) { }
		try {
			return before.equals(snapshot(menu));
		} catch (RuntimeException observationFailure) {
			return false;
		}
	}

	private static TransactionSnapshot snapshot(AbstractContainerMenu menu) {
		List<TransactionSnapshot.SlotState> states = new ArrayList<>(menu.slots.size());
		for (int index = 0; index < menu.slots.size(); index++) {
			ItemStack stack = menu.getSlot(index).getItem();
			states.add(new TransactionSnapshot.SlotState(
					index,
					stack.isEmpty() ? "" : itemId(stack),
					stack.getCount(),
					stack.isEmpty() ? "" : stack.getComponentsPatch().toString()
			));
		}
		return new TransactionSnapshot(states);
	}

	private static void requireExpectedStack(ItemStack stack, String expectedItemId, int count) {
		if (stack.isEmpty() || !itemId(stack).equals(expectedItemId) || stack.getCount() < count) {
			throw new AgentDomainException("SOURCE_MISMATCH", "Source item identity or count did not match");
		}
	}

	private static int capacity(Slot destination, ItemStack stack) {
		if (!destination.mayPlace(stack)) return 0;
		ItemStack current = destination.getItem();
		if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, stack)) return 0;
		return Math.max(0, destination.getMaxStackSize(stack) - current.getCount());
	}

	private static int menuCapacity(AbstractContainerMenu menu, int first, int last, ItemStack stack) {
		int capacity = 0;
		for (int index = first; index <= last; index++) capacity += capacity(menu.getSlot(index), stack);
		return capacity;
	}

	private static List<TransactionSnapshot.OwnedStack> ownedStacks(Inventory inventory, List<Slot> extraSlots) {
		List<TransactionSnapshot.OwnedStack> owned = new ArrayList<>();
		for (int index = 0; index < inventory.getContainerSize(); index++) addOwned(owned, inventory.getItem(index));
		for (Slot slot : extraSlots) addOwned(owned, slot.getItem());
		return owned;
	}

	private static List<TransactionSnapshot.OwnedStack> ownedStacks(List<ItemStack> stacks) {
		List<TransactionSnapshot.OwnedStack> owned = new ArrayList<>();
		for (ItemStack stack : stacks) addOwned(owned, stack);
		return owned;
	}

	private static TransactionSnapshot.OwnedStack ownedStack(ItemStack stack) {
		return new TransactionSnapshot.OwnedStack(itemId(stack), stack.getCount(), stack.getComponentsPatch().toString());
	}

	private static void addOwned(List<TransactionSnapshot.OwnedStack> owned, ItemStack stack) {
		if (!stack.isEmpty()) owned.add(ownedStack(stack));
	}

	private static int reachableEscrowCount(List<ItemStack> escrow, String expectedItemId) {
		int count = 0;
		for (ItemStack stack : escrow) {
			if (!stack.isEmpty() && itemId(stack).equals(expectedItemId)) count += stack.getCount();
		}
		return count;
	}

	private static void preserveReachableEscrow(
			Transaction owner,
			ServerPlayer player,
			TransactionSnapshot.TransferMutationEscrow<ItemStack> mutationEscrow
	) {
		for (ItemStack stack : mutationEscrow.reachable()) preserveReachable(owner, player, stack);
		mutationEscrow.clear();
	}

	private static void preserveReachable(Transaction owner, ServerPlayer player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		int originalCount = stack.getCount();
		int inventoryBefore = inventoryOwnershipCount(player.getInventory(), stack);
		try {
			player.getInventory().placeItemBackInInventory(stack);
		} catch (RuntimeException ignored) {
			int inventoryAfter = inventoryOwnershipCount(player.getInventory(), stack);
			int credited = Math.max(0, inventoryAfter - inventoryBefore);
			int reflectedByEscrow = Math.max(0, originalCount - stack.getCount());
			int unreflectedCredit = Math.min(stack.getCount(), Math.max(0, credited - reflectedByEscrow));
			if (unreflectedCredit > 0) stack.shrink(unreflectedCredit);
		}
		if (!stack.isEmpty()) owner.retainEscrow(stack);
	}

	private static int inventoryOwnershipCount(Inventory inventory, ItemStack expected) {
		int count = 0;
		for (int index = 0; index < inventory.getContainerSize(); index++) {
			ItemStack stack = inventory.getItem(index);
			if (ItemStack.isSameItemSameComponents(stack, expected)) count += stack.getCount();
		}
		return count;
	}

	private static int menuSlot(ChestMenu menu, String kind, int logicalSlot) {
		if (kind.equals("container")) {
			if (logicalSlot < 0 || logicalSlot >= menu.getContainer().getContainerSize()) return -1;
			return logicalSlot;
		}
		if (!kind.equals("player")) return -1;
		int base = menu.getRowCount() * 9;
		return logicalSlot >= 0 && logicalSlot < 9 ? base + 27 + logicalSlot
				: logicalSlot >= 9 && logicalSlot < 36 ? base + logicalSlot - 9 : -1;
	}

	private static int inventoryMenuSlot(int inventorySlot) {
		return inventorySlot >= 0 && inventorySlot < 9 ? 36 + inventorySlot
				: inventorySlot >= 9 && inventorySlot < 36 ? inventorySlot : -1;
	}

	private static int furnacePlayerSlot(int inventorySlot) {
		return inventorySlot >= 0 && inventorySlot < 9 ? 30 + inventorySlot
				: inventorySlot >= 9 && inventorySlot < 36 ? 3 + inventorySlot - 9 : -1;
	}

	private static void useInventoryMenu(ServerPlayer player) {
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		if (player.containerMenu.getClass() != InventoryMenu.class) {
			throw new AgentDomainException("UNSUPPORTED_MENU", "Player inventory menu is unavailable");
		}
	}

	private static AbstractContainerMenu requireCurrentSupportedMenu(ServerPlayer player, String expectedMenuId) {
		AbstractContainerMenu menu = player.containerMenu;
		String actualMenuId;
		try {
			actualMenuId = BuiltInRegistries.MENU.getKey(menu.getType()).toString();
		} catch (RuntimeException exception) {
			throw new AgentDomainException("UNSUPPORTED_MENU", "Open menu has no registered vanilla type");
		}
		if (!actualMenuId.equals(expectedMenuId)) {
			throw new AgentDomainException(
					"MENU_MISMATCH",
					"Expected open menu " + expectedMenuId + " but observed " + actualMenuId
			);
		}
		MenuCapabilityRegistry.requireSupported(actualMenuId);
		return menu;
	}

	private static void requireBlockPreflight(ServerPlayer player, BlockPos position) {
		ServerLevel level = player.level();
		if (!level.hasChunkAt(position)) {
			throw new AgentDomainException("TARGET_NOT_LOADED", "Target chunk is not loaded");
		}
		if (player.distanceToSqr(Vec3.atCenterOf(position)) > MAX_INTERACTION_DISTANCE_SQUARED) {
			throw new AgentDomainException("TARGET_TOO_FAR", "Target menu is out of reach");
		}
	}

	private static boolean vanillaFurnaceMenu(AbstractContainerMenu menu) {
		String name = menu.getClass().getName();
		return name.equals("net.minecraft.world.inventory.FurnaceMenu")
				|| name.equals("net.minecraft.world.inventory.SmokerMenu")
				|| name.equals("net.minecraft.world.inventory.BlastFurnaceMenu");
	}

	private static TickResult unsupportedMenu(AbstractContainerMenu menu) {
		return TickResult.failed("UNSUPPORTED_MENU", "Unsupported or modded menu: " + menu.getClass().getName());
	}

	private static String containerLeaseKey(ServerLevel level, BlockPos position) {
		return "container:" + level.dimension().identifier() + ":" + position.asLong();
	}

	private static BlockPos blockPosition(JsonObject arguments) {
		return new BlockPos(integer(arguments, "x"), integer(arguments, "y"), integer(arguments, "z"));
	}

	private static InteractionHand shieldHand(ServerPlayer player) {
		if (player.getOffhandItem().getItem() instanceof ShieldItem) return InteractionHand.OFF_HAND;
		if (player.getMainHandItem().getItem() instanceof ShieldItem) return InteractionHand.MAIN_HAND;
		throw new AgentDomainException("SHIELD_NOT_EQUIPPED", "A shield must be held in either hand");
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static void returnCarried(ServerPlayer player, AbstractContainerMenu menu) {
		ItemStack carried = menu.getCarried();
		if (carried.isEmpty()) {
			menu.setCarried(ItemStack.EMPTY);
			return;
		}
		menu.setCarried(ItemStack.EMPTY);
		player.getInventory().placeItemBackInInventory(carried);
	}

	private static String text(JsonObject object, String field) {
		return object.get(field).getAsString();
	}

	private static int integer(JsonObject object, String field) {
		return object.get(field).getAsInt();
	}

	private static String safeMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}

	private static Result unavailable(String code) {
		return Result.failed(code, "Capability unavailable until its server adapter is validated");
	}

	public record Result(boolean succeeded, String reasonCode, String message) {
		public static Result succeeded(String message) { return new Result(true, "SUCCEEDED", message); }
		public static Result failed(String code, String message) { return new Result(false, code, message); }
	}
}
