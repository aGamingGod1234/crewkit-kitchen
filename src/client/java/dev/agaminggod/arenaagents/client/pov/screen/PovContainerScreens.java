package dev.agaminggod.arenaagents.client.pov.screen;

import dev.agaminggod.arenaagents.client.mixin.MenuScreensInvoker;
import dev.agaminggod.arenaagents.pov.AgentPovMenuPayload;
import java.util.List;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/** Builds vanilla container screens over a stand-in inventory; LocalPlayer.containerMenu is never touched. */
final class PovContainerScreens {
	private PovContainerScreens() {
	}

	/** Same screen MenuScreens.create would build, minus the containerMenu assignment and setScreen. */
	static <T extends AbstractContainerMenu> Screen create(MenuType<T> type, int containerId, Inventory inventory, Component title) {
		MenuScreens.ScreenConstructor<T, ?> constructor = MenuScreensInvoker.arenaagents$getConstructor(type);
		if (constructor == null) return null;
		return constructor.create(type.create(containerId, inventory), inventory, title);
	}

	static void fill(AbstractContainerMenu menu, AgentPovMenuPayload payload) {
		List<ItemStack> slots = payload.slots();
		int count = Math.min(slots.size(), menu.slots.size());
		menu.initializeContents(payload.stateId(), count == slots.size() ? slots : slots.subList(0, count), payload.carried());
		List<Integer> data = payload.dataSlots();
		try {
			for (int index = 0; index < data.size(); index++) menu.setData(index, data.get(index));
		} catch (IndexOutOfBoundsException ignored) {
			// The client menu exposes no data slot count; extra server values are simply not shown.
		}
	}
}
