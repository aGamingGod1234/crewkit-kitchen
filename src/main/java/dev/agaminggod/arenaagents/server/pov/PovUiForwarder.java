package dev.agaminggod.arenaagents.server.pov;

import carpet.patches.EntityPlayerMPFake;
import dev.agaminggod.arenaagents.pov.AgentPovBookPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlaceGhostRecipePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookSettingsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import dev.agaminggod.arenaagents.mixin.MerchantMenuAccessor;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.ItemStack;

/**
 * Gives a takeover operator's client the agent-side screen data a vanilla client gets for its own body: the sign
 * editor, merchant offers, recipe book contents and ghost recipes, and opened books. Without it those screens were
 * blank or never opened, because the server sent them only to the agent's Carpet connection.
 *
 * <p>Vanilla packets are passed on unchanged; the operator's client routes the container-bound ones to its mirrored
 * screen. The recipe book is the operator's own client book, so it shows the agent's book for the takeover and is
 * restored from the operator's server book when the takeover ends.
 */
public final class PovUiForwarder {
	/** Merchant offers change as trades are used up; vanilla's client tracks that itself, a mirror cannot. */
	static final int MERCHANT_REFRESH_TICKS = 10;
	/** ServerboundEditBookPacket names the off hand as this inventory slot. */
	static final int OFFHAND_SLOT = 40;

	private PovUiForwarder() {
	}

	/** Called for every packet sent to a game connection; only the screen packets above are looked at further. */
	public static void observe(ServerPlayer player, Packet<?> packet) {
		if (!forwarded(packet) || !(player instanceof EntityPlayerMPFake)) return;
		MinecraftServer server = player.level().getServer();
		if (!server.isSameThread()) {
			server.execute(() -> observe(player, packet));
			return;
		}
		PovSessionRuntime.takeoverOf(player).ifPresent(session -> {
			ServerPlayer operator = server.getPlayerList().getPlayer(session.operatorId());
			if (operator == null || operator.hasDisconnected()) return;
			if (packet instanceof ClientboundOpenSignEditorPacket editor) sendSign(operator, player, editor.getPos());
			operator.connection.send(packet);
		});
	}

	/**
	 * ServerPlayer.openTextEdit sends the sign block right before the editor. A just-placed sign reaches the operator
	 * only with the chunk broadcast at the end of the tick, after the editor packet, which the client would then
	 * ignore; so the block and its text go first, as they do for the agent.
	 */
	private static void sendSign(ServerPlayer operator, ServerPlayer agent, BlockPos pos) {
		operator.connection.send(new ClientboundBlockUpdatePacket(agent.level(), pos));
		if (agent.level().getBlockEntity(pos) instanceof SignBlockEntity sign) {
			Packet<?> text = sign.getUpdatePacket();
			if (text != null) operator.connection.send(text);
		}
	}

	static boolean forwarded(Packet<?> packet) {
		return packet instanceof ClientboundOpenSignEditorPacket
				|| packet instanceof ClientboundMerchantOffersPacket
				|| packet instanceof ClientboundPlaceGhostRecipePacket
				|| packet instanceof ClientboundRecipeBookAddPacket
				|| packet instanceof ClientboundRecipeBookRemovePacket
				|| packet instanceof ClientboundRecipeBookSettingsPacket;
	}

	/** ServerPlayer.openItemGui ran for the agent: a writable or written book in {@code hand}. */
	public static void bookOpened(ServerPlayer agent, ItemStack book, InteractionHand hand) {
		if (!(agent instanceof EntityPlayerMPFake) || book.isEmpty()) return;
		MinecraftServer server = agent.level().getServer();
		PovSessionRuntime.takeoverOf(agent).ifPresent(session -> {
			ServerPlayer operator = server.getPlayerList().getPlayer(session.operatorId());
			if (operator == null || operator.hasDisconnected() || !ServerPlayNetworking.canSend(operator, AgentPovBookPayload.TYPE)) return;
			ServerPlayNetworking.send(operator, new AgentPovBookPayload(session.id(), hand, bookSlot(hand, agent.getInventory().getSelectedSlot()), book));
		});
	}

	/** The slot BookEditScreen would name for the agent: its selected hotbar slot, or the off hand. */
	static int bookSlot(InteractionHand hand, int selectedSlot) {
		return hand == InteractionHand.MAIN_HAND ? selectedSlot : OFFHAND_SLOT;
	}

	/** Takeover start: the operator's client recipe book shows the agent's book. */
	static void showAgentRecipeBook(ServerPlayer operator, ServerPlayer agent) {
		agent.getRecipeBook().sendInitialRecipeBook(operator);
	}

	/** Takeover end: the operator's own book again, exactly as the server knows it. */
	static void restoreOperatorRecipeBook(ServerPlayer operator) {
		operator.getRecipeBook().sendInitialRecipeBook(operator);
	}

	/** Keeps the operator's mirrored trading screen on the agent's current offers. */
	static void refreshMerchant(ServerPlayer operator, ServerPlayer agent, long gameTime) {
		if (gameTime % MERCHANT_REFRESH_TICKS != 0 || !(agent.containerMenu instanceof MerchantMenu menu)) return;
		Merchant trader = ((MerchantMenuAccessor) menu).arenaagents$getTrader();
		// Merchant.openTradingScreen's level argument: a villager's profession level, 1 for every other trader.
		int level = trader instanceof Villager villager ? villager.getVillagerData().level() : 1;
		operator.connection.send(new ClientboundMerchantOffersPacket(menu.containerId, trader.getOffers(), level,
				trader.getVillagerXp(), trader.showProgressBar(), trader.canRestock()));
	}
}
