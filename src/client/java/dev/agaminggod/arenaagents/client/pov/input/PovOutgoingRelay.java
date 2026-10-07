package dev.agaminggod.arenaagents.client.pov.input;

import dev.agaminggod.arenaagents.client.pov.PovClient;
import dev.agaminggod.arenaagents.client.pov.screen.PovScreens;
import dev.agaminggod.arenaagents.pov.OperatorAction;
import dev.agaminggod.arenaagents.pov.OperatorTextPayload;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.network.protocol.game.ServerboundRecipeBookSeenRecipePacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;

/**
 * Vanilla screens send these packets for the local player. During a takeover the screen belongs to the agent's
 * body (a mirrored menu, the sign editor or book the agent opened, the recipe book showing the agent's book), so
 * the packet is turned into an operator payload and the server hands the same vanilla packet to the agent's
 * connection. While spectating, the menu packets of a mirrored screen are dropped: a spectator only looks.
 */
public final class PovOutgoingRelay {
	private PovOutgoingRelay() {
	}

	/** True when the packet was relayed or dropped and must not reach the operator's own connection. */
	public static boolean intercept(Packet<?> packet) {
		if (!PovClient.isActive() || !relayable(packet)) return false;
		if (!PovClient.isTakeover()) return PovScreens.blocksVanillaMenuPackets() && menuPacket(packet);
		switch (packet) {
			case ServerboundRenameItemPacket rename -> OperatorInputSender.sendText((session, sequence) ->
					OperatorTextPayload.rename(session, sequence, clamp(rename.getName(), OperatorTextPayload.MAX_RENAME_LENGTH)));
			case ServerboundSignUpdatePacket sign -> OperatorInputSender.sendText((session, sequence) ->
					OperatorTextPayload.sign(session, sequence, sign.getPos(), sign.isFrontText(), List.of(sign.getLines())));
			case ServerboundEditBookPacket book -> {
				int slot = PovScreens.agentBookSlot();
				// Only a book the agent opened is relayed; its slot comes from the server, not the operator's hotbar.
				if (slot >= 0) OperatorInputSender.sendText((session, sequence) ->
						OperatorTextPayload.book(session, sequence, slot, book.pages(), book.title()));
			}
			case ServerboundSelectTradePacket trade -> OperatorInputSender.sendAction(OperatorAction.SELECT_TRADE, trade.getItem(), 0, 0);
			case ServerboundSetBeaconPacket beacon -> OperatorInputSender.sendAction(OperatorAction.SET_BEACON,
					beacon.primary().map(effect -> BuiltInRegistries.MOB_EFFECT.getId(effect.value())).orElse(-1),
					beacon.secondary().map(effect -> BuiltInRegistries.MOB_EFFECT.getId(effect.value())).orElse(-1), 0);
			case ServerboundPlaceRecipePacket place -> OperatorInputSender.sendAction(OperatorAction.PLACE_RECIPE,
					place.recipe().index(), place.useMaxItems() ? 1 : 0, 0);
			case ServerboundRecipeBookChangeSettingsPacket settings -> OperatorInputSender.sendAction(OperatorAction.RECIPE_BOOK_SETTINGS,
					settings.getBookType().ordinal(), settings.isOpen() ? 1 : 0, settings.isFiltering() ? 1 : 0);
			case ServerboundRecipeBookSeenRecipePacket seen -> OperatorInputSender.sendAction(OperatorAction.RECIPE_SEEN, seen.recipe().index(), 0, 0);
			case ServerboundSelectBundleItemPacket bundle -> OperatorInputSender.sendAction(OperatorAction.SELECT_BUNDLE_ITEM,
					bundle.slotId(), bundle.selectedItemIndex(), 0);
			case ServerboundContainerSlotStateChangedPacket crafter -> OperatorInputSender.sendAction(OperatorAction.CRAFTER_SLOT,
					crafter.slotId(), crafter.newState() ? 1 : 0, 0);
			default -> {
				return false;
			}
		}
		return true;
	}

	static boolean relayable(Packet<?> packet) {
		return packet instanceof ServerboundRenameItemPacket || packet instanceof ServerboundSignUpdatePacket
				|| packet instanceof ServerboundEditBookPacket || packet instanceof ServerboundSelectTradePacket
				|| packet instanceof ServerboundSetBeaconPacket || packet instanceof ServerboundPlaceRecipePacket
				|| packet instanceof ServerboundRecipeBookChangeSettingsPacket || packet instanceof ServerboundRecipeBookSeenRecipePacket
				|| packet instanceof ServerboundSelectBundleItemPacket || packet instanceof ServerboundContainerSlotStateChangedPacket;
	}

	/** Packets that act on the open menu; a spectator's mirrored screen must not send them for the operator. */
	static boolean menuPacket(Packet<?> packet) {
		return packet instanceof ServerboundRenameItemPacket || packet instanceof ServerboundSelectTradePacket
				|| packet instanceof ServerboundSetBeaconPacket || packet instanceof ServerboundPlaceRecipePacket
				|| packet instanceof ServerboundSelectBundleItemPacket || packet instanceof ServerboundContainerSlotStateChangedPacket;
	}

	private static String clamp(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}
}
