package dev.agaminggod.arenaagents.server.pov;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;

/** Which agent messages a takeover operator is shown, without a server. */
public final class PovMessageRelayVerification {
	private PovMessageRelayVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PovMessageRelayVerification: " + verify() + " assertions passed");
	}

	public static int verify() {
		Component spawnSet = Component.translatable("block.minecraft.set_spawn");
		Component notNow = Component.translatable("block.minecraft.bed.no_sleep");
		PovMessageRelay.Text chat = PovMessageRelay.text(new ClientboundSystemChatPacket(spawnSet, false));
		check(chat != null && chat.message().equals(spawnSet) && !chat.overlay(), "system chat is relayed as chat");
		PovMessageRelay.Text overlay = PovMessageRelay.text(new ClientboundSystemChatPacket(notNow, true));
		check(overlay != null && overlay.overlay(), "overlay system messages stay on the action bar");
		PovMessageRelay.Text actionBar = PovMessageRelay.text(new ClientboundSetActionBarTextPacket(notNow));
		check(actionBar != null && actionBar.overlay(), "action bar packets are relayed as action bar text");
		check(PovMessageRelay.text(new ClientboundSetTitleTextPacket(notNow)) == null, "titles are not relayed");
		check(PovMessageRelay.relay(spawnSet, List.of()), "a message only the agent received is relayed");
		check(!PovMessageRelay.relay(Component.translatable("block.minecraft.set_spawn"), List.of(spawnSet)),
				"a broadcast the operator also received is not repeated");
		String tagged = PovMessageRelay.tagged("Luna", Component.literal("Respawn point set")).getString();
		check(tagged.equals("[Luna] Respawn point set"), "relayed chat names the agent");
		check(PovMessageRelay.tagged(" ", Component.literal("x")).getString().equals("[Agent] x"), "a blank name falls back");
		return 8;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
