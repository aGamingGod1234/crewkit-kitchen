package dev.agaminggod.arenaagents.client.pov.input;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;

/** Which vanilla screen packets a takeover relays to the agent, and which a spectator's mirror drops. */
public final class PovOutgoingRelayVerification {
	private PovOutgoingRelayVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PovOutgoingRelayVerification: " + verify() + " assertions passed");
	}

	public static int verify() {
		check(PovOutgoingRelay.relayable(new ServerboundRenameItemPacket("x")), "anvil names are relayed");
		check(PovOutgoingRelay.relayable(new ServerboundSignUpdatePacket(BlockPos.ZERO, true, "a", "b", "c", "d")), "sign text is relayed");
		check(PovOutgoingRelay.relayable(new ServerboundEditBookPacket(0, List.of("p"), Optional.empty())), "book text is relayed");
		check(PovOutgoingRelay.relayable(new ServerboundSelectTradePacket(0)), "trade selection is relayed");
		check(!PovOutgoingRelay.relayable(new ServerboundSwingPacket(InteractionHand.MAIN_HAND)), "unrelated packets stay the operator's");
		check(PovOutgoingRelay.menuPacket(new ServerboundSelectTradePacket(0))
				&& !PovOutgoingRelay.menuPacket(new ServerboundSignUpdatePacket(BlockPos.ZERO, true, "", "", "", "")),
				"only menu packets are dropped for spectators");
		return 6;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
