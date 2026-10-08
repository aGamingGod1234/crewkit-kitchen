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
import dev.agaminggod.arenaagents.server.runtime.menu.AgentInventoryView;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuCapabilityRegistry;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuInspection;
import dev.agaminggod.arenaagents.server.runtime.menu.MenuStackIdentity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class AdvancedInteractionService implements ServerTransactionAdapter {
	private static final long EQUIPMENT_TIMEOUT_MS = 5_000L;
	private final ServerProtectionPolicy protection;
	private final ResourceLeaseManager leases;
	private final CraftCommitter craftCommitter;

	public AdvancedInteractionService(ServerProtectionPolicy protection, ResourceLeaseManager leases) {
		this(protection, leases, (menu, player, resultSlot) -> menu.quickMoveStack(player, resultSlot));
	}

	AdvancedInteractionService(ServerProtectionPolicy protection, ResourceLeaseManager leases, CraftCommitter craftCommitter) {
		this.protection = Objects.requireNonNull(protection);
		this.leases = Objects.requireNonNull(leases);
		this.craftCommitter = Objects.requireNonNull(craftCommitter);
	}

	@FunctionalInterface
	interface CraftCommitter {
		ItemStack quickMove(AbstractContainerMenu menu, ServerPlayer player, int resultSlot);
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

	static boolean recipeAllowed(ServerRecipeBook recipeBook, ResourceKey<Recipe<?>> recipeKey, boolean limitedCrafting) {
		Objects.requireNonNull(recipeBook, "recipeBook must not be null");
		Objects.requireNonNull(recipeKey, "recipeKey must not be null");
		return !limitedCrafting || recipeBook.contains(recipeKey);
	}

	static boolean craftOutputSatisfiesRequest(int outputCount, int requestedCount) {
		return requestedCount > 0 && outputCount >= requestedCount;
	}

	static String craftPlacementFailureReason(RecipeBookMenu.PostPlaceAction placement) {
		return placement == RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE
				? "RECIPE_INPUTS_UNAVAILABLE"
				: "RECIPE_PLACEMENT_REJECTED";
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
			case MENU_CLICK -> new MenuClickTransaction(player, request, arguments);
			case MENU_CLOSE -> new MenuCloseTransaction(player, request, arguments);
			case BEACON_EFFECTS -> new BeaconEffectsTransaction(player, request, arguments);
			default -> throw new AgentDomainException("UNSUPPORTED_TRANSACTION", "Action is not a transaction adapter action");
		};
	}

	public Result setDoor(AgentId id, ServerPlayer agent, BlockPos position, boolean open) {
		ServerLevel level = (ServerLevel) agent.level();
		String key = "block:" + level.dimension().identifier() + ":" + position.asLong();
		if (!leases.acquire(key, id, System.currentTimeMillis(), 5_000L)) return Result.failed("RESOURCE_BUSY", "Door is leased");
		try {
			requireBlockPreflight(agent, position);
			if (!protection.mayModifyBlock(agent, level, position)) return Result.failed("PROTECTION_DENIED", "Door mutation denied");
			BlockState state = level.getBlockState(position);
			if (!state.hasProperty(BlockStateProperties.OPEN)) return Result.failed("NOT_A_DOOR", "Block has no open property");
			if (state.getValue(BlockStateProperties.OPEN) == open) return Result.succeeded("Door already has the requested state");
			BlockHitResult hit = visibleBlockHit(agent, position);
			InteractionResult interaction = agent.gameMode.useItemOn(agent, level, agent.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
			BlockState after = level.getBlockState(position);
			if (!interaction.consumesAction() || !after.hasProperty(BlockStateProperties.OPEN)
					|| after.getValue(BlockStateProperties.OPEN) != open) return Result.failed("DOOR_REJECTED", "Vanilla interaction did not produce the requested door state");
			return Result.succeeded(open ? "Door opened" : "Door closed");
		} catch (AgentDomainException exception) {
			return Result.failed(exception.code(), safeMessage(exception));
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
		ItemStack before = agent.getInventory().getItem(slot).copy();
		ItemStack removed = agent.getInventory().removeItem(slot, count);
		if (removed.isEmpty()) return Result.failed("EMPTY_SLOT", "Inventory slot is empty");
		try {
			if (agent.drop(removed, false) == null) {
				return dropFailure(agent.getInventory(), slot, before,
						"ITEM_DROP_REJECTED", "World rejected item drop");
			}
		} catch (RuntimeException exception) {
			return dropFailure(agent.getInventory(), slot, before,
					"ITEM_DROP_FAILED", "Item drop raised an exception: " + safeMessage(exception));
		}
		return Result.succeeded("Dropped item stack");
	}

	private static Result dropFailure(Inventory inventory, int slot, ItemStack before, String reasonCode, String message) {
		ItemStack current = inventory.getItem(slot);
		if ((!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, before))
				|| current.getCount() > before.getCount()) {
			return Result.failed("ROLLBACK_FAILED", message + "; inventory changed before the debit could be restored");
		}
		inventory.setItem(slot, before);
		inventory.setChanged();
		ItemStack restored = inventory.getItem(slot);
		if (!ItemStack.isSameItemSameComponents(restored, before) || restored.getCount() != before.getCount()) {
			return Result.failed("ROLLBACK_FAILED", message + "; exact inventory restoration failed");
		}
		return Result.failed(reasonCode, message + "; inventory was restored");
	}

	public Result respawn() { return unavailable("RESPAWN_REQUIRES_SERVER_TICK_DEATH_RECONCILER"); }
	public Result chunkTicket() { return unavailable("CHUNK_TICKET_REQUIRES_VERSION_VALIDATED_TICKET_TYPE"); }

	private abstract class Transaction implements ActiveTransaction {
		final ServerPlayer player;
		final ServerActionRequest request;
		final JsonObject arguments;
		/** Started on the first tick, so the turn toward a block menu before it opens never eats the paced budget. */
		ElapsedTimeAccumulator elapsedTime;
		final long timeoutMs;
		final TerminalGate terminal = new TerminalGate();
		final List<ItemStack> retainedEscrow = new ArrayList<>();
		String leaseKey;
		boolean menuOpened;
		boolean executed;
		boolean preserveCursor;

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
			if (elapsedTime == null) elapsedTime = new ElapsedTimeAccumulator(nowEpochMs);
			// Work already committed (a craft whose result was taken) stays a success even if the agent dies or the
			// deadline passes while the menu is still being closed.
			TickResult committed = committedResult();
			if (committed != null && (!player.isAlive() || elapsedTime.advance(nowEpochMs) >= timeoutMs)) {
				return finish(committed);
			}
			if (!player.isAlive()) return finish(TickResult.failed("AGENT_DEAD", "Agent player died"));
			if (elapsedTime.advance(nowEpochMs) >= timeoutMs) {
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

		/** A success that is already irreversible while the transaction finishes its presentation, else null. */
		@Override
		public TickResult committedResult() {
			return null;
		}

		/** A cancel during the linger after committed work reports that work; the items have already moved. */
		@Override
		public void cancel(String reason) {
			TickResult committed = committedResult();
			terminal.finish(committed != null ? committed : TickResult.cancelled(reason == null ? "Transaction cancelled" : reason));
			cleanup();
		}

		@Override
		public void cleanup() {
			terminal.cleanupOnce(() -> ServerTransactionAdapter.runBestEffort(
					this::beforeCleanup,
					() -> { if (player.isUsingItem()) player.stopUsingItem(); },
					this::flushRetainedEscrow,
					() -> { if (!preserveCursor) returnCarried(player, player.containerMenu); },
					() -> { if (menuOpened) player.closeContainer(); },
					() -> { if (!preserveCursor) returnCarried(player, player.inventoryMenu); },
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
			AbstractContainerMenu previous = player.containerMenu;
			BlockHitResult hit = visibleBlockHit(player, position);
			InteractionResult interaction = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
			if (!interaction.consumesAction() || player.containerMenu == previous) {
				throw new AgentDomainException("MENU_OPEN_REJECTED", "Server rejected opening the target menu");
			}
			menuOpened = true;
			return player.containerMenu;
		}
	}

	/**
	 * A one-move menu job paced the way a player does it: the screen opens and is shown for {@link #OPEN_TICKS},
	 * the stack moves, the screen stays {@link #LINGER_TICKS}, then cleanup closes it. Done in a single tick, the
	 * menu opened and closed between two POV snapshots, so a spectator saw items appear in a furnace or a tool
	 * appear in the hand with no screen at all (play-test: every furnace_transaction and select_tool).
	 */
	private abstract class PacedMenuTransaction extends Transaction {
		/** Same pacing as crafting: the opened screen is shown before the move and after it. */
		static final int OPEN_TICKS = 3;
		static final int LINGER_TICKS = 3;

		private int ticks;
		private int actTick = -1;
		private int lingerUntil;
		private AbstractContainerMenu shown;
		private boolean inventoryView;
		private TickResult committed;

		PacedMenuTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments, long timeoutMs) {
			super(player, request, arguments, timeoutMs);
		}

		/** Validates and opens the screen; returns a terminal result to stop before any move, else null. */
		abstract TickResult open();

		/** The single exact move, run once while the screen is shown. */
		abstract TickResult act();

		/** False when the job needs no screen (a tool already on the hotbar is one number key). */
		boolean needsScreen() {
			return true;
		}

		/** The agent's own inventory screen, which the server never opens: mark it so spectators see it. */
		void openInventoryScreen() {
			useInventoryMenu(player);
			AgentInventoryView.open(player);
			inventoryView = true;
		}

		@Override
		final TickResult execute(long nowEpochMs) {
			ticks++;
			if (committed != null) return ticks >= lingerUntil ? committed : TickResult.running();
			if (actTick < 0) {
				if (!needsScreen()) return actOnce();
				TickResult stopped = open();
				if (stopped != null) return stopped;
				shown = player.containerMenu;
				actTick = ticks + OPEN_TICKS;
				return TickResult.running();
			}
			if (ticks < actTick) return TickResult.running();
			if (player.containerMenu != shown) {
				return TickResult.failed("MENU_CLOSED", "The menu closed before the item was moved; nothing was moved");
			}
			TickResult result = actOnce();
			if (result.state() != TickState.SUCCEEDED) return result;
			committed = result;
			lingerUntil = ticks + LINGER_TICKS;
			return TickResult.running();
		}

		private TickResult actOnce() {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Menu move executed more than once");
			executed = true;
			return act();
		}

		@Override
		public TickResult committedResult() {
			return committed;
		}

		@Override
		void beforeCleanup() {
			if (inventoryView) AgentInventoryView.close(player);
		}
	}

	private final class TransferTransaction extends PacedMenuTransaction {
		private ChestMenu menu;

		TransferTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
		}

		@Override
		TickResult open() {
			if (text(arguments, "sourceKind").equals(text(arguments, "destinationKind"))) {
				return TickResult.failed("INVALID_TRANSFER", "Exactly one transfer endpoint must be the container");
			}
			AbstractContainerMenu opened = openBlockMenu(blockPosition(arguments));
			player.swing(InteractionHand.MAIN_HAND);
			if (opened.getClass() != ChestMenu.class) return unsupportedMenu(opened);
			menu = (ChestMenu) opened;
			return null;
		}

		@Override
		TickResult act() {
			int source = menuSlot(menu, text(arguments, "sourceKind"), integer(arguments, "sourceSlot"));
			int destination = menuSlot(menu, text(arguments, "destinationKind"), integer(arguments, "destinationSlot"));
			return transfer(this, player, menu, source, destination,
					text(arguments, "expectedItemId"), integer(arguments, "count"), true);
		}
	}

	private final class MenuTransferTransaction extends Transaction {
		MenuTransferTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
			preserveCursor = true;
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Menu transfer executed more than once");
			executed = true;
			AbstractContainerMenu menu = requireMenuForAction(player, arguments);
			return transferUsingMenuInput(player, menu, integer(arguments, "sourceSlot"),
					integer(arguments, "destinationSlot"), text(arguments, "expectedItemId"), integer(arguments, "count"));
		}
	}

	private final class MenuButtonTransaction extends Transaction {
		MenuButtonTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
			preserveCursor = true;
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Menu button executed more than once");
			executed = true;
			AbstractContainerMenu menu = requireMenuForAction(player, arguments);
			int buttonId = integer(arguments, "buttonId");
			boolean accepted;
			if (menu instanceof MerchantMenu merchant) {
				if (buttonId < 0 || buttonId >= merchant.getOffers().size()) {
					return TickResult.failed("MENU_BUTTON_REJECTED", "The requested merchant offer is not present");
				}
				menu.incrementStateId();
				merchant.setSelectionHint(buttonId);
				merchant.tryMoveItems(buttonId);
				accepted = true;
			} else if (menu instanceof CrafterMenu crafter) {
				if (buttonId < 0 || buttonId >= 9 || menu.getSlot(buttonId).hasItem()) {
					return TickResult.failed("MENU_BUTTON_REJECTED", "Only an empty crafter grid slot can be toggled");
				}
				menu.incrementStateId();
				crafter.setSlotState(buttonId, crafter.isSlotDisabled(buttonId));
				accepted = true;
			} else {
				menu.incrementStateId();
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
			preserveCursor = true;
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Anvil rename executed more than once");
			executed = true;
			AbstractContainerMenu current = requireMenuForAction(player, arguments);
			if (!(current instanceof AnvilMenu anvil)) return unsupportedMenu(current);
			anvil.incrementStateId();
			if (!anvil.setItemName(text(arguments, "name"))) {
				return TickResult.failed("ANVIL_NAME_REJECTED", "Vanilla anvil rejected the requested name");
			}
			anvil.broadcastChanges();
			return TickResult.succeeded("ANVIL_NAME_SET", "Vanilla anvil accepted the requested name");
		}
	}

	private final class FurnaceTransaction extends PacedMenuTransaction {
		private AbstractContainerMenu opened;

		FurnaceTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
		}

		@Override
		TickResult open() {
			opened = openBlockMenu(blockPosition(arguments));
			player.swing(InteractionHand.MAIN_HAND);
			if (!(opened instanceof AbstractFurnaceMenu) || !vanillaFurnaceMenu(opened)) return unsupportedMenu(opened);
			return null;
		}

		@Override
		TickResult act() {
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

	private final class EquipmentTransaction extends PacedMenuTransaction {
		EquipmentTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, EQUIPMENT_TIMEOUT_MS);
		}

		@Override
		TickResult open() {
			openInventoryScreen();
			return null;
		}

		@Override
		TickResult act() {
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

	private final class ToolSelectionTransaction extends PacedMenuTransaction {
		ToolSelectionTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, EQUIPMENT_TIMEOUT_MS);
		}

		@Override
		boolean needsScreen() {
			return inventoryMenuSlot(integer(arguments, "sourceSlot")) != inventoryMenuSlot(integer(arguments, "hotbarSlot"));
		}

		@Override
		TickResult open() {
			openInventoryScreen();
			return null;
		}

		@Override
		TickResult act() {
			useInventoryMenu(player);
			int inventorySource = integer(arguments, "sourceSlot");
			int hotbarSlot = integer(arguments, "hotbarSlot");
			int source = inventoryMenuSlot(inventorySource);
			int destination = inventoryMenuSlot(hotbarSlot);
			int previousSelectedSlot = player.getInventory().getSelectedSlot();
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
			try {
				player.getInventory().setSelectedSlot(hotbarSlot);
				ItemStack selected = player.getMainHandItem();
				if (!itemId(selected).equals(text(arguments, "expectedItemId"))) {
					return rollbackToolSelection(this, source, destination, previousSelectedSlot,
							TickResult.failed("SELECTION_NOT_CONFIRMED", "Expected tool was not observed in the selected hand"));
				}
			} catch (RuntimeException exception) {
				return rollbackToolSelection(this, source, destination, previousSelectedSlot,
						TickResult.failed("SELECTION_NOT_CONFIRMED", "Tool selection verification raised an exception: "
								+ safeMessage(exception)));
			}
			return TickResult.succeeded("TOOL_SELECTED", "Tool moved with vanilla slots and selected");
		}
	}

	private final class MenuClickTransaction extends Transaction {
		MenuClickTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, EQUIPMENT_TIMEOUT_MS);
			preserveCursor = true;
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Menu input was already applied");
			executed = true;
			AbstractContainerMenu menu = requireMenuSession(player, arguments);
			int slotIndex = integer(arguments, "slot");
			int button = integer(arguments, "button");
			ContainerInput input = ContainerInput.valueOf(text(arguments, "clickType"));
			validateMenuInput(slotIndex, button, input, menu.slots.size());
			ItemStack observed = slotIndex == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE
					? menu.getCarried() : menu.getSlot(slotIndex).getItem();
			String observedId = observed.isEmpty() ? "minecraft:air" : itemId(observed);
			if (!observedId.equals(text(arguments, "expectedItemId")) || observed.getCount() != integer(arguments, "expectedCount")) {
				return TickResult.failed("SOURCE_MISMATCH", "Observed slot identity or exact count changed before the input");
			}
			if (arguments.has("expectedFingerprint") && !MenuStackIdentity.fingerprint(observed, player.registryAccess())
					.equals(text(arguments, "expectedFingerprint"))) {
				return TickResult.failed("SOURCE_MISMATCH", "Observed item components changed before the input");
			}
			if ((input == ContainerInput.THROW || slotIndex == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && input == ContainerInput.PICKUP)
					&& !protection.mayDropItem(player)) return TickResult.failed("PROTECTION_DENIED", "Item drop denied");
			TransactionSnapshot before = snapshot(menu);
			ItemStack cursorBefore = menu.getCarried().copy();
			int experienceBefore = player.totalExperience;
			menu.incrementStateId();
			try {
				menu.clicked(slotIndex, button, input, player);
				menu.broadcastChanges();
			} catch (RuntimeException mutationFailure) {
				return TickResult.failed("MENU_INPUT_PARTIAL", "Vanilla input raised an exception after it began; inspect the current menu before deciding the next action: " + safeMessage(mutationFailure));
			}
			boolean changed = !before.equals(snapshot(menu)) || !ItemStack.matches(cursorBefore, menu.getCarried())
					|| experienceBefore != player.totalExperience;
			return TickResult.succeeded(changed ? "MENU_INPUT_APPLIED" : "MENU_INPUT_NO_CHANGE",
					"Vanilla " + input.name() + " input applied once; containerId=" + menu.containerId + ", stateId=" + menu.getStateId()
							+ (changed ? "; menu state changed" : "; no slot, cursor or experience change observed"));
		}
	}

	private final class MenuCloseTransaction extends Transaction {
		MenuCloseTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, EQUIPMENT_TIMEOUT_MS);
			preserveCursor = true;
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Menu close was already applied");
			executed = true;
			AbstractContainerMenu menu = requireMenuSession(player, arguments);
			menu.incrementStateId();
			if (menu == player.inventoryMenu) {
				menu.removed(player);
				menu.broadcastChanges();
			} else {
				player.closeContainer();
			}
			return TickResult.succeeded("MENU_CLOSED", "Vanilla menu close returned carried items and crafting inputs");
		}
	}

	private final class BeaconEffectsTransaction extends Transaction {
		BeaconEffectsTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments) {
			super(player, request, arguments, EQUIPMENT_TIMEOUT_MS);
			preserveCursor = true;
		}

		@Override
		TickResult execute(long nowEpochMs) {
			if (executed) return TickResult.failed("TRANSACTION_CONFLICT", "Beacon input was already applied");
			executed = true;
			AbstractContainerMenu current = requireMenuSession(player, arguments);
			if (!(current instanceof BeaconMenu beacon)) return unsupportedMenu(current);
			Optional<Holder<MobEffect>> primary = beaconEffect(text(arguments, "primaryEffectId"));
			Optional<Holder<MobEffect>> secondary = beaconEffect(text(arguments, "secondaryEffectId"));
			validateBeaconSelection(beacon.getLevels(), primary, secondary);
			ItemStack payment = beacon.getSlot(0).getItem();
			if (!beacon.hasPayment() || !beacon.getSlot(0).mayPlace(payment)) {
				return TickResult.failed("BEACON_PAYMENT_REQUIRED", "Place an accepted payment in the beacon slot first");
			}
			int paymentCount = payment.getCount();
			beacon.incrementStateId();
			try {
				beacon.updateEffects(primary, secondary);
				beacon.broadcastChanges();
			} catch (RuntimeException mutationFailure) {
				return TickResult.failed("MENU_INPUT_PARTIAL", "Beacon input raised an exception after it began; inspect current effects and payment: " + safeMessage(mutationFailure));
			}
			if (!Objects.equals(beacon.getPrimaryEffect(), primary.orElse(null))
					|| !Objects.equals(beacon.getSecondaryEffect(), secondary.orElse(null))
					|| beacon.getSlot(0).getItem().getCount() != paymentCount - 1) {
				return TickResult.failed("MENU_INPUT_PARTIAL", "Beacon effects or payment did not match the requested vanilla input; inspect before continuing");
			}
			return TickResult.succeeded("BEACON_EFFECTS_SET", "Requested beacon effects applied and one payment consumed by vanilla");
		}
	}

	private static Optional<Holder<MobEffect>> beaconEffect(String effectId) {
		if (effectId.equals("none")) return Optional.empty();
		Identifier identifier = Identifier.tryParse(effectId);
		if (identifier == null) throw new AgentDomainException("UNKNOWN_EFFECT", "Beacon effect identifier is invalid");
		return Optional.of(BuiltInRegistries.MOB_EFFECT.get(identifier)
				.orElseThrow(() -> new AgentDomainException("UNKNOWN_EFFECT", "Requested effect is not registered")));
	}

	static void validateBeaconSelection(int levels, Optional<Holder<MobEffect>> primary, Optional<Holder<MobEffect>> secondary) {
		if (primary.isEmpty()) throw new AgentDomainException("BEACON_PRIMARY_REQUIRED", "The beacon confirm button requires a primary effect");
		boolean primaryAllowed = false;
		for (int tier = 0; tier < Math.min(3, levels); tier++) {
			if (BeaconBlockEntity.BEACON_EFFECTS.get(tier).contains(primary.get())) primaryAllowed = true;
		}
		if (!primaryAllowed) throw new AgentDomainException("BEACON_EFFECT_UNAVAILABLE", "The primary effect is unavailable at the current beacon level");
		if (secondary.isPresent() && (levels < 4 || !secondary.equals(primary)
				&& !BeaconBlockEntity.BEACON_EFFECTS.get(3).contains(secondary.get()))) {
			throw new AgentDomainException("BEACON_EFFECT_UNAVAILABLE", "The secondary effect is not an available beacon choice");
		}
	}

	private TickResult rollbackToolSelection(
			Transaction owner,
			int source,
			int destination,
			int previousSelectedSlot,
			TickResult failure
	) {
		boolean inventoryRestored = source == destination;
		if (!inventoryRestored) {
			try {
				TickResult reversed = transfer(owner, owner.player, owner.player.inventoryMenu, destination, source,
						text(owner.arguments, "expectedItemId"), 1, true);
				inventoryRestored = reversed.state() == TickState.SUCCEEDED;
			} catch (RuntimeException ignored) {
				inventoryRestored = false;
			}
		}
		boolean selectionRestored;
		try {
			owner.player.getInventory().setSelectedSlot(previousSelectedSlot);
			selectionRestored = owner.player.getInventory().getSelectedSlot() == previousSelectedSlot;
		} catch (RuntimeException ignored) {
			selectionRestored = false;
		}
		if (!inventoryRestored || !selectionRestored) {
			return TickResult.failed("ROLLBACK_FAILED", failure.message() + "; prior tool selection state could not be restored");
		}
		return TickResult.failed(failure.reasonCode(), failure.message() + "; prior inventory and selection were restored");
	}

	/**
	 * Crafts like a player instead of filling the grid through the recipe book in one tick: opens the inventory
	 * (2x2) or the crafting table menu (3x3), moves each ingredient into the grid with ordinary slot clicks a few
	 * ticks apart, shift-clicks the result out and closes the menu. Every step is a vanilla menu click on the
	 * server, so a spectator mirroring the agent's menu watches the items move. Vanilla recipe placement is only
	 * used as a same-tick dry run to learn which ingredient goes in which slot, and is undone before any click.
	 */
	private final class CraftTransaction extends Transaction {
		/** Ticks the opened, empty grid is shown before the first click. */
		static final int OPEN_TICKS = 3;
		/** Ticks between clicks: quick, but each item movement is readable. */
		static final int CLICK_TICKS = 2;
		/** Ticks the menu stays open after the result has been taken. */
		static final int LINGER_TICKS = 3;

		private enum Phase { START, FILL, LINGER }

		private final boolean table;
		private boolean placementAttempted;
		private Phase phase = Phase.START;
		private int ticks;
		private int nextActionTick;
		private AbstractContainerMenu menu;
		private AbstractCraftingMenu craftingMenu;
		private List<Slot> gridSlots;
		private CraftingRecipe craftingRecipe;
		private ResourceKey<Recipe<?>> recipeKey;
		private TransactionSnapshot.CraftPlacementGuard placementGuard;
		/** Menu slot index of each grid cell still to fill, mapped to the one ingredient vanilla chose for it. */
		private final Map<Integer, ItemStack> pendingGrid = new LinkedHashMap<>();
		private int carrySourceSlot = -1;
		private TickResult committed;
		/** Inventory, grid and cursor ownership when this transaction last finished a tick. */
		private List<TransactionSnapshot.OwnedStack> ownershipAtLastTick;
		/** Stacks this transaction dropped at the body because no inventory slot could take them, as vanilla does. */
		private final List<TransactionSnapshot.OwnedStack> droppedAtBody = new ArrayList<>();
		private boolean externalCloseAccounted;

		CraftTransaction(ServerPlayer player, ServerActionRequest request, JsonObject arguments, boolean table) {
			super(player, request, arguments, arguments.get("timeoutMs").getAsLong());
			this.table = table;
		}

		@Override
		TickResult execute(long nowEpochMs) {
			// Pickups land in the inventory between the craft's paced ticks (a player picks up items with a menu
			// open too); only this transaction's own clicks must conserve ownership.
			if (placementGuard != null && ownershipAtLastTick != null) {
				List<TransactionSnapshot.OwnedStack> held = heldOwnership();
				if (menu != null && player.containerMenu != menu && !externalCloseAccounted) {
					// Closed by someone else (the table broke, the server closed it): vanilla put the grid and cursor
					// back and dropped at the body what did not fit. That loss is recorded as dropped at the body, not
					// absorbed like a pickup, or the failure would claim the exact ownership was restored.
					externalCloseAccounted = true;
					List<TransactionSnapshot.OwnedStack> lost = TransactionSnapshot.ownershipLost(ownershipAtLastTick, held);
					droppedAtBody.addAll(lost);
					held = new ArrayList<>(held);
					held.addAll(lost);
				}
				placementGuard.absorbExternal(ownershipAtLastTick, held);
			}
			TickResult result = step();
			ownershipAtLastTick = placementGuard == null || result.terminal() ? null : heldOwnership();
			return result;
		}

		private List<TransactionSnapshot.OwnedStack> heldOwnership() {
			List<TransactionSnapshot.OwnedStack> owned = ownedStacks(player.getInventory(), gridSlots);
			if (menu != null) addOwned(owned, menu.getCarried());
			return owned;
		}

		private TickResult step() {
			ticks++;
			if (phase == Phase.START) {
				TickResult failure = start();
				if (failure != null) return failure;
				phase = Phase.FILL;
				nextActionTick = ticks + OPEN_TICKS;
				return TickResult.running();
			}
			if (ticks < nextActionTick) return TickResult.running();
			if (phase == Phase.LINGER) {
				// Leftovers (remainders, or the grid with a full inventory) go back now, so the result can say where.
				returnMenuItems();
				int dropped = droppedAtBody.stream().mapToInt(TransactionSnapshot.OwnedStack::count).sum();
				return dropped == 0 ? committed : TickResult.succeeded(committed.reasonCode(), committed.message() + "; "
						+ dropped + " leftover item" + (dropped == 1 ? " was" : "s were") + " dropped at the body because the inventory is full");
			}
			nextActionTick = ticks + CLICK_TICKS;
			if (player.containerMenu != menu) {
				return failureAfterPlacement("MENU_CLOSED", "The crafting menu closed before the craft finished",
						placementGuard, gridSlots);
			}
			try {
				return fillStep();
			} catch (RuntimeException clickFailure) {
				return failureAfterPlacement("CRAFT_CLICK_EXCEPTION",
						"A crafting click raised an exception: " + safeMessage(clickFailure), placementGuard, gridSlots);
			}
		}

		/** Resolves the recipe, opens the menu and plans the clicks. Returns a failure, or null to continue. */
		private TickResult start() {
			String requestedRecipeId = canonicalRecipeId(text(arguments, "recipeId"));
			if (requestedRecipeId.equals("minecraft:planks")) {
				requestedRecipeId = resolveGenericPlankRecipeId(player.level(), player.getInventory());
			}
			String lookupRecipeId = requestedRecipeId;
			recipeKey = ResourceKey.create(Registries.RECIPE, Identifier.parse(lookupRecipeId));
			RecipeHolder<?> holder = player.level().recipeAccess().byKey(recipeKey).orElseThrow(() ->
					new AgentDomainException("RECIPE_NOT_FOUND", "Recipe " + lookupRecipeId + " is not registered"));
			if (!(holder.value() instanceof CraftingRecipe recipe)) {
				return TickResult.failed("RECIPE_TYPE_MISMATCH", "Requested recipe is not a crafting recipe");
			}
			craftingRecipe = recipe;
			if (!recipeAllowed(player.getRecipeBook(), holder.id(), player.level().getGameRules().get(GameRules.LIMITED_CRAFTING))) {
				return TickResult.failed("RECIPE_LOCKED", "Limited crafting requires an already unlocked recipe");
			}
			if (table) {
				menu = openBlockMenu(blockPosition(arguments));
				player.swing(InteractionHand.MAIN_HAND);
				if (menu.getClass() != CraftingMenu.class) return unsupportedMenu(menu);
			} else {
				useInventoryMenu(player);
				menu = player.inventoryMenu;
				if (menu.getClass() != InventoryMenu.class) return unsupportedMenu(menu);
				AgentInventoryView.open(player);
			}
			craftingMenu = (AbstractCraftingMenu) menu;
			gridSlots = craftingMenu.getInputGridSlots();
			if (!menu.getCarried().isEmpty()) {
				return TickResult.failed("TRANSACTION_CONFLICT", "Safe crafting requires an empty carried stack");
			}
			placementGuard = new TransactionSnapshot.CraftPlacementGuard(ownedStacks(player.getInventory(), gridSlots));
			placementAttempted = true;
			placementGuard.markPlacementAttempted();
			// Leftovers in the grid go back to the inventory with a shift-click, as a player would clear it.
			for (Slot slot : gridSlots) {
				if (slot.hasItem()) click(slot.index, 0, ContainerInput.QUICK_MOVE);
			}
			for (Slot slot : gridSlots) {
				if (slot.hasItem()) {
					return failureAfterPlacement("CRAFT_GRID_OCCUPIED",
							"The crafting grid could not be cleared into the inventory", placementGuard, gridSlots);
				}
			}
			return planClicks(holder);
		}

		/**
		 * Dry-runs vanilla recipe placement to learn the ingredient layout and validate one craft, then restores the
		 * exact pre-placement menu in the same tick, before anything is sent or clicked.
		 */
		private TickResult planClicks(RecipeHolder<?> holder) {
			CraftMenuSnapshot beforePlacement = CraftMenuSnapshot.capture(menu);
			String reasonCode = null;
			String message = null;
			try {
				TransactionSnapshot.CraftPlacementMode placementMode =
						TransactionSnapshot.CraftPlacementMode.oneCraft(player.isCreative());
				RecipeBookMenu.PostPlaceAction placement = ((RecipeBookMenu) menu).handlePlacement(
						placementMode.useMaxItems(),
						placementMode.allowDroppingItemsToClear(),
						holder,
						player.level(),
						player.getInventory()
				);
				List<ItemStack> gridStacks = gridSlots.stream().map(slot -> slot.getItem().copy()).toList();
				CraftingInput input = CraftingInput.ofPositioned(
						craftingMenu.getGridWidth(), craftingMenu.getGridHeight(), gridStacks).input();
				ItemStack output = menu.getSlot(0).getItem().copy();
				if (placement != RecipeBookMenu.PostPlaceAction.NOTHING) {
					reasonCode = craftPlacementFailureReason(placement);
					message = reasonCode.equals("RECIPE_INPUTS_UNAVAILABLE")
							? "The inventory does not hold the ingredients for one craft" + (table ? "" : " in the 2x2 grid")
							: "Vanilla recipe placement did not place one craft";
				} else if (!exactRecipe(input)) {
					reasonCode = "RECIPE_IDENTITY_MISMATCH";
					message = "Placed ingredients do not exactly match the requested recipe";
				} else if (gridStacks.stream().anyMatch(stack -> !stack.isEmpty() && stack.getCount() != 1)) {
					reasonCode = "CRAFT_COUNT_UNSUPPORTED";
					message = "Safe crafting requires exactly one item in every occupied input slot";
				} else if (output.isEmpty() || !craftOutputSatisfiesRequest(output.getCount(), integer(arguments, "count"))) {
					reasonCode = "CRAFT_COUNT_UNSUPPORTED";
					message = "One craft of this recipe makes " + output.getCount()
							+ "; request at most that many and craft again for more";
				} else if (menuCapacity(menu, playerSlotStart(), playerSlotEnd(), output) < output.getCount()) {
					reasonCode = "DESTINATION_FULL";
					message = "Player inventory cannot accept the complete crafting result";
				} else {
					// Click order: every cell of one ingredient while it is carried, then the next ingredient.
					for (Slot slot : gridSlots) {
						if (!slot.hasItem() || pendingGrid.containsKey(slot.index)) continue;
						for (Slot same : gridSlots) {
							if (same.hasItem() && ItemStack.isSameItemSameComponents(same.getItem(), slot.getItem())) {
								pendingGrid.put(same.index, same.getItem().copyWithCount(1));
							}
						}
					}
				}
			} catch (RuntimeException placementFailure) {
				reasonCode = "CRAFT_PLACEMENT_EXCEPTION";
				message = "Recipe placement or precommit validation raised an exception: " + safeMessage(placementFailure);
			} finally {
				beforePlacement.restore(menu);
			}
			if (!placementGuard.mayReportCleanFailure(ownedStacks(player.getInventory(), gridSlots))) {
				pendingGrid.clear();
				return failureAfterPlacement("CRAFT_PLACEMENT_ACCOUNTING_FAILED",
						"Planning the craft changed inventory ownership", placementGuard, gridSlots);
			}
			if (reasonCode != null) return failureAfterPlacement(reasonCode, message, placementGuard, gridSlots);
			if (pendingGrid.isEmpty()) {
				return failureAfterPlacement("RECIPE_INPUTS_UNAVAILABLE", "The recipe placed no ingredients",
						placementGuard, gridSlots);
			}
			return null;
		}

		/** One click: pick up an ingredient, drop one into a grid cell, put the rest back, or take the result. */
		private TickResult fillStep() {
			ItemStack carried = menu.getCarried();
			Map.Entry<Integer, ItemStack> next = pendingGrid.isEmpty() ? null : pendingGrid.entrySet().iterator().next();
			if (carried.isEmpty()) {
				if (next == null) return commit();
				int source = findSource(next.getValue());
				if (source < 0) {
					return failureAfterPlacement("RECIPE_INPUTS_UNAVAILABLE",
							"An ingredient left the inventory while crafting", placementGuard, gridSlots);
				}
				click(source, 0, ContainerInput.PICKUP);
				if (!ItemStack.isSameItemSameComponents(menu.getCarried(), next.getValue())) {
					return failureAfterPlacement("MENU_INPUT_PARTIAL",
							"Picking up the ingredient did not carry it", placementGuard, gridSlots);
				}
				carrySourceSlot = source;
				return TickResult.running();
			}
			if (next != null && ItemStack.isSameItemSameComponents(carried, next.getValue())) {
				int carriedBefore = carried.getCount();
				Slot cell = menu.getSlot(next.getKey());
				// Right click drops exactly one item into the cell.
				click(next.getKey(), 1, ContainerInput.PICKUP);
				if (cell.getItem().getCount() != 1 || !ItemStack.isSameItemSameComponents(cell.getItem(), next.getValue())
						|| menu.getCarried().getCount() != carriedBefore - 1) {
					return failureAfterPlacement("CRAFT_INPUT_REJECTED",
							"The crafting grid did not accept exactly one ingredient", placementGuard, gridSlots);
				}
				pendingGrid.remove(next.getKey());
				return TickResult.running();
			}
			// This ingredient is in every cell that needs it: put the rest back where it came from. A pickup during
			// the pacing can have filled that emptied slot (the play-test ROLLBACK_FAILED after mining), so the rest
			// goes to any slot that takes it, a click per tick, or is dropped at the body when the inventory is full.
			int target = putBackSlot(carrySourceSlot, carried);
			carrySourceSlot = -1;
			if (target >= 0) {
				click(target, 0, ContainerInput.PICKUP);
			} else {
				ItemStack rest = menu.getCarried();
				menu.setCarried(ItemStack.EMPTY);
				returnToInventoryOrDrop(rest);
				menu.broadcastChanges();
			}
			return TickResult.running();
		}

		/** The source slot if it still takes the stack, else a matching unfilled stack, else an empty slot, else -1. */
		private int putBackSlot(int source, ItemStack stack) {
			if (source >= 0 && accepts(menu.getSlot(source), stack, true)) return source;
			for (int index = playerSlotStart(); index <= playerSlotEnd(); index++) {
				if (accepts(menu.getSlot(index), stack, false)) return index;
			}
			for (int index = playerSlotStart(); index <= playerSlotEnd(); index++) {
				Slot slot = menu.getSlot(index);
				if (!slot.hasItem() && slot.mayPlace(stack)) return index;
			}
			return -1;
		}

		private static boolean accepts(Slot slot, ItemStack stack, boolean emptyAllowed) {
			if (!slot.hasItem()) return emptyAllowed && slot.mayPlace(stack);
			return ItemStack.isSameItemSameComponents(slot.getItem(), stack)
					&& slot.getItem().getCount() < slot.getMaxStackSize(slot.getItem());
		}

		/** Vanilla placeItemBackInInventory, with the dropped remainder recorded for ownership accounting. */
		private void returnToInventoryOrDrop(ItemStack stack) {
			if (stack == null || stack.isEmpty()) return;
			int fits = Math.min(stack.getCount(), inventoryRoom(player.getInventory(), stack));
			if (fits > 0) player.getInventory().placeItemBackInInventory(stack.split(fits));
			if (stack.isEmpty()) return;
			droppedAtBody.add(ownedStack(stack.copy()));
			player.drop(stack, false);
		}

		/** Returns the cursor and grid to the inventory (or the body when full) before the menu closes. */
		private void returnMenuItems() {
			if (menu == null) return;
			ItemStack carried = menu.getCarried();
			menu.setCarried(ItemStack.EMPTY);
			returnToInventoryOrDrop(carried);
			if (gridSlots == null || player.containerMenu != menu) return;
			for (Slot slot : gridSlots) {
				ItemStack stack = slot.getItem();
				if (stack.isEmpty()) continue;
				slot.set(ItemStack.EMPTY);
				returnToInventoryOrDrop(stack);
			}
		}

		/** Shift-clicks the result out of the filled grid with exact ingredient, remainder and output accounting. */
		private TickResult commit() {
			List<ItemStack> gridStacks = gridSlots.stream().map(slot -> slot.getItem().copy()).toList();
			CraftingInput.Positioned positionedInput = CraftingInput.ofPositioned(
					craftingMenu.getGridWidth(), craftingMenu.getGridHeight(), gridStacks);
			CraftingInput input = positionedInput.input();
			if (!exactRecipe(input)) {
				return failureAfterPlacement("RECIPE_IDENTITY_MISMATCH",
						"Placed ingredients do not exactly match the requested recipe", placementGuard, gridSlots);
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
				return failureAfterPlacement("CRAFT_REMAINDER_UNSAFE",
						"Recipe remainder layout could not be aligned with the crafting grid", placementGuard, gridSlots);
			}
			Slot resultSlot = menu.getSlot(0);
			ItemStack output = resultSlot.getItem().copy();
			if (output.isEmpty() || !craftOutputSatisfiesRequest(output.getCount(), integer(arguments, "count"))) {
				return failureAfterPlacement("CRAFT_COUNT_UNSUPPORTED",
						"One vanilla craft must produce at least the requested count", placementGuard, gridSlots);
			}
			if (menuCapacity(menu, playerSlotStart(), playerSlotEnd(), output) < output.getCount()) {
				return failureAfterPlacement("DESTINATION_FULL",
						"Player inventory cannot accept the complete crafting result", placementGuard, gridSlots);
			}
			if (!resultSlot.mayPickup(player)) {
				return failureAfterPlacement("CRAFT_RESULT_LOCKED",
						"Vanilla menu denied taking the crafting result", placementGuard, gridSlots);
			}
			TransactionSnapshot.CraftingAccounting accounting;
			try {
				accounting = TransactionSnapshot.CraftingAccounting.plan(
						ownedStacks(player.getInventory(), gridSlots), ownedStacks(gridStacks),
						ownedStacks(remainders), ownedStack(output));
			} catch (IllegalArgumentException invalidAccounting) {
				return failureAfterPlacement("CRAFT_ACCOUNTING_UNSAFE", safeMessage(invalidAccounting),
						placementGuard, gridSlots);
			}
			CraftMenuSnapshot beforeCraft = CraftMenuSnapshot.capture(menu);
			try {
				menu.incrementStateId();
				ItemStack moved = craftCommitter.quickMove(menu, player, 0);
				menu.broadcastChanges();
				TransactionPostcondition.Verdict craftVerdict = accounting.verify(
						ownedStacks(player.getInventory(), gridSlots));
				if (!moved.isEmpty() && moved.getCount() == output.getCount()
						&& craftVerdict instanceof TransactionPostcondition.Verdict.Succeeded) {
					committed = TickResult.succeeded("CRAFT_CONFIRMED",
							"Crafted " + output.getCount() + " " + itemId(output) + " through visible menu clicks");
					phase = Phase.LINGER;
					nextActionTick = ticks + LINGER_TICKS;
					return TickResult.running();
				}
				return rollbackCommittedCraft(
						menu, beforeCraft, placementGuard, gridSlots,
						"CRAFT_POSTCONDITION_FAILED",
						"Craft result did not satisfy exact ingredient, remainder, and output accounting"
				);
			} catch (RuntimeException mutationFailure) {
				return rollbackCommittedCraft(
						menu, beforeCraft, placementGuard, gridSlots,
						"CRAFT_POSTCOMMIT_EXCEPTION",
						"Craft mutation raised an exception: " + safeMessage(mutationFailure)
				);
			}
		}

		private boolean exactRecipe(CraftingInput input) {
			RecipeHolder<CraftingRecipe> exact = player.level().recipeAccess().getRecipeFor(
					RecipeType.CRAFTING, input, player.level(), recipeKey).orElse(null);
			return craftingRecipe.matches(input, player.level()) && exact != null && exact.id().equals(recipeKey);
		}

		/** First player-inventory slot of this menu: after the result and grid (and armor in the inventory). */
		private int playerSlotStart() {
			return table ? 10 : 9;
		}

		/** Last hotbar slot of this menu; the inventory's offhand slot follows it and is never a source. */
		private int playerSlotEnd() {
			return table ? 45 : 44;
		}

		/** A player inventory slot holding the ingredient; the main inventory comes before the hotbar. */
		private int findSource(ItemStack wanted) {
			for (int index = playerSlotStart(); index <= playerSlotEnd(); index++) {
				Slot slot = menu.getSlot(index);
				if (slot.hasItem() && ItemStack.isSameItemSameComponents(slot.getItem(), wanted) && slot.mayPickup(player)) {
					return index;
				}
			}
			return -1;
		}

		/** What a client click does on the server: one vanilla menu click, then the slot changes go out. */
		private void click(int slotIndex, int button, ContainerInput input) {
			menu.incrementStateId();
			menu.clicked(slotIndex, button, input, player);
			menu.broadcastChanges();
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
				restored = placementGuard.mayReportCleanFailure(ownedStacks(player.getInventory(), gridSlots), droppedAtBody);
			} catch (RuntimeException observationFailure) {
				restored = false;
				if (cleanupFailure != null) cleanupFailure.addSuppressed(observationFailure);
				else cleanupFailure = observationFailure;
			}
			if (restored) {
				int dropped = droppedAtBody.stream().mapToInt(TransactionSnapshot.OwnedStack::count).sum();
				return TickResult.failed(reasonCode, message + (dropped == 0
						? "; exact pre-placement ownership was restored"
						: "; ingredients were returned like vanilla, " + dropped + " dropped at the body because the inventory is full"));
			}
			String suffix = cleanupFailure == null ? "" : ": " + safeMessage(cleanupFailure);
			return TickResult.failed("ROLLBACK_FAILED",
					message + "; exact pre-placement ownership restoration was not proved" + suffix);
		}

		private TickResult rollbackCommittedCraft(
			AbstractContainerMenu menu,
			CraftMenuSnapshot beforeCraft,
			TransactionSnapshot.CraftPlacementGuard placementGuard,
			List<Slot> gridSlots,
			String reasonCode,
			String message
		) {
			RuntimeException restoreFailure = null;
			try {
				beforeCraft.restore(menu);
				menu.broadcastChanges();
			} catch (RuntimeException exception) {
				restoreFailure = exception;
			}
			if (restoreFailure != null) {
				try { cleanup(); } catch (RuntimeException cleanupFailure) { restoreFailure.addSuppressed(cleanupFailure); }
				return TickResult.failed("ROLLBACK_FAILED", message + "; exact pre-action inventory restoration failed");
			}
			TickResult restored = failureAfterPlacement(reasonCode, message + "; exact pre-action inventory restored", placementGuard, gridSlots);
			if (restored.reasonCode().equals("ROLLBACK_FAILED")) return restored;
			return restored;
		}

		@Override
		public TickResult committedResult() {
			return phase == Phase.LINGER ? committed : null;
		}

		/**
		 * A dead agent's inventory has already dropped as death loot, and vanilla close handling would put the
		 * cursor and grid back into that inventory, where respawn discards them. Drop them at the body instead.
		 */
		private void dropMenuItemsAtBody() {
			if (menu == null) return;
			ItemStack carried = menu.getCarried();
			menu.setCarried(ItemStack.EMPTY);
			if (!carried.isEmpty()) player.drop(carried, true, false);
			if (gridSlots == null) return;
			for (Slot slot : gridSlots) {
				ItemStack stack = slot.getItem();
				if (stack.isEmpty()) continue;
				slot.set(ItemStack.EMPTY);
				player.drop(stack, true, false);
			}
		}

		@Override
		void beforeCleanup() {
			if (!player.isAlive()) dropMenuItemsAtBody();
			else returnMenuItems();
			if (table) return;
			// Closing the inventory screen: vanilla returns the cursor and any grid items to the inventory.
			AgentInventoryView.close(player);
			if (placementAttempted) player.inventoryMenu.removed(player);
		}
	}

	static record CraftMenuSnapshot(List<ItemStack> slots, ItemStack carried) {
		static CraftMenuSnapshot capture(AbstractContainerMenu menu) {
			List<ItemStack> slots = new ArrayList<>(menu.slots.size());
			for (int index = 0; index < menu.slots.size(); index++) slots.add(menu.getSlot(index).getItem().copy());
			return new CraftMenuSnapshot(List.copyOf(slots), menu.getCarried().copy());
		}

		void restore(AbstractContainerMenu menu) {
			if (menu.slots.size() != slots.size()) throw new IllegalStateException("Craft menu shape changed during transaction");
			for (int index = 0; index < slots.size(); index++) menu.getSlot(index).set(slots.get(index).copy());
			menu.setCarried(carried.copy());
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

	private static TickResult transferUsingMenuInput(ServerPlayer player, AbstractContainerMenu menu,
			int sourceIndex, int destinationIndex, String expectedItemId, int count) {
		if (sourceIndex < 0 || destinationIndex < 0 || sourceIndex >= menu.slots.size()
				|| destinationIndex >= menu.slots.size() || sourceIndex == destinationIndex) {
			return TickResult.failed("INVALID_SLOT", "Source or destination menu slot is invalid");
		}
		Slot source = menu.getSlot(sourceIndex);
		Slot destination = menu.getSlot(destinationIndex);
		ItemStack sourceBefore = source.getItem().copy();
		ItemStack destinationBefore = destination.getItem().copy();
		requireExpectedStack(sourceBefore, expectedItemId, count);
		if (!source.mayPickup(player)) return TickResult.failed("SOURCE_LOCKED", "Vanilla menu denied taking the source stack");
		if (capacity(destination, sourceBefore) < count) return TickResult.failed("DESTINATION_REJECTED", "Destination cannot accept the requested stack");
		if (!menu.getCarried().isEmpty()) return TickResult.failed("TRANSACTION_CONFLICT", "Exact transfer requires an empty cursor");
		if (count != sourceBefore.getCount() && !source.mayPlace(sourceBefore)) {
			return TickResult.failed("RESULT_COUNT_MISMATCH", "Take a complete result stack, or use individual menu inputs to manage the cursor");
		}
		menu.incrementStateId();
		try {
			menu.clicked(sourceIndex, 0, ContainerInput.PICKUP, player);
			if (!ItemStack.matches(sourceBefore, menu.getCarried())) {
				return TickResult.failed("MENU_INPUT_PARTIAL", "Vanilla source input changed the cursor unexpectedly; inspect the menu before continuing");
			}
			if (count == sourceBefore.getCount()) {
				menu.clicked(destinationIndex, 0, ContainerInput.PICKUP, player);
			} else {
				for (int index = 0; index < count; index++) menu.clicked(destinationIndex, 1, ContainerInput.PICKUP, player);
				menu.clicked(sourceIndex, 0, ContainerInput.PICKUP, player);
			}
			ItemStack destinationAfter = destination.getItem();
			if (!menu.getCarried().isEmpty() || !ItemStack.isSameItemSameComponents(sourceBefore, destinationAfter)
					|| destinationAfter.getCount() != destinationBefore.getCount() + count) {
				return TickResult.failed("MENU_INPUT_PARTIAL", "Vanilla input did not produce the requested transfer; inspect current slots and cursor");
			}
			return TickResult.succeeded("TRANSACTION_CONFIRMED", "Requested stack moved with vanilla menu inputs; recipe inputs and costs follow vanilla rules");
		} catch (RuntimeException mutationFailure) {
			return TickResult.failed("MENU_INPUT_PARTIAL", "Vanilla input raised an exception after it began; inspect current menu state: " + safeMessage(mutationFailure));
		} finally {
			menu.broadcastChanges();
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
		String actualMenuId = MenuInspection.menuId(menu);
		if (!actualMenuId.equals(expectedMenuId)) {
			throw new AgentDomainException(
					"MENU_MISMATCH",
					"Expected open menu " + expectedMenuId + " but observed " + actualMenuId
			);
		}
		MenuCapabilityRegistry.requireSupported(actualMenuId);
		if (player.isSpectator()) throw new AgentDomainException("MENU_READ_ONLY", "Spectators cannot change menu contents");
		if (!menu.stillValid(player)) throw new AgentDomainException("MENU_NO_LONGER_VALID", "The open menu is no longer usable by this player");
		return menu;
	}

	private static AbstractContainerMenu requireMenuSession(ServerPlayer player, JsonObject arguments) {
		AbstractContainerMenu menu = requireCurrentSupportedMenu(player, text(arguments, "menuId"));
		if (menu.containerId != integer(arguments, "containerId")) {
			throw new AgentDomainException("MENU_MISMATCH", "The observed menu instance is no longer open");
		}
		if (menu.getStateId() != integer(arguments, "stateId")) {
			throw new AgentDomainException("MENU_STATE_CHANGED", "Menu contents changed after observation; inspect the menu again");
		}
		return menu;
	}

	private static AbstractContainerMenu requireMenuForAction(ServerPlayer player, JsonObject arguments) {
		boolean hasContainer = arguments.has("containerId");
		boolean hasState = arguments.has("stateId");
		if (hasContainer != hasState) throw new AgentDomainException("MENU_SESSION_REQUIRED", "Supply both containerId and stateId together");
		return hasContainer ? requireMenuSession(player, arguments) : requireCurrentSupportedMenu(player, text(arguments, "menuId"));
	}

	static void validateMenuInput(int slot, int button, ContainerInput input, int slotCount) {
		boolean outside = slot == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE;
		if ((!outside && (slot < 0 || slot >= slotCount))
				|| outside && input != ContainerInput.PICKUP && input != ContainerInput.QUICK_CRAFT) {
			throw new AgentDomainException("INVALID_SLOT", "Menu input requires a present slot or a supported outside click");
		}
		boolean valid = switch (input) {
			case PICKUP, QUICK_MOVE, THROW, PICKUP_ALL -> button == 0 || button == 1;
			case SWAP -> button >= 0 && button <= 8 || button == 40;
			case CLONE -> button == 2;
			case QUICK_CRAFT -> button >= 0 && button <= 10
					&& AbstractContainerMenu.getQuickcraftHeader(button) <= 2
					&& AbstractContainerMenu.getQuickcraftType(button) <= 2;
		};
		if (!valid) throw new AgentDomainException("INVALID_BUTTON", "Button is not valid for this vanilla menu input");
	}

	private static void requireBlockPreflight(ServerPlayer player, BlockPos position) {
		ServerLevel level = player.level();
		if (!level.hasChunkAt(position)) {
			throw new AgentDomainException("TARGET_NOT_LOADED", "Target chunk is not loaded");
		}
		if (!player.isWithinBlockInteractionRange(position, 0.0D)) {
			throw new AgentDomainException("TARGET_TOO_FAR", "Target menu is out of reach");
		}
		visibleBlockHit(player, position);
	}

	private static BlockHitResult visibleBlockHit(ServerPlayer player, BlockPos position) {
		BlockHitResult hit = player.level().clip(new ClipContext(player.getEyePosition(), Vec3.atCenterOf(position),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(position)) {
			throw new AgentDomainException("TARGET_NOT_VISIBLE", "The target block is not visible along the interaction ray");
		}
		return hit;
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

	/** Items of this kind the main inventory can still take (a lower bound of what placeItemBackInInventory places). */
	private static int inventoryRoom(Inventory inventory, ItemStack stack) {
		int room = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack held = inventory.getItem(slot);
			if (held.isEmpty()) room += stack.getMaxStackSize();
			else if (ItemStack.isSameItemSameComponents(held, stack)) room += Math.max(0, held.getMaxStackSize() - held.getCount());
		}
		return room;
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
