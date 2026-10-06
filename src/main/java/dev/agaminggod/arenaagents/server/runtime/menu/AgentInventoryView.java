package dev.agaminggod.arenaagents.server.runtime.menu;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * Agents whose own inventory screen is open because the agent itself is using it (crafting in the 2x2 grid).
 * Vanilla servers never learn when a client opens its inventory, so the action marks it here and the POV
 * publisher mirrors the inventory screen to spectators while the mark is set. Server thread only.
 */
public final class AgentInventoryView {
	private static final Set<ServerPlayer> OPEN = Collections.newSetFromMap(new WeakHashMap<>());

	private AgentInventoryView() {
	}

	public static synchronized void open(ServerPlayer player) {
		OPEN.add(Objects.requireNonNull(player, "player must not be null"));
	}

	public static synchronized void close(ServerPlayer player) {
		if (player != null) OPEN.remove(player);
	}

	/** True only while the agent's inventory menu is also its current menu; an opened container replaces it. */
	public static synchronized boolean isOpen(ServerPlayer player) {
		return player != null && OPEN.contains(player) && player.containerMenu == player.inventoryMenu;
	}
}
