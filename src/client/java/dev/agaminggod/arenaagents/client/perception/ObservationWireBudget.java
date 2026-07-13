package dev.agaminggod.arenaagents.client.perception;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.client.bridge.BridgeServer;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.protocol.ProtocolConstants;
import dev.agaminggod.arenaagents.protocol.ProtocolException;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

public final class ObservationWireBudget {
	public static final String STATUS_WIRE_BUDGET_EXCEEDED = "wire_budget_exceeded";

	private ObservationWireBudget() {
	}

	public static FittedObservation fit(
			Observation source,
			ProtocolCodec codec,
			BridgeServer bridgeServer,
			String eventType
	) {
		Objects.requireNonNull(source, "source must not be null");
		Objects.requireNonNull(codec, "codec must not be null");
		Objects.requireNonNull(bridgeServer, "bridgeServer must not be null");
		Objects.requireNonNull(eventType, "eventType must not be null");

		FittedObservation fitted = tryFit(source, codec, bridgeServer, eventType);
		if (fitted != null) {
			return fitted;
		}

		Observation candidate = source;
		Reduction reduction = reducePrefix(
				candidate,
				candidate.blocks().size(),
				count -> withBlocks(source, source.blocks().subList(0, count)),
				codec,
				bridgeServer,
				eventType
		);
		if (reduction.fitted() != null) {
			return reduction.fitted();
		}
		candidate = reduction.minimum();

		Observation inventoryBase = candidate;
		reduction = reducePrefix(
				inventoryBase,
				inventoryBase.inventory().items().size(),
				count -> withInventoryItems(
						inventoryBase,
						inventoryBase.inventory().items().subList(0, count)
				),
				codec,
				bridgeServer,
				eventType
		);
		if (reduction.fitted() != null) {
			return reduction.fitted();
		}
		candidate = reduction.minimum();

		Observation entityBase = candidate;
		reduction = reducePrefix(
				entityBase,
				entityBase.entities().size(),
				count -> withEntities(entityBase, entityBase.entities().subList(0, count)),
				codec,
				bridgeServer,
				eventType
		);
		if (reduction.fitted() != null) {
			return reduction.fitted();
		}
		candidate = reduction.minimum();

		Observation effectBase = candidate;
		reduction = reducePrefix(
				effectBase,
				effectBase.player().effects().size(),
				count -> withEffects(effectBase, effectBase.player().effects().subList(0, count)),
				codec,
				bridgeServer,
				eventType
		);
		if (reduction.fitted() != null) {
			return reduction.fitted();
		}

		Observation fallback = Observation.unavailable(STATUS_WIRE_BUDGET_EXCEEDED);
		FittedObservation fallbackFit = tryFit(fallback, codec, bridgeServer, eventType);
		if (fallbackFit == null) {
			throw new ProtocolException(
					ProtocolConstants.ERROR_LINE_TOO_LARGE,
					"Minimal observation does not fit the reserved bridge envelope"
			);
		}
		return fallbackFit;
	}

	private static Reduction reducePrefix(
			Observation base,
			int sourceSize,
			IntFunction<Observation> candidateFactory,
			ProtocolCodec codec,
			BridgeServer bridgeServer,
			String eventType
	) {
		Observation minimum = candidateFactory.apply(0);
		FittedObservation best = tryFit(minimum, codec, bridgeServer, eventType);
		if (best == null) {
			return new Reduction(minimum, null);
		}

		int low = 1;
		int high = sourceSize;
		while (low <= high) {
			int middle = low + (high - low) / 2;
			FittedObservation candidate = tryFit(
					candidateFactory.apply(middle),
					codec,
					bridgeServer,
					eventType
			);
			if (candidate == null) {
				high = middle - 1;
			} else {
				best = candidate;
				low = middle + 1;
			}
		}
		return new Reduction(base, best);
	}

	private static FittedObservation tryFit(
			Observation observation,
			ProtocolCodec codec,
			BridgeServer bridgeServer,
			String eventType
	) {
		JsonObject payload = payload(codec, observation);
		try {
			int bytes = bridgeServer.encodedEventBytesAtMaximumEnvelope(eventType, payload);
			return new FittedObservation(observation, payload, bytes);
		} catch (ProtocolException exception) {
			if (ProtocolConstants.ERROR_LINE_TOO_LARGE.equals(exception.code())) {
				return null;
			}
			throw exception;
		}
	}

	private static JsonObject payload(ProtocolCodec codec, Observation observation) {
		JsonObject payload = codec.toVersionedJsonObject(observation);
		payload.remove(ProtocolConstants.FIELD_PROTOCOL_VERSION);
		return payload;
	}

	private static Observation withBlocks(Observation source, List<BlockSnapshot> blocks) {
		return copy(source, source.player(), source.inventory(), source.entities(), blocks);
	}

	private static Observation withInventoryItems(
			Observation source,
			List<InventorySnapshot.ItemSummary> items
	) {
		InventorySnapshot inventory = source.inventory();
		InventorySnapshot reduced = new InventorySnapshot(
				inventory.selectedSlot(),
				inventory.selectedItemId(),
				inventory.selectedItemCount(),
				items
		);
		return copy(source, source.player(), reduced, source.entities(), source.blocks());
	}

	private static Observation withEntities(Observation source, List<EntitySnapshot> entities) {
		return copy(source, source.player(), source.inventory(), entities, source.blocks());
	}

	private static Observation withEffects(Observation source, List<Observation.EffectStatus> effects) {
		Observation.PlayerStatus player = source.player();
		Observation.PlayerStatus reduced = new Observation.PlayerStatus(
				player.health(),
				player.maxHealth(),
				player.hunger(),
				player.armor(),
				effects
		);
		return copy(source, reduced, source.inventory(), source.entities(), source.blocks());
	}

	private static Observation copy(
			Observation source,
			Observation.PlayerStatus player,
			InventorySnapshot inventory,
			List<EntitySnapshot> entities,
			List<BlockSnapshot> blocks
	) {
		return new Observation(
				source.ready(),
				source.status(),
				source.position(),
				source.velocity(),
				source.view(),
				player,
				inventory,
				entities,
				blocks,
				source.world(),
				source.currentAction(),
				source.lastResult()
		);
	}

	public record FittedObservation(
			Observation observation,
			JsonObject payload,
			int reservedEnvelopeBytes
	) {
		public FittedObservation {
			observation = Objects.requireNonNull(observation, "observation must not be null");
			payload = Objects.requireNonNull(payload, "payload must not be null").deepCopy();
			if (reservedEnvelopeBytes < 0 || reservedEnvelopeBytes > ProtocolConstants.MAX_LINE_BYTES) {
				throw new IllegalArgumentException("reservedEnvelopeBytes must fit the protocol line limit");
			}
		}

		@Override
		public JsonObject payload() {
			return payload.deepCopy();
		}
	}

	private record Reduction(Observation minimum, FittedObservation fitted) {
	}
}
