package dev.agaminggod.arenaagents.server.pov;

import dev.agaminggod.arenaagents.pov.OperatorAction;
import dev.agaminggod.arenaagents.pov.OperatorTextPayload;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.RecipeBookType;

/** Takeover relays for screens that do not use slot clicks: payload limits, action contract and decoding. */
public final class PovRelayVerification {
	private PovRelayVerification() {
	}

	public static void main(String[] args) {
		System.out.println("PovRelayVerification: " + verify() + " assertions passed");
	}

	public static int verify() {
		var out = System.out;
		var err = System.err;
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.setOut(out);
		System.setErr(err);
		return verifyTextLimits() + verifyTextRoundTrip() + verifyActionContract() + verifyDecoding()
				+ verifyForwarding() + verifyFlightToggle() + verifyFlightOutcome() + verifyDirectMessageSpy();
	}

	private static int verifyTextLimits() {
		OperatorTextPayload.rename(1L, 1, "Excalibur");
		OperatorTextPayload.sign(1L, 1, BlockPos.ZERO, true, List.of("a", "b", "c", "d"));
		OperatorTextPayload.book(1L, 1, 40, List.of("page"), Optional.of("Title"));
		rejects(() -> OperatorTextPayload.sign(1L, 1, BlockPos.ZERO, true, List.of("a", "b", "c")), "a sign needs four lines");
		rejects(() -> OperatorTextPayload.sign(1L, 1, BlockPos.ZERO, true, List.of("x".repeat(385), "", "", "")),
				"sign lines keep vanilla's 384 character limit");
		rejects(() -> OperatorTextPayload.book(1L, 1, 0, Collections.nCopies(101, "p"), Optional.empty()), "books keep 100 pages");
		rejects(() -> OperatorTextPayload.book(1L, 1, 0, List.of("x".repeat(1025)), Optional.empty()), "pages keep 1024 characters");
		rejects(() -> OperatorTextPayload.book(1L, 1, 0, List.of("p"), Optional.of("t".repeat(33))), "titles keep 32 characters");
		rejects(() -> new OperatorTextPayload(1L, 1, OperatorTextPayload.Kind.RENAME_ITEM, BlockPos.ZERO, 0,
				List.of("a", "b"), Optional.empty()), "a rename carries one name");
		rejects(() -> OperatorTextPayload.rename(1L, -1, "x"), "sequences are never negative");
		return 9;
	}

	private static int verifyTextRoundTrip() {
		List<String> pages = new ArrayList<>(Collections.nCopies(100, "é".repeat(1024)));
		OperatorTextPayload book = OperatorTextPayload.book(7L, 3, 4, pages, Optional.of("Title"));
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		OperatorTextPayload.CODEC.encode(buffer, book);
		check(buffer.readableBytes() <= OperatorTextPayload.MAX_ENCODED_BYTES, "a maximal book fits the registered payload size");
		check(OperatorTextPayload.CODEC.decode(buffer).equals(book), "book text round-trips");
		OperatorTextPayload sign = OperatorTextPayload.sign(7L, 4, new BlockPos(1, -60, 2), false, List.of("a", "", "c", ""));
		buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		OperatorTextPayload.CODEC.encode(buffer, sign);
		check(OperatorTextPayload.CODEC.decode(buffer).equals(sign), "sign text and position round-trip");
		return 3;
	}

	private static int verifyActionContract() {
		OperatorAction[] appended = {OperatorAction.SELECT_TRADE, OperatorAction.SET_BEACON, OperatorAction.PLACE_RECIPE,
				OperatorAction.RECIPE_BOOK_SETTINGS, OperatorAction.RECIPE_SEEN, OperatorAction.SELECT_BUNDLE_ITEM,
				OperatorAction.CRAFTER_SLOT};
		for (int index = 0; index < appended.length; index++) {
			check(appended[index].ordinal() == 12 + index, appended[index] + " keeps its appended ordinal");
		}
		check(OperatorAction.values().length == 19, "no action was inserted or removed");
		return appended.length + 1;
	}

	private static int verifyDecoding() {
		ServerboundSetBeaconPacket none = OperatorActionDispatcher.beaconEffects(-1, -1).orElseThrow();
		check(none.primary().isEmpty() && none.secondary().isEmpty(), "-1 means no beacon effect");
		int speed = BuiltInRegistries.MOB_EFFECT.getId(MobEffects.SPEED.value());
		ServerboundSetBeaconPacket chosen = OperatorActionDispatcher.beaconEffects(speed, -1).orElseThrow();
		check(chosen.primary().orElseThrow().is(MobEffects.SPEED), "a registry id names the effect");
		check(OperatorActionDispatcher.beaconEffects(-2, -1).isEmpty(), "other negative ids are refused");
		check(OperatorActionDispatcher.beaconEffects(speed, 1_000_000).isEmpty(), "unknown ids are refused like a bad packet");
		check(OperatorActionDispatcher.decodeRecipeBookType(RecipeBookType.FURNACE.ordinal()).orElseThrow() == RecipeBookType.FURNACE,
				"recipe book types decode by ordinal");
		check(OperatorActionDispatcher.decodeRecipeBookType(-1).isEmpty()
				&& OperatorActionDispatcher.decodeRecipeBookType(RecipeBookType.values().length).isEmpty(), "unknown book types are refused");
		return 6;
	}

	private static int verifyForwarding() {
		check(PovUiForwarder.forwarded(new ClientboundOpenSignEditorPacket(BlockPos.ZERO, true)), "the sign editor is forwarded");
		check(!PovUiForwarder.forwarded(new ClientboundSetChunkCacheRadiusPacket(8)), "unrelated packets are not");
		check(PovUiForwarder.bookSlot(InteractionHand.MAIN_HAND, 5) == 5, "a main-hand book names the selected slot");
		check(PovUiForwarder.bookSlot(InteractionHand.OFF_HAND, 5) == PovUiForwarder.OFFHAND_SLOT, "an off-hand book names slot 40");
		return 4;
	}

	private static int verifyFlightToggle() {
		CarpetOperatorBodyController.Keys keys = new CarpetOperatorBodyController.Keys();
		check(!keys.flightTogglePress(true), "the first press only opens the double-tap window");
		check(keys.flightTogglePress(true), "a second press inside the window toggles flight");
		check(!keys.flightTogglePress(true), "a toggle closes the window");
		for (int tick = 0; tick < CarpetOperatorBodyController.Keys.FLIGHT_TOGGLE_TICKS; tick++) keys.tick();
		check(!keys.flightTogglePress(true), "after seven ticks a press starts a new window");
		check(!keys.flightTogglePress(false), "swimming or an unjumpable vehicle blocks the toggle");
		check(keys.flightTogglePress(true), "the window stays open while blocked, like LocalPlayer");
		return 6;
	}

	private static int verifyFlightOutcome() {
		check(dev.agaminggod.arenaagents.server.runtime.controller.ServerFlightControllerAccess.outcome(true).state()
				== dev.agaminggod.arenaagents.server.runtime.controller.ServerController.State.SUCCEEDED, "flight that survives a tick succeeds");
		var ended = dev.agaminggod.arenaagents.server.runtime.controller.ServerFlightControllerAccess.outcome(false);
		check(ended.state() == dev.agaminggod.arenaagents.server.runtime.controller.ServerController.State.FAILED
				&& ended.reasonCode().equals("FLIGHT_ENDED_ON_LANDING"), "flight that landing ended is reported");
		return 2;
	}

	private static int verifyDirectMessageSpy() {
		java.util.UUID agent = java.util.UUID.randomUUID();
		java.util.UUID operator = java.util.UUID.randomUUID();
		java.util.UUID other = java.util.UUID.randomUUID();
		try {
			PovMessageRelay.start(agent, operator, "Luna");
			check(PovMessageRelay.relaysAnyTo(operator, List.of(other, agent)), "the operator driving a DM'd agent already sees its line");
			check(!PovMessageRelay.relaysAnyTo(other, List.of(agent)), "other operators keep their spy copy");
			check(!PovMessageRelay.relaysAnyTo(operator, List.of(other)), "a DM to another agent keeps the spy copy");
		} finally {
			PovMessageRelay.stop(agent, operator);
		}
		check(!PovMessageRelay.relaysAnyTo(operator, List.of(agent)), "the spy copy returns after the takeover");
		return 4;
	}

	private static void rejects(Runnable action, String message) {
		try {
			action.run();
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError(message);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
