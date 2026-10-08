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
	LEAVE_BED,
	/** MerchantScreen trade button: {@code a} = offer index (ServerboundSelectTradePacket). */
	SELECT_TRADE,
	/** Beacon confirm: {@code a} / {@code b} = primary / secondary MobEffect registry id, -1 for none. */
	SET_BEACON,
	/** Recipe book click: {@code a} = RecipeDisplayId index, {@code b} != 0 to place as many as possible, {@code c} = container id. */
	PLACE_RECIPE,
	/** Recipe book toggles: {@code a} = RecipeBookType ordinal, {@code b} = open, {@code c} = filtering. */
	RECIPE_BOOK_SETTINGS,
	/** A highlighted recipe was looked at: {@code a} = RecipeDisplayId index. */
	RECIPE_SEEN,
	/** Bundle scroll in a menu: {@code a} = menu slot, {@code b} = selected item index (-1 for none). */
	SELECT_BUNDLE_ITEM,
	/** Crafter slot toggle: {@code a} = menu slot, {@code b} != 0 to enable, {@code c} = container id. */
	CRAFTER_SLOT
}
