package dev.agaminggod.arenaagents.client.perception;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.client.config.AgentConfig;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import java.util.ArrayList;
import java.util.List;

public final class ObservationWireBudgetVerification {
	private static final String OBSERVATION_EVENT = "observation";
	private static final String WORST_CASE_PATTERN = "\u0001😀\"\\漢";

	private ObservationWireBudgetVerification() {
	}

	public static void verifyWorstCaseObservationFits() {
		verifyStringBounds();
		ProtocolCodec codec = new ProtocolCodec();
		AgentConfig config = new AgentConfig(
				worstCaseText(ProtocolConstants.MAX_COMMAND_ID_LENGTH),
				AgentConfig.DEFAULT_BRIDGE_PORT,
				AgentConfig.MAX_OBSERVATION_RADIUS,
				false,
				AgentConfig.DEFAULT_BRIDGE_SECRET
		);
		BridgeServer server = new BridgeServer(config, codec, Runnable::run, command -> { });
		Observation source = worstCaseObservation();

		ObservationWireBudget.FittedObservation first = ObservationWireBudget.fit(
				source,
				codec,
				server,
				OBSERVATION_EVENT
		);
		ObservationWireBudget.FittedObservation second = ObservationWireBudget.fit(
				source,
				codec,
				server,
				OBSERVATION_EVENT
		);

		if (!first.observation().ready()) {
			throw new AssertionError("bounded ready observation must not degrade to unavailable");
		}
		if (first.reservedEnvelopeBytes() > ProtocolConstants.MAX_LINE_BYTES) {
			throw new AssertionError("reserved observation envelope exceeds the JSONL byte limit");
		}
		if (!first.observation().equals(second.observation())
				|| !first.payload().equals(second.payload())
				|| first.reservedEnvelopeBytes() != second.reservedEnvelopeBytes()) {
			throw new AssertionError("wire-budget fitting must be deterministic");
		}
		assertStablePrefix(source.blocks(), first.observation().blocks(), "blocks");
		assertStablePrefix(source.entities(), first.observation().entities(), "entities");
		assertStablePrefix(source.inventory().items(), first.observation().inventory().items(), "inventory");
		assertStablePrefix(source.player().effects(), first.observation().player().effects(), "effects");
		int sourceEntries = source.blocks().size()
				+ source.entities().size()
				+ source.inventory().items().size()
				+ source.player().effects().size();
		int fittedEntries = first.observation().blocks().size()
				+ first.observation().entities().size()
				+ first.observation().inventory().items().size()
				+ first.observation().player().effects().size();
		if (fittedEntries >= sourceEntries) {
			throw new AssertionError("worst-case observation must exercise aggregate fitting");
		}

		JsonObject versioned = codec.toVersionedJsonObject(first.observation());
		versioned.remove("protocolVersion");
		if (!versioned.equals(first.payload())) {
			throw new AssertionError("fitted payload must use ProtocolCodec's exact object serialization");
		}
		int exactReservedBytes = server.encodedEventBytesAtMaximumEnvelope(OBSERVATION_EVENT, first.payload());
		if (exactReservedBytes != first.reservedEnvelopeBytes()) {
			throw new AssertionError("reported budget must use the exact bridge publication envelope");
		}
	}

	private static void verifyStringBounds() {
		String identifier = "界".repeat(ProtocolConstants.MAX_IDENTIFIER_LENGTH + 1);
		String commandId = "界".repeat(ProtocolConstants.MAX_COMMAND_ID_LENGTH + 1);
		String reason = "界".repeat(ProtocolConstants.MAX_REASON_CODE_LENGTH + 1);
		String message = "界".repeat(ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH + 1);
		expectRejected(
				() -> new EntitySnapshot(identifier, "minecraft:pig", "pig", 0, 0, 0, 0, 1, 1, false),
				"entity identifier"
		);
		expectRejected(
				() -> new EntitySnapshot("entity", "minecraft:pig", identifier, 0, 0, 0, 0, 1, 1, false),
				"entity name"
		);
		expectRejected(
				() -> new BlockSnapshot(0, 0, 0, identifier, "minecraft:empty", true, false, 0),
				"block identifier"
		);
		expectRejected(
				() -> new InventorySnapshot(0, identifier, 1, List.of()),
				"selected item identifier"
		);
		expectRejected(
				() -> new InventorySnapshot.ItemSummary(identifier, 1),
				"inventory item identifier"
		);
		expectRejected(
				() -> new Observation.EffectStatus(identifier, 0, 1, false, true),
				"effect identifier"
		);
		expectRejected(
				() -> new Observation.WorldStatus(identifier, 0, 0, false, false),
				"dimension identifier"
		);
		expectRejected(
				() -> new Observation.ActionStatus(true, commandId, "wait", "RUNNING"),
				"action command identifier"
		);
		expectRejected(
				() -> new Observation.ResultStatus(true, "command", "FAILED", reason, message, 1),
				"result text"
		);
		expectRejected(
				() -> Observation.unavailable(reason),
				"unavailable status"
		);
	}

	private static Observation worstCaseObservation() {
		String identifier = worstCaseText(ProtocolConstants.MAX_IDENTIFIER_LENGTH);
		String commandId = worstCaseText(ProtocolConstants.MAX_COMMAND_ID_LENGTH);
		String state = worstCaseText(ProtocolConstants.MAX_REASON_CODE_LENGTH);

		List<Observation.EffectStatus> effects = new ArrayList<>();
		for (int index = 0; index < ObservationLimits.MAX_EFFECTS; index++) {
			effects.add(new Observation.EffectStatus(identifier, index, index + 1, false, true));
		}
		List<InventorySnapshot.ItemSummary> items = new ArrayList<>();
		for (int index = 0; index < ObservationLimits.MAX_INVENTORY_SUMMARIES; index++) {
			items.add(new InventorySnapshot.ItemSummary(identifier, index + 1));
		}
		List<EntitySnapshot> entities = new ArrayList<>();
		for (int index = 0; index < ObservationLimits.MAX_ENTITIES; index++) {
			entities.add(new EntitySnapshot(
					identifier,
					identifier,
					identifier,
					index,
					64.0D,
					0.0D,
					index,
					20.0F,
					20.0F,
					true
			));
		}
		List<BlockSnapshot> blocks = new ArrayList<>();
		for (int index = 0; index < ObservationLimits.MAX_BLOCKS; index++) {
			blocks.add(new BlockSnapshot(
					index,
					64,
					0,
					identifier,
					identifier,
					false,
					true,
					index
			));
		}

		return new Observation(
				true,
				Observation.READY_STATUS,
				new Observation.Position(0.0D, 64.0D, 0.0D),
				new Observation.Velocity(0.0D, 0.0D, 0.0D),
				new Observation.View(0.0F, 0.0F),
				new Observation.PlayerStatus(20.0F, 20.0F, 20, 20, effects),
				new InventorySnapshot(0, identifier, 64, items),
				entities,
				blocks,
				new Observation.WorldStatus(identifier, Long.MAX_VALUE, Long.MAX_VALUE, true, true),
				new Observation.ActionStatus(true, commandId, state, state),
				new Observation.ResultStatus(
						true,
						commandId,
						state,
						state,
						worstCaseText(ProtocolConstants.MAX_RESULT_MESSAGE_LENGTH),
						Long.MAX_VALUE
				)
		);
	}

	private static String worstCaseText(int maximumLength) {
		StringBuilder text = new StringBuilder(maximumLength);
		while (text.length() + WORST_CASE_PATTERN.length() <= maximumLength) {
			text.append(WORST_CASE_PATTERN);
		}
		while (text.length() < maximumLength) {
			text.append('界');
		}
		return text.toString();
	}

	private static <T> void assertStablePrefix(List<T> source, List<T> fitted, String label) {
		if (!source.subList(0, fitted.size()).equals(fitted)) {
			throw new AssertionError(label + " fitting must preserve the stable priority prefix");
		}
	}

	private static void expectRejected(Runnable constructor, String label) {
		try {
			constructor.run();
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError(label + " must reject overlong text");
	}
}
