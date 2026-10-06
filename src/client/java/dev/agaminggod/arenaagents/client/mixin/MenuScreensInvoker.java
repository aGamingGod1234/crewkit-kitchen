package dev.agaminggod.arenaagents.client.mixin;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** MenuScreens.create assigns LocalPlayer.containerMenu; mirrored screens need only the constructor lookup. */
@Mixin(MenuScreens.class)
public interface MenuScreensInvoker {
	@Invoker("getConstructor")
	static <T extends AbstractContainerMenu> MenuScreens.ScreenConstructor<T, ?> arenaagents$getConstructor(MenuType<T> type) {
		throw new AssertionError();
	}
}
