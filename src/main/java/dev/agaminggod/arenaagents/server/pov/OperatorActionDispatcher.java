package dev.agaminggod.arenaagents.server.pov;

import carpet.patches.EntityPlayerMPFake;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.mixin.EntityPlayerActionPackAccessor;
import dev.agaminggod.arenaagents.mixin.ServerGamePacketListenerImplAccessor;
import dev.agaminggod.arenaagents.pov.OperatorActionPayload;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.OfflineAgentPlayers;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import dev.agaminggod.arenaagents.pov.OperatorTextPayload;
import java.util.List;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.HasCustomInventoryScreen;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-shot operator actions run against the agent's own body. Where vanilla has a server handler, the
 * action is relayed into the agent's own connection so vanilla validation runs against the agent; clicks
 * whose outcome decides the next step (attack, use) call the same game-mode methods those handlers use.
 * Movement, chat and commands are never relayed.
 */
public final class OperatorActionDispatcher {
	private static final Logger LOGGER = LoggerFactory.getLogger(OperatorActionDispatcher.class);

	enum Outcome {
		IGNORED, ENTITY_ATTACKED, BLOCK_TARGETED, MISSED, STABBED, USED, NOTHING_USED, PICKED, RELAYED,
		MENU_OPENED, MENU_CLOSED, MENU_CLICKED
	}

	/** A validated container click: slot, mouse button and vanilla click type. */
	record MenuClick(short slot, byte button, ContainerInput input) {
	}

	private OperatorActionDispatcher() {
	}

	static Outcome dispatch(
			ServerPlayer agent,
			OperatorActionPayload action,
			CarpetOperatorBodyController.Keys keys,
			CarpetOperatorBodyController.Frame frame
	) {
		return switch (action.action()) {
			case ATTACK_CLICK -> attackClick(agent, keys);
			case USE_CLICK -> useClick(agent, keys);
			case PICK_BLOCK -> pickBlock(agent, decodeIncludeData(action.a()), keys, frame);
			case DROP_ITEM -> drop(agent, false);
			case DROP_STACK -> drop(agent, true);
			case SWAP_HANDS -> swapHands(agent);
			case OPEN_INVENTORY -> openVehicleInventory(agent);
			case CLOSE_MENU -> closeMenu(agent);
			case MENU_CLICK -> decodeMenuClick(action.a(), action.b(), action.c())
					.map(click -> menuClick(agent, click))
					.orElse(Outcome.IGNORED);
			case MENU_BUTTON -> {
				OptionalInt button = decodeMenuButton(action.a());
				yield button.isPresent() ? menuButton(agent, button.getAsInt()) : Outcome.IGNORED;
			}
			case LEAVE_BED -> leaveBed(agent);
			case SELECT_TRADE -> relay(agent, listener -> listener.handleSelectTrade(new ServerboundSelectTradePacket(action.a())));
			case SET_BEACON -> beaconEffects(action.a(), action.b())
					.map(effects -> relay(agent, listener -> listener.handleSetBeaconPacket(effects)))
					.orElse(Outcome.IGNORED);
			// The container id is the one the operator's screen showed, so vanilla still refuses a stale screen.
			case PLACE_RECIPE -> relay(agent, listener -> listener.handlePlaceRecipe(new ServerboundPlaceRecipePacket(
					action.c(), new RecipeDisplayId(action.a()), action.b() != 0)));
			case RECIPE_BOOK_SETTINGS -> decodeRecipeBookType(action.a())
					.map(type -> relay(agent, listener -> listener.handleRecipeBookChangeSettingsPacket(
							new ServerboundRecipeBookChangeSettingsPacket(type, action.b() != 0, action.c() != 0))))
					.orElse(Outcome.IGNORED);
			case RECIPE_SEEN -> relay(agent, listener -> listener.handleRecipeBookSeenRecipePacket(
					new ServerboundRecipeBookSeenRecipePacket(new RecipeDisplayId(action.a()))));
			// The vanilla packet decoder refuses negative indices other than -1 (no selection).
			case SELECT_BUNDLE_ITEM -> action.b() < -1 ? Outcome.IGNORED
					: relay(agent, listener -> listener.handleBundleItemSelectedPacket(
							new ServerboundSelectBundleItemPacket(action.a(), action.b())));
			case CRAFTER_SLOT -> relay(agent, listener -> listener.handleContainerSlotStateChanged(
					new ServerboundContainerSlotStateChangedPacket(action.a(), action.c(), action.b() != 0)));
			// The controller handles respawn itself because the body is usually absent then.
			case RESPAWN -> Outcome.IGNORED;
		};
	}

	/**
	 * Text typed into a vanilla screen for the agent, rebuilt into the vanilla packet and handled by the agent's own
	 * connection: AnvilMenu, SignBlockEntity and the book handler apply their own checks (open menu, allowed sign
	 * editor and distance, hotbar or off-hand slot, text filtering and length).
	 */
	static Outcome text(ServerPlayer agent, OperatorTextPayload text) {
		List<String> lines = text.lines();
		return switch (text.kind()) {
			case RENAME_ITEM -> relay(agent, listener -> listener.handleRenameItem(new ServerboundRenameItemPacket(lines.get(0))));
			case SIGN_UPDATE -> relay(agent, listener -> listener.handleSignUpdate(new ServerboundSignUpdatePacket(
					text.pos(), text.value() != 0, lines.get(0), lines.get(1), lines.get(2), lines.get(3))));
			case EDIT_BOOK -> relay(agent, listener -> listener.handleEditBook(
					new ServerboundEditBookPacket(text.value(), lines, text.title())));
		};
	}

	/** ServerboundSetCreativeModeSlotPacket for the agent; vanilla checks infinite materials, features, slot and size. */
	static Outcome creativeSlot(ServerPlayer agent, short slot, ItemStack stack) {
		return relay(agent, listener -> listener.handleSetCreativeModeSlot(new ServerboundSetCreativeModeSlotPacket(slot, stack)));
	}

	/** Beacon effects by registry id as ServerboundSetBeaconPacket carries them; an unknown id is refused like a bad packet. */
	static Optional<ServerboundSetBeaconPacket> beaconEffects(int primary, int secondary) {
		Optional<Optional<Holder<MobEffect>>> first = effect(primary);
		Optional<Optional<Holder<MobEffect>>> second = effect(secondary);
		if (first.isEmpty() || second.isEmpty()) return Optional.empty();
		return Optional.of(new ServerboundSetBeaconPacket(first.get(), second.get()));
	}

	private static Optional<Optional<Holder<MobEffect>>> effect(int id) {
		if (id == -1) return Optional.of(Optional.empty());
		if (id < 0) return Optional.empty();
		return BuiltInRegistries.MOB_EFFECT.get(id).map(holder -> Optional.of((Holder<MobEffect>) holder));
	}

	static Optional<RecipeBookType> decodeRecipeBookType(int ordinal) {
		RecipeBookType[] types = RecipeBookType.values();
		return ordinal >= 0 && ordinal < types.length ? Optional.of(types[ordinal]) : Optional.empty();
	}

	private static Outcome relay(ServerPlayer agent, java.util.function.Consumer<ServerGamePacketListenerImpl> handler) {
		prepareBody(agent);
		handler.accept(agent.connection);
		return Outcome.RELAYED;
	}

	/**
	 * Makes relayed packets reach vanilla's handlers. Fake players report a client that is still loading for
	 * about 60 ticks after spawn or respawn (handlers drop actions until then), and Carpet never acknowledges
	 * teleports, which leaves vanilla's pending-teleport position set and rejects use-on-block forever.
	 */
	static void prepareBody(ServerPlayer agent) {
		ServerGamePacketListenerImpl listener = agent.connection;
		if (listener == null) return;
		if (!listener.hasClientLoaded()) listener.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		if (agent instanceof EntityPlayerMPFake) {
			((ServerGamePacketListenerImplAccessor) listener).arenaagents$setAwaitingPositionFromClient(null);
		}
	}

	/** Vanilla Minecraft.handleKeybinds use-key handling for a held key, run once per server tick. */
	static void tickUseKey(ServerPlayer agent, CarpetOperatorBodyController.Keys keys, CarpetOperatorBodyController.Frame frame) {
		switch (keys.useStep(agent.isUsingItem(), frame)) {
			case RELEASE -> agent.releaseUsingItem();
			case START -> startUseItem(agent, keys);
			case NONE -> { }
		}
	}

	/**
	 * LocalPlayer.aiStep: a fresh jump press in the air with a glider equipped asks the server to start gliding. The
	 * held jump key only reaches Carpet's jump action, which never does this, so elytra could not open in a takeover.
	 * The relayed handler runs vanilla's own tryToStartFallFlying checks (airborne, not riding, no levitation, glider).
	 */
	static Outcome jumpPressed(ServerPlayer agent) {
		if (agent.onGround() || agent.isFallFlying() || agent.onClimbable() || agent.getAbilities().flying
				|| agent.isInWater() || agent.isPassenger()) {
			return Outcome.IGNORED;
		}
		prepareBody(agent);
		agent.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(agent, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		return agent.isFallFlying() ? Outcome.RELAYED : Outcome.IGNORED;
	}

	/** LocalPlayer.aiStep creative flight toggle on a jump press; true when the press toggled flight. */
	static boolean toggleFlight(ServerPlayer agent, CarpetOperatorBodyController.Keys keys) {
		Abilities abilities = agent.getAbilities();
		if (!abilities.mayfly || agent.isSpectator()) return false;
		boolean vehicleAllows = agent.getVehicle() == null
				|| agent.getControlledVehicle() instanceof PlayerRideableJumping jumping && jumping.canJump();
		if (!keys.flightTogglePress(!agent.isSwimming() && vehicleAllows)) return false;
		abilities.flying = !abilities.flying;
		if (abilities.flying && agent.onGround()) agent.jumpFromGround();
		agent.onUpdateAbilities();
		return true;
	}

	/** Server-checked one-click melee; a held attack only mines, so entities are hit once per click. */
	static Outcome attackClick(ServerPlayer agent, CarpetOperatorBodyController.Keys keys) {
		if (!keys.attackClickAllowed(agent.isSpectator(), agent.isUsingItem())) return Outcome.IGNORED;
		ItemStack weapon = agent.getMainHandItem();
		if (!weapon.isItemEnabled(agent.level().enabledFeatures()) || agent.cannotAttackWithItem(weapon, 0)) {
			return Outcome.IGNORED;
		}
		agent.resetLastActionTime();
		if (weapon.has(DataComponents.PIERCING_WEAPON)) {
			// Spears never make targeted hits; vanilla sends a stab that the server resolves.
			relayPlayerAction(agent, ServerboundPlayerActionPacket.Action.STAB);
			agent.swing(InteractionHand.MAIN_HAND);
			return Outcome.STABBED;
		}
		HitResult hit = pick(agent);
		if (hit instanceof EntityHitResult entityHit) {
			Entity target = entityHit.getEntity();
			AttackRange range = weapon.get(DataComponents.ATTACK_RANGE);
			boolean reachable = (range == null || range.isInRange(agent, entityHit.getLocation()))
					&& agent.level().getWorldBorder().isWithinBounds(target.blockPosition())
					&& agent.isWithinAttackRange(weapon, target.getBoundingBox(), 0.0D);
			if (reachable) {
				// Player.attack scales damage by the current cooldown before resetting it.
				agent.attack(target);
				agent.resetAttackStrengthTicker();
			}
			agent.swing(InteractionHand.MAIN_HAND);
			return reachable ? Outcome.ENTITY_ATTACKED : Outcome.IGNORED;
		}
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
				&& !agent.level().getBlockState(blockHit.getBlockPos()).isAir()) {
			// Mining belongs to the held attack; the pulse keeps a sub-tick click held long enough to start it.
			// Carpet's 5-tick blockHitDelay after a break is vanilla's destroyDelay, which only throttles a held
			// key: vanilla startDestroyBlock ignores it, so every click may start (or creative-break) at once.
			// Left in place, a 2-tick pulse only counts the delay down and fast clicks were lost.
			((EntityPlayerActionPackAccessor) OfflineAgentPlayers.actions(agent)).arenaagents$setBlockHitDelay(0);
			keys.blockClicked();
			return Outcome.BLOCK_TARGETED;
		}
		keys.missed(agent.isCreative());
		agent.resetAttackStrengthTicker();
		agent.swing(InteractionHand.MAIN_HAND);
		return Outcome.MISSED;
	}

	static Outcome useClick(ServerPlayer agent, CarpetOperatorBodyController.Keys keys) {
		// Vanilla only consumes use clicks while no item is in use.
		if (!keys.useClickAllowed(agent.isUsingItem())) return Outcome.IGNORED;
		keys.useClicked();
		return startUseItem(agent, keys);
	}

	/** Vanilla Minecraft.startUseItem: main hand then off hand, target first, then the item itself. */
	static Outcome startUseItem(ServerPlayer agent, CarpetOperatorBodyController.Keys keys) {
		if (isDestroying(agent)) return Outcome.IGNORED;
		keys.useStarted();
		ServerLevel level = agent.level();
		HitResult hit = pick(agent);
		agent.resetLastActionTime();
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = agent.getItemInHand(hand);
			if (!stack.isItemEnabled(level.enabledFeatures())) return Outcome.NOTHING_USED;
			if (hit instanceof EntityHitResult entityHit) {
				Entity target = entityHit.getEntity();
				if (!level.getWorldBorder().isWithinBounds(target.blockPosition())) return Outcome.NOTHING_USED;
				if (agent.isWithinEntityInteractionRange(target, 0.0D)) {
					Vec3 relative = entityHit.getLocation().subtract(target.getX(), target.getY(), target.getZ());
					if (agent.interactOn(target, hand, relative) instanceof InteractionResult.Success success) {
						swing(agent, hand, success);
						return Outcome.USED;
					}
				}
			} else if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
				InteractionResult result = useOnBlock(agent, level, stack, hand, blockHit);
				if (result instanceof InteractionResult.Success success) {
					swing(agent, hand, success);
					return Outcome.USED;
				}
				if (result instanceof InteractionResult.Fail) return Outcome.NOTHING_USED;
			}
			if (!stack.isEmpty()
					&& agent.gameMode.useItem(agent, level, stack, hand) instanceof InteractionResult.Success success) {
				swing(agent, hand, success);
				return Outcome.USED;
			}
		}
		return Outcome.NOTHING_USED;
	}

	static Outcome pickBlock(
			ServerPlayer agent,
			boolean includeData,
			CarpetOperatorBodyController.Keys keys,
			CarpetOperatorBodyController.Frame frame
	) {
		HitResult hit = pick(agent);
		int before = agent.getInventory().getSelectedSlot();
		prepareBody(agent);
		if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
			agent.connection.handlePickItemFromBlock(new ServerboundPickItemFromBlockPacket(blockHit.getBlockPos(), includeData));
		} else if (hit instanceof EntityHitResult entityHit) {
			agent.connection.handlePickItemFromEntity(new ServerboundPickItemFromEntityPacket(entityHit.getEntity().getId(), includeData));
		} else {
			return Outcome.IGNORED;
		}
		keys.serverSelectedSlot(before, agent.getInventory().getSelectedSlot(), frame.selectedSlot());
		return Outcome.PICKED;
	}

	static Outcome drop(ServerPlayer agent, boolean fullStack) {
		if (agent.isSpectator()) return Outcome.IGNORED;
		boolean holding = !agent.getInventory().getSelectedItem().isEmpty();
		relayPlayerAction(agent, fullStack
				? ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS
				: ServerboundPlayerActionPacket.Action.DROP_ITEM);
		// The vanilla client swings after a drop that removed something.
		if (holding) agent.swing(InteractionHand.MAIN_HAND);
		return Outcome.RELAYED;
	}

	static Outcome swapHands(ServerPlayer agent) {
		if (agent.isSpectator()) return Outcome.IGNORED;
		relayPlayerAction(agent, ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND);
		return Outcome.RELAYED;
	}

	/**
	 * The player inventory screen is client-only, so there is nothing to do for it here. While riding a
	 * vehicle with its own inventory (horse, chest boat) vanilla asks the server to open that instead.
	 */
	static Outcome openVehicleInventory(ServerPlayer agent) {
		if (!(agent.getVehicle() instanceof HasCustomInventoryScreen vehicle)) return Outcome.IGNORED;
		agent.resetLastActionTime();
		vehicle.openCustomInventoryScreen(agent);
		return Outcome.MENU_OPENED;
	}

	/**
	 * Vanilla InBedChatScreen sends STOP_SLEEPING; relayed so the handler's own checks run. The handler then waits for
	 * the client to confirm its position, which a Carpet body never does, so that wait is cleared again.
	 */
	static Outcome leaveBed(ServerPlayer agent) {
		if (!agent.isSleeping()) return Outcome.IGNORED;
		prepareBody(agent);
		agent.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(agent, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
		prepareBody(agent);
		return Outcome.RELAYED;
	}

	static Outcome closeMenu(ServerPlayer agent) {
		agent.closeContainer();
		return Outcome.MENU_CLOSED;
	}

	static Outcome menuClick(ServerPlayer agent, MenuClick click) {
		AbstractContainerMenu menu = agent.containerMenu;
		prepareBody(agent);
		// The menu's own state id means no forced resync; vanilla still checks validity, slot and click rules.
		agent.connection.handleContainerClick(new ServerboundContainerClickPacket(
				menu.containerId, menu.getStateId(), click.slot(), click.button(), click.input(),
				Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
		return Outcome.MENU_CLICKED;
	}

	static Outcome menuButton(ServerPlayer agent, int button) {
		prepareBody(agent);
		agent.connection.handleContainerButtonClick(
				new ServerboundContainerButtonClickPacket(agent.containerMenu.containerId, button));
		return Outcome.MENU_CLICKED;
	}

	/** Requests the manager's verified vanilla respawn, only while the agent record is dead. */
	static boolean respawn(MinecraftServer server, AgentId agentId) {
		CodexAgentManager manager = CodexAgentManager.get(server);
		try {
			if (manager.registry().require(agentId).state() != AgentLifecycleState.DEAD) return false;
			manager.requestRespawn(agentId.toString());
			return true;
		} catch (AgentDomainException rejected) {
			LOGGER.debug("Operator respawn for agent {} was rejected: {}", agentId, rejected.getMessage());
			return false;
		}
	}

	static Optional<MenuClick> decodeMenuClick(int slot, int button, int clickType) {
		ContainerInput[] inputs = ContainerInput.values();
		if (slot < Short.MIN_VALUE || slot > Short.MAX_VALUE) return Optional.empty();
		if (button < 0 || button > Byte.MAX_VALUE) return Optional.empty();
		if (clickType < 0 || clickType >= inputs.length) return Optional.empty();
		return Optional.of(new MenuClick((short) slot, (byte) button, inputs[clickType]));
	}

	static OptionalInt decodeMenuButton(int button) {
		return button >= 0 ? OptionalInt.of(button) : OptionalInt.empty();
	}

	/** PICK_BLOCK a != 0 asks for block data like vanilla's Ctrl+pick. */
	static boolean decodeIncludeData(int value) {
		return value != 0;
	}

	/**
	 * Server copy of vanilla LocalPlayer.raycastHitResult for the agent's current look: the held item's
	 * attack range first, then blocks within block reach and entities within entity reach.
	 */
	static HitResult pick(ServerPlayer agent) {
		float partialTick = 1.0F;
		double blockRange = agent.blockInteractionRange();
		AttackRange range = agent.getActiveItem().get(DataComponents.ATTACK_RANGE);
		HitResult hit = null;
		if (range != null) {
			hit = range.getClosesetHit(agent, partialTick, EntitySelector.CAN_BE_PICKED);
			if (hit instanceof BlockHitResult) hit = filter(hit, agent.getEyePosition(partialTick), blockRange);
		}
		if (hit == null || hit.getType() == HitResult.Type.MISS) {
			hit = pick(agent, blockRange, agent.entityInteractionRange(), partialTick);
		}
		return hit;
	}

	private static HitResult pick(Entity viewer, double blockRange, double entityRange, float partialTick) {
		double reach = Math.max(blockRange, entityRange);
		double reachSquared = Mth.square(reach);
		Vec3 eye = viewer.getEyePosition(partialTick);
		HitResult block = viewer.pick(reach, partialTick, false);
		double blockDistanceSquared = block.getLocation().distanceToSqr(eye);
		if (block.getType() != HitResult.Type.MISS) {
			reachSquared = blockDistanceSquared;
			reach = Math.sqrt(reachSquared);
		}
		Vec3 view = viewer.getViewVector(partialTick);
		Vec3 end = eye.add(view.x * reach, view.y * reach, view.z * reach);
		AABB sweep = viewer.getBoundingBox().expandTowards(view.scale(reach)).inflate(1.0D, 1.0D, 1.0D);
		Predicate<Entity> pickable = EntitySelector.CAN_BE_PICKED;
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(viewer, eye, end, sweep, pickable, reachSquared);
		if (entity != null && entity.getLocation().distanceToSqr(eye) < blockDistanceSquared) {
			return filter(entity, eye, entityRange);
		}
		return filter(block, eye, blockRange);
	}

	private static HitResult filter(HitResult hit, Vec3 eye, double range) {
		Vec3 location = hit.getLocation();
		if (location.closerThan(eye, range)) return hit;
		Direction direction = Direction.getApproximateNearest(location.x - eye.x, location.y - eye.y, location.z - eye.z);
		return BlockHitResult.miss(location, direction, BlockPos.containing(location));
	}

	private static InteractionResult useOnBlock(
			ServerPlayer agent,
			ServerLevel level,
			ItemStack stack,
			InteractionHand hand,
			BlockHitResult hit
	) {
		// The checks ServerGamePacketListenerImpl.handleUseItemOn makes before calling the game mode.
		BlockPos position = hit.getBlockPos();
		if (!agent.isWithinBlockInteractionRange(position, 1.0D)) return InteractionResult.PASS;
		if (position.getY() > level.getMaxY() || position.getY() < level.getMinY()) return InteractionResult.PASS;
		if (!level.mayInteract(agent, position)) return InteractionResult.PASS;
		return agent.gameMode.useItemOn(agent, level, stack, hand, hit);
	}

	private static void swing(ServerPlayer agent, InteractionHand hand, InteractionResult.Success success) {
		// Vanilla swings on the client (CLIENT) or the server (SERVER); both reach watchers either way.
		if (success.swingSource() != InteractionResult.SwingSource.NONE) agent.swing(hand, true);
	}

	private static boolean isDestroying(ServerPlayer agent) {
		// Vanilla refuses to start using an item while a block is being broken.
		return ((EntityPlayerActionPackAccessor) OfflineAgentPlayers.actions(agent)).arenaagents$getCurrentBlock() != null;
	}

	private static void relayPlayerAction(ServerPlayer agent, ServerboundPlayerActionPacket.Action action) {
		prepareBody(agent);
		agent.connection.handlePlayerAction(new ServerboundPlayerActionPacket(action, BlockPos.ZERO, Direction.DOWN));
	}
}
