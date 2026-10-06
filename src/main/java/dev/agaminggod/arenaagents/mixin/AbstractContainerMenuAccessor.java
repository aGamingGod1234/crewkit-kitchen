package dev.agaminggod.arenaagents.mixin;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads menu internals that the agent POV mirror needs without throwing for typeless menus. */
@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuAccessor {
	@Accessor("dataSlots")
	List<DataSlot> arenaagents$getDataSlots();

	/** Null for the player inventory and other menus that vanilla never opens by type (getType() throws for them). */
	@Accessor("menuType")
	MenuType<?> arenaagents$getMenuType();
}
