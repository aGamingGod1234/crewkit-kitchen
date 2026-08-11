package dev.agaminggod.arenaagents.server.runtime;

public enum DeferredInteraction {
	OPEN_CLOSE_DOOR,
	PICK_UP_ITEM,
	DROP_ITEM,
	OPEN_CONTAINER,
	TRANSFER_CONTAINER_ITEM,
	CRAFT_RECIPE,
	USE_FURNACE,
	RESPAWN,
	CHUNK_TICKET;

	public String reasonCode() {
		return "INTERACTION_DEFERRED_" + name();
	}
}
