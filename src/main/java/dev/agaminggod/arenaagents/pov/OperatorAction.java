package dev.agaminggod.arenaagents.pov;

/**
 * One-shot takeover inputs carried by {@link OperatorActionPayload}. {@link #MENU_CLICK} uses {@code a} = slot,
 * {@code b} = button and {@code c} = {@link net.minecraft.world.inventory.ContainerInput} ordinal;
 * {@link #MENU_BUTTON} uses {@code a} = button id; every other action ignores {@code a}, {@code b} and {@code c}.
 * Encoded by ordinal, so new actions must be appended.
 */
public enum OperatorAction {
	ATTACK_CLICK,
	USE_CLICK,
	PICK_BLOCK,
	DROP_ITEM,
	DROP_STACK,
	SWAP_HANDS,
	OPEN_INVENTORY,
	CLOSE_MENU,
	MENU_CLICK,
	MENU_BUTTON,
	RESPAWN,
	/** The in-bed screen's Leave Bed button (or Escape): vanilla STOP_SLEEPING for the agent. */
	LEAVE_BED
}
