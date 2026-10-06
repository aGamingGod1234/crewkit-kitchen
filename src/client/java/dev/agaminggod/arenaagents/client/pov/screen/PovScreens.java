package dev.agaminggod.arenaagents.client.pov.screen;

import com.mojang.authlib.GameProfile;
import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.client.pov.PovClientSession;
import dev.agaminggod.arenaagents.client.pov.input.OperatorInputSender;
import dev.agaminggod.arenaagents.pov.AgentPovMenuPayload;
import dev.agaminggod.arenaagents.pov.AgentPovStatePayload;
import dev.agaminggod.arenaagents.pov.OperatorAction;
import dev.agaminggod.arenaagents.pov.PovDeath;
import dev.agaminggod.arenaagents.pov.PovMenu;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors the agent's open menu and death state. Mirrored screens are built over a stand-in inventory,
 * never become LocalPlayer.containerMenu and never reach vanilla container packets.
 */
public final class PovScreens {
	private static final Logger LOGGER = LoggerFactory.getLogger(PovScreens.class);
	private static final int NONE = Integer.MIN_VALUE;
	private static final int INVENTORY_CONTAINER_ID = 0;
	private static final long INVENTORY_REQUEST_NANOS = TimeUnit.SECONDS.toNanos(2);
	private static final UUID PROXY_ID = UUID.nameUUIDFromBytes("arenaagents:pov-mirror".getBytes(StandardCharsets.UTF_8));
	private static Supplier<Inventory> proxyInventory = PovScreens::ownProxyInventory;
	private static RemotePlayer ownProxy;
	private static PovClientSession session;
	private static Screen mirrorScreen;
	private static AbstractContainerMenu mirrorMenu;
	private static int mirrorContainerId = NONE;
	private static MenuType<?> mirrorType;
	private static int dismissedContainerId = NONE;
	private static boolean inventoryRequested;
	private static long inventoryRequestedAt;
	private static AgentPovMenuPayload lastMenu;
	private static boolean registered;

	private PovScreens() {
	}

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen != mirrorScreen) return;
			// Spectators look but never click; scrolling would select bundle items in the operator's own menu.
			ScreenMouseEvents.allowMouseClick(screen).register((target, event) -> PovClient.isTakeover());
			ScreenMouseEvents.allowMouseScroll(screen).register((target, x, y, horizontal, vertical) -> false);
		});
	}

	/** Lets integration share the HUD stand-in's inventory; the default is a private unregistered RemotePlayer. */
	public static void setProxyInventory(Supplier<Inventory> supplier) {
		proxyInventory = supplier == null ? PovScreens::ownProxyInventory : supplier;
	}

	public static void onState(PovClientSession session, AgentPovStatePayload state) {
		Minecraft client = Minecraft.getInstance();
		if (session == null || state == null || client.player == null || client.level == null) return;
		adoptSession(session);
		OperatorInputSender.observeServerSlot(state.inventory().selectedSlot());
		syncMirror(client);
		PovDeath death = state.death().orElse(null);
		if (death != null) {
			showDeath(client, session, death);
			return;
		}
		if (client.screen instanceof AgentPovDeathScreen) client.setScreen(null);
		PovMenu menu = state.menu().orElse(null);
		if (menu == null) {
			// The agent has no screen open: close whichever mirror is showing, including its inventory.
			dismissedContainerId = NONE;
			if (mirrorScreen != null) closeMirrorLocally(client);
			return;
		}
		if (menu.containerId() != dismissedContainerId) dismissedContainerId = NONE;
		MenuType<?> type = menu.menuType().orElse(null);
		if (type == null) {
			// The agent's own inventory screen: opened by the takeover operator (E) or by the agent to craft in
			// its 2x2 grid. Contents arrive through onMenu right after this state.
			if (mirrorType != null) closeMirrorLocally(client);
			if (mirrorScreen == null && menu.containerId() != dismissedContainerId && screenFree(client)) {
				openInventory(client);
			}
			return;
		}
		if (mirrorScreen != null && mirrorType == type && mirrorContainerId == menu.containerId()) return;
		// A screen the operator opened (chat, pause) is never replaced; the next state retries.
		if (menu.containerId() == dismissedContainerId || !screenFree(client)) return;
		openContainer(client, type, menu);
	}

	public static void onMenu(PovClientSession session, AgentPovMenuPayload menu) {
		Minecraft client = Minecraft.getInstance();
		if (session == null || menu == null || client.player == null || menu.sessionId() != session.sessionId()) return;
		adoptSession(session);
		syncMirror(client);
		lastMenu = menu;
		if (mirrorScreen == null && menu.containerId() == INVENTORY_CONTAINER_ID && inventoryRequestPending()
				&& screenFree(client) && !(client.screen instanceof AgentPovDeathScreen)) {
			openInventory(client);
		}
		if (mirrorMenu != null && mirrorContainerId == menu.containerId()) PovContainerScreens.fill(mirrorMenu, menu);
	}

	public static void closeAll() {
		Minecraft client = Minecraft.getInstance();
		boolean povScreen = isPovScreen(client.screen);
		clearMirror();
		dismissedContainerId = NONE;
		inventoryRequested = false;
		lastMenu = null;
		session = null;
		if (povScreen) client.setScreen(null);
	}

	public static boolean isPovScreen(Screen screen) {
		return screen != null && (screen == mirrorScreen || screen instanceof AgentPovDeathScreen);
	}

	/** Takeover E press: the mirrored inventory opens when the agent's inventory contents arrive. */
	public static void requestInventory() {
		inventoryRequested = true;
		inventoryRequestedAt = System.nanoTime();
		if (dismissedContainerId == INVENTORY_CONTAINER_ID) dismissedContainerId = NONE;
	}

	/** LocalPlayer.closeContainer for a mirrored screen: close locally, tell the server only in takeover. */
	public static boolean interceptCloseContainer(Minecraft client) {
		Screen screen = client.screen;
		if (screen == null || screen != mirrorScreen) return false;
		int containerId = mirrorContainerId;
		clearMirror();
		dismissedContainerId = containerId;
		if (PovClient.isTakeover()) OperatorInputSender.sendAction(OperatorAction.CLOSE_MENU, containerId, 0, 0);
		client.setScreen(null);
		return true;
	}

	public static boolean interceptContainerInput(int containerId, int slot, int button, ContainerInput input) {
		Screen screen = Minecraft.getInstance().screen;
		if (!isPovScreen(screen)) return false;
		if (screen == mirrorScreen && containerId == mirrorContainerId && PovClient.isTakeover()) {
			OperatorInputSender.sendAction(OperatorAction.MENU_CLICK, slot, button, input.ordinal());
		}
		return true;
	}

	public static boolean interceptButtonClick(int containerId, int buttonId) {
		Screen screen = Minecraft.getInstance().screen;
		if (!isPovScreen(screen)) return false;
		if (screen == mirrorScreen && containerId == mirrorContainerId && PovClient.isTakeover()) {
			OperatorInputSender.sendAction(OperatorAction.MENU_BUTTON, buttonId, 0, 0);
		}
		return true;
	}

	/** Recipe placement and crafter slot toggles are not relayed in v1; they are dropped for mirrored screens. */
	public static boolean blocksVanillaMenuPackets() {
		return isPovScreen(Minecraft.getInstance().screen);
	}

	/** The mirrored inventory shows the agent's body instead of the operator's; null hides the model. */
	public static LivingEntity displayEntity(Screen screen, LivingEntity fallback) {
		if (screen == null || screen != mirrorScreen) return fallback;
		ClientLevel level = Minecraft.getInstance().level;
		PovClientSession current = session;
		return level == null || current == null ? null : level.getPlayerByUUID(current.agentUuid());
	}

	private static void adoptSession(PovClientSession next) {
		if (session != null && session.sessionId() == next.sessionId()) {
			session = next;
			return;
		}
		if (session != null) closeAll();
		session = next;
	}

	private static void showDeath(Minecraft client, PovClientSession current, PovDeath death) {
		if (client.screen instanceof AgentPovDeathScreen screen) {
			screen.update(death, current.takeover());
			return;
		}
		if (!screenFree(client)) return;
		clearMirror();
		client.setScreen(new AgentPovDeathScreen(current.agentName(), death, current.takeover()));
	}

	private static void openContainer(Minecraft client, MenuType<?> type, PovMenu menu) {
		Inventory inventory = proxyInventory.get();
		if (inventory == null) return;
		Screen screen = PovContainerScreens.create(type, menu.containerId(), inventory, menu.title());
		if (!(screen instanceof MenuAccess<?> access)) {
			LOGGER.warn("No mirrored screen for agent menu type {}", type);
			dismissedContainerId = menu.containerId();
			return;
		}
		setMirror(screen, access.getMenu(), menu.containerId(), type);
		client.setScreen(screen);
		AgentPovMenuPayload contents = lastMenu;
		if (contents != null && contents.containerId() == menu.containerId() && mirrorMenu != null) {
			PovContainerScreens.fill(mirrorMenu, contents);
		}
	}

	private static void openInventory(Minecraft client) {
		Inventory inventory = proxyInventory.get();
		if (inventory == null) return;
		Player owner = inventory.player;
		InventoryScreen screen = new InventoryScreen(owner);
		inventoryRequested = false;
		setMirror(screen, owner.inventoryMenu, INVENTORY_CONTAINER_ID, null);
		client.setScreen(screen);
	}

	private static boolean inventoryRequestPending() {
		if (inventoryRequested && System.nanoTime() - inventoryRequestedAt > INVENTORY_REQUEST_NANOS) inventoryRequested = false;
		return inventoryRequested;
	}

	private static boolean screenFree(Minecraft client) {
		return client.screen == null || isPovScreen(client.screen);
	}

	private static void setMirror(Screen screen, AbstractContainerMenu menu, int containerId, MenuType<?> type) {
		// Set before setScreen so AFTER_INIT recognises the screen.
		mirrorScreen = screen;
		mirrorMenu = menu;
		mirrorContainerId = containerId;
		mirrorType = type;
	}

	/** Forget a mirror that something else (a vanilla screen, a server close packet) already replaced. */
	private static void syncMirror(Minecraft client) {
		if (mirrorScreen != null && client.screen != mirrorScreen) clearMirror();
	}

	private static void closeMirrorLocally(Minecraft client) {
		Screen screen = mirrorScreen;
		clearMirror();
		if (screen != null && client.screen == screen) client.setScreen(null);
	}

	private static void clearMirror() {
		mirrorScreen = null;
		mirrorMenu = null;
		mirrorContainerId = NONE;
		mirrorType = null;
	}

	private static Inventory ownProxyInventory() {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return null;
		if (ownProxy == null || ownProxy.level() != level) {
			// Never added to the level; it only owns the slots the mirrored menus write into.
			ownProxy = new RemotePlayer(level, new GameProfile(PROXY_ID, "PovMirror"));
		}
		return ownProxy.getInventory();
	}
}
