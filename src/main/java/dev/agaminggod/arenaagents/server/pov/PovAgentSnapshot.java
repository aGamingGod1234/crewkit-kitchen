package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.mixin.AbstractContainerMenuAccessor;
import dev.agaminggod.arenaagents.pov.AgentPovPosePayload;
import dev.agaminggod.arenaagents.pov.PovInventory;
import dev.agaminggod.arenaagents.pov.PovMenu;
import dev.agaminggod.arenaagents.pov.PovVitals;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.Nameable;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

/**
 * Everything the POV payloads need from one agent at one tick. Stacks are private copies (never live inventory
 * references) so a snapshot can be kept as the "last sent" baseline and diffed with {@link ItemStack#matches}.
 */
public record PovAgentSnapshot(
		int entityId,
		ResourceKey<Level> dimension,
		PovVitals vitals,
		PovInventory inventory,
		List<MobEffectInstance> effects,
		Optional<PovMenu> menu,
		Optional<MenuContents> menuContents,
		Pose pose
) {
	public static final int MAX_EFFECTS = 32;
	public static final int MAX_MENU_SLOTS = 256;
	public static final int MAX_DATA_SLOTS = 32;
	public static final int HOTBAR_SIZE = 9;
	public static final int ARMOR_SIZE = 4;
	private static final int TICKS_PER_SECOND = 20;
	private static final Comparator<MobEffectInstance> EFFECT_ORDER =
			Comparator.comparing(effect -> effect.getEffect().getRegisteredName());
	private static final Map<MenuType<?>, String> MENU_TITLE_KEYS = Map.ofEntries(
			Map.entry(MenuType.CRAFTING, "container.crafting"),
			Map.entry(MenuType.ANVIL, "container.repair"),
			Map.entry(MenuType.ENCHANTMENT, "container.enchant"),
			Map.entry(MenuType.GRINDSTONE, "container.grindstone_title"),
			Map.entry(MenuType.LOOM, "container.loom"),
			Map.entry(MenuType.SMITHING, "container.upgrade"),
			Map.entry(MenuType.STONECUTTER, "container.stonecutter"),
			Map.entry(MenuType.CARTOGRAPHY_TABLE, "container.cartography_table"),
			Map.entry(MenuType.BEACON, "container.beacon"),
			Map.entry(MenuType.LECTERN, "container.lectern"),
			Map.entry(MenuType.CRAFTER_3x3, "container.crafter"),
			Map.entry(MenuType.MERCHANT, "merchant.trades")
	);

	public PovAgentSnapshot {
		Objects.requireNonNull(dimension, "dimension must not be null");
		Objects.requireNonNull(vitals, "vitals must not be null");
		Objects.requireNonNull(inventory, "inventory must not be null");
		effects = List.copyOf(Objects.requireNonNull(effects, "effects must not be null"));
		if (effects.size() > MAX_EFFECTS) throw new IllegalArgumentException("at most " + MAX_EFFECTS + " effects");
		Objects.requireNonNull(menu, "menu must not be null");
		Objects.requireNonNull(menuContents, "menuContents must not be null");
		Objects.requireNonNull(pose, "pose must not be null");
		if (menu.isPresent() != menuContents.isPresent()
				|| menu.isPresent() && menu.get().containerId() != menuContents.get().containerId()) {
			throw new IllegalArgumentException("menu descriptor and contents must describe the same container");
		}
	}

	/** Look, flags and the body's post-physics position (feet), which the takeover camera follows. */
	public record Pose(float yaw, float pitch, float attackStrength, int flags, double x, double y, double z) {
		public Pose {
			if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(attackStrength)
					|| !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
				throw new IllegalArgumentException("pose values must be finite");
			}
		}

		public boolean dead() {
			return (flags & AgentPovPosePayload.FLAG_DEAD) != 0;
		}

		public Pose asDead() {
			return new Pose(yaw, pitch, attackStrength, flags | AgentPovPosePayload.FLAG_DEAD, x, y, z);
		}
	}

	public record MenuContents(int containerId, int stateId, List<ItemStack> slots, ItemStack carried, List<Integer> dataSlots) {
		public MenuContents {
			slots = List.copyOf(Objects.requireNonNull(slots, "slots must not be null"));
			Objects.requireNonNull(carried, "carried must not be null");
			dataSlots = List.copyOf(Objects.requireNonNull(dataSlots, "dataSlots must not be null"));
			if (slots.size() > MAX_MENU_SLOTS) throw new IllegalArgumentException("at most " + MAX_MENU_SLOTS + " slots");
			if (dataSlots.size() > MAX_DATA_SLOTS) {
				throw new IllegalArgumentException("at most " + MAX_DATA_SLOTS + " data slots");
			}
		}

		public boolean sameAs(MenuContents other) {
			return other != null
					&& containerId == other.containerId
					&& stateId == other.stateId
					&& sameStacks(slots, other.slots)
					&& ItemStack.matches(carried, other.carried)
					&& dataSlots.equals(other.dataSlots);
		}
	}

	public static PovAgentSnapshot capture(ServerPlayer agent, boolean inventoryOpen) {
		Objects.requireNonNull(agent, "agent must not be null");
		Optional<AbstractContainerMenu> visible = visibleMenu(agent, inventoryOpen);
		return new PovAgentSnapshot(
				agent.getId(),
				agent.level().dimension(),
				captureVitals(agent),
				captureInventory(agent.getInventory()),
				captureEffects(agent.getActiveEffects()),
				visible.map(menu -> describeMenu(agent, menu)),
				visible.map(PovAgentSnapshot::captureContents),
				capturePose(agent)
		);
	}

	/** State for an agent that is dead and removed or not spawned: last known inventory, no menu, zero health. */
	public static PovAgentSnapshot absent(PovAgentSnapshot lastKnown, ResourceKey<Level> fallbackDimension) {
		if (lastKnown == null) {
			return new PovAgentSnapshot(
					0,
					Objects.requireNonNull(fallbackDimension, "fallbackDimension must not be null"),
					new PovVitals(0.0F, 20.0F, 0.0F, 0, 20, 5.0F, 300, 300, 0, 0.0F, GameType.SURVIVAL),
					PovInventory.empty(),
					List.of(),
					Optional.empty(),
					Optional.empty(),
					new Pose(0.0F, 0.0F, 1.0F, AgentPovPosePayload.FLAG_DEAD, 0.0D, 0.0D, 0.0D)
			);
		}
		PovVitals last = lastKnown.vitals;
		PovVitals dead = new PovVitals(0.0F, last.maxHealth(), 0.0F, last.armor(), last.food(), last.saturation(),
				last.air(), last.maxAir(), last.xpLevel(), last.xpProgress(), last.gameMode());
		return new PovAgentSnapshot(lastKnown.entityId, lastKnown.dimension, dead, lastKnown.inventory,
				List.of(), Optional.empty(), Optional.empty(), lastKnown.pose.asDead());
	}

	/** True when the state payload (vitals, inventory, effects, menu descriptor, body identity) must be resent. */
	public boolean stateDiffers(PovAgentSnapshot previous) {
		return previous == null
				|| entityId != previous.entityId
				|| !dimension.equals(previous.dimension)
				|| !vitals.equals(previous.vitals)
				|| !sameInventory(inventory, previous.inventory)
				|| !sameEffects(effects, previous.effects)
				|| !sameMenuDescriptor(menu, previous.menu);
	}

	/** True when this snapshot has an open menu whose contents the client does not have yet. */
	public boolean menuDiffers(PovAgentSnapshot previous) {
		if (menuContents.isEmpty()) return false;
		return previous == null || previous.menuContents.isEmpty() || !menuContents.get().sameAs(previous.menuContents.get());
	}

	public static boolean sameInventory(PovInventory left, PovInventory right) {
		return left.selectedSlot() == right.selectedSlot()
				&& sameStacks(left.hotbar(), right.hotbar())
				&& ItemStack.matches(left.offhand(), right.offhand())
				&& sameStacks(left.armor(), right.armor());
	}

	public static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
		if (left.size() != right.size()) return false;
		for (int index = 0; index < left.size(); index++) {
			if (!ItemStack.matches(left.get(index), right.get(index))) return false;
		}
		return true;
	}

	/**
	 * Durations are compared in whole seconds so a running effect resends at most once per second instead of every
	 * tick, which keeps the HUD countdown honest without a per-tick payload.
	 */
	public static boolean sameEffects(List<MobEffectInstance> left, List<MobEffectInstance> right) {
		if (left.size() != right.size()) return false;
		for (int index = 0; index < left.size(); index++) {
			MobEffectInstance a = left.get(index);
			MobEffectInstance b = right.get(index);
			if (!a.getEffect().equals(b.getEffect())
					|| a.getAmplifier() != b.getAmplifier()
					|| a.isAmbient() != b.isAmbient()
					|| a.isVisible() != b.isVisible()
					|| a.showIcon() != b.showIcon()
					|| displaySeconds(a) != displaySeconds(b)) {
				return false;
			}
		}
		return true;
	}

	/** The state stateId is informational; contents changes travel in the menu payload, so it is not compared here. */
	public static boolean sameMenuDescriptor(Optional<PovMenu> left, Optional<PovMenu> right) {
		if (left.isEmpty() || right.isEmpty()) return left.isEmpty() == right.isEmpty();
		PovMenu a = left.get();
		PovMenu b = right.get();
		return a.containerId() == b.containerId() && a.menuType().equals(b.menuType()) && a.title().equals(b.title());
	}

	public static int poseFlags(
			boolean sneaking,
			boolean sprinting,
			boolean swimming,
			boolean fallFlying,
			boolean onGround,
			boolean usingItem,
			boolean blocking,
			boolean dead
	) {
		int flags = 0;
		if (sneaking) flags |= AgentPovPosePayload.FLAG_SNEAKING;
		if (sprinting) flags |= AgentPovPosePayload.FLAG_SPRINTING;
		if (swimming) flags |= AgentPovPosePayload.FLAG_SWIMMING;
		if (fallFlying) flags |= AgentPovPosePayload.FLAG_FALL_FLYING;
		if (onGround) flags |= AgentPovPosePayload.FLAG_ON_GROUND;
		if (usingItem) flags |= AgentPovPosePayload.FLAG_USING_ITEM;
		if (blocking) flags |= AgentPovPosePayload.FLAG_BLOCKING;
		if (dead) flags |= AgentPovPosePayload.FLAG_DEAD;
		return flags;
	}

	/** Copy for the wire: drops nested contents and pages that the HUD and mirrored screens never render. */
	public static ItemStack wireStack(ItemStack stack) {
		if (stack.isEmpty()) return ItemStack.EMPTY;
		ItemStack copy = stack.copy();
		resetToPrototype(copy, DataComponents.CONTAINER);
		resetToPrototype(copy, DataComponents.BUNDLE_CONTENTS);
		resetToPrototype(copy, DataComponents.WRITABLE_BOOK_CONTENT);
		resetToPrototype(copy, DataComponents.CONTAINER_LOOT);
		resetToPrototype(copy, DataComponents.BLOCK_ENTITY_DATA);
		resetToPrototype(copy, DataComponents.ENTITY_DATA);
		resetToPrototype(copy, DataComponents.BUCKET_ENTITY_DATA);
		resetToPrototype(copy, DataComponents.BEES);
		resetToPrototype(copy, DataComponents.MAP_DECORATIONS);
		WrittenBookContent book = copy.get(DataComponents.WRITTEN_BOOK_CONTENT);
		if (book != null && !book.pages().isEmpty()) {
			// Title and author stay so the held-item name still reads like the real book.
			copy.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
					book.title(), book.author(), book.generation(), List.of(), book.resolved()));
		}
		return copy;
	}

	public static PovInventory wireInventory(PovInventory inventory) {
		return new PovInventory(
				inventory.selectedSlot(),
				inventory.hotbar().stream().map(PovAgentSnapshot::wireStack).toList(),
				wireStack(inventory.offhand()),
				inventory.armor().stream().map(PovAgentSnapshot::wireStack).toList()
		);
	}

	static int displaySeconds(MobEffectInstance effect) {
		return effect.isInfiniteDuration() ? -1 : effect.getDuration() / TICKS_PER_SECOND;
	}

	private static <T> void resetToPrototype(ItemStack stack, DataComponentType<T> type) {
		T prototype = stack.getPrototype().get(type);
		if (!Objects.equals(stack.get(type), prototype)) stack.set(type, prototype);
	}

	private static PovVitals captureVitals(ServerPlayer agent) {
		return new PovVitals(
				agent.getHealth(),
				agent.getMaxHealth(),
				agent.getAbsorptionAmount(),
				agent.getArmorValue(),
				agent.getFoodData().getFoodLevel(),
				agent.getFoodData().getSaturationLevel(),
				agent.getAirSupply(),
				agent.getMaxAirSupply(),
				agent.experienceLevel,
				agent.experienceProgress,
				agent.gameMode()
		);
	}

	private static PovInventory captureInventory(Inventory inventory) {
		List<ItemStack> hotbar = new ArrayList<>(HOTBAR_SIZE);
		for (int slot = 0; slot < HOTBAR_SIZE; slot++) hotbar.add(inventory.getItem(slot).copy());
		// Inventory slots 36..39 are FEET, LEGS, CHEST, HEAD (Inventory.EQUIPMENT_SLOT_MAPPING).
		List<ItemStack> armor = new ArrayList<>(ARMOR_SIZE);
		for (int slot = 0; slot < ARMOR_SIZE; slot++) armor.add(inventory.getItem(Inventory.INVENTORY_SIZE + slot).copy());
		int selected = Math.clamp(inventory.getSelectedSlot(), 0, HOTBAR_SIZE - 1);
		return new PovInventory(selected, hotbar, inventory.getItem(Inventory.SLOT_OFFHAND).copy(), armor);
	}

	private static List<MobEffectInstance> captureEffects(Collection<MobEffectInstance> active) {
		if (active.isEmpty()) return List.of();
		List<MobEffectInstance> effects = new ArrayList<>(active.size());
		for (MobEffectInstance effect : active) effects.add(new MobEffectInstance(effect));
		effects.sort(EFFECT_ORDER);
		return effects.size() > MAX_EFFECTS ? effects.subList(0, MAX_EFFECTS) : effects;
	}

	private static Pose capturePose(ServerPlayer agent) {
		return new Pose(
				agent.getYRot(),
				agent.getXRot(),
				agent.getAttackStrengthScale(0.5F),
				poseFlags(
						agent.isShiftKeyDown(),
						agent.isSprinting(),
						agent.isSwimming(),
						agent.isFallFlying(),
						agent.onGround(),
						agent.isUsingItem(),
						agent.isBlocking(),
						agent.getHealth() <= 0.0F
				),
				agent.getX(),
				agent.getY(),
				agent.getZ()
		);
	}

	/**
	 * The open container wins over a requested inventory view, as in vanilla. Menus without a MenuType (horse and
	 * other mount inventories) cannot be rebuilt on the client, so they are not mirrored.
	 */
	private static Optional<AbstractContainerMenu> visibleMenu(ServerPlayer agent, boolean inventoryOpen) {
		AbstractContainerMenu open = agent.containerMenu;
		if (open != null && open != agent.inventoryMenu) {
			return menuType(open) == null ? Optional.empty() : Optional.of(open);
		}
		return inventoryOpen ? Optional.of(agent.inventoryMenu) : Optional.empty();
	}

	private static MenuType<?> menuType(AbstractContainerMenu menu) {
		return ((AbstractContainerMenuAccessor) menu).arenaagents$getMenuType();
	}

	private static PovMenu describeMenu(ServerPlayer agent, AbstractContainerMenu menu) {
		if (menu == agent.inventoryMenu) {
			return new PovMenu(menu.containerId, menu.getStateId(), Optional.empty(), Component.translatable("container.inventory"));
		}
		MenuType<?> type = menuType(menu);
		return new PovMenu(menu.containerId, menu.getStateId(), Optional.of(type), menuTitle(agent, menu, type));
	}

	/**
	 * Vanilla only sends the title in ClientboundOpenScreenPacket, which Carpet's fake connection discards, so the
	 * title is rebuilt from the menu: the first non-player container names itself when it is a block entity or entity
	 * (chests, barrels, shulkers, furnaces, hoppers, brewing stands, chest minecarts and boats, custom names
	 * included); double and ender chests use their vanilla keys; container-less work stations use the vanilla title
	 * key for their menu type; anything else falls back to the agent's own name.
	 */
	private static Component menuTitle(ServerPlayer agent, AbstractContainerMenu menu, MenuType<?> type) {
		for (int index = 0; index < menu.slots.size(); index++) {
			Container container = menu.slots.get(index).container;
			if (container instanceof Inventory) continue;
			if (container instanceof CompoundContainer) return Component.translatable("container.chestDouble");
			if (container instanceof PlayerEnderChestContainer) return Component.translatable("container.enderchest");
			if (container instanceof Nameable nameable) return nameable.getDisplayName();
			break;
		}
		String key = MENU_TITLE_KEYS.get(type);
		return key == null ? agent.getName() : Component.translatable(key);
	}

	private static MenuContents captureContents(AbstractContainerMenu menu) {
		int slotCount = Math.min(menu.slots.size(), MAX_MENU_SLOTS);
		List<ItemStack> slots = new ArrayList<>(slotCount);
		for (int index = 0; index < slotCount; index++) slots.add(menu.slots.get(index).getItem().copy());
		List<DataSlot> source = ((AbstractContainerMenuAccessor) menu).arenaagents$getDataSlots();
		int dataCount = Math.min(source.size(), MAX_DATA_SLOTS);
		List<Integer> data = new ArrayList<>(dataCount);
		for (int index = 0; index < dataCount; index++) data.add(source.get(index).get());
		return new MenuContents(menu.containerId, menu.getStateId(), slots, menu.getCarried().copy(), data);
	}
}
