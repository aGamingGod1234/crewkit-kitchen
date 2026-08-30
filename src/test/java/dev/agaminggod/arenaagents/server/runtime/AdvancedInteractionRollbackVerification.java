package dev.agaminggod.arenaagents.server.runtime;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.transaction.ServerTransactionAdapter;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.Holder;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
			if (failure != null) throw failure;
		}
		return 12;
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
		public ItemStack getMainHandItem() {
			return failMainHandVerification ? ItemStack.EMPTY : super.getMainHandItem();
		}

		@Override
		public ItemEntity drop(ItemStack stack, boolean randomDirection) {
			if (throwOnDrop) throw new IllegalStateException("injected drop failure after inventory debit");
			return super.drop(stack, randomDirection);
		}
	}
}
