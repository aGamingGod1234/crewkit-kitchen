package dev.agaminggod.arenaagents.risk;

import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Presentation only: the risk the watched agent's perception assigns to nearby creatures and players, so a player
 * spectating or taking over that agent can see it above their heads. It never feeds back into any decision.
 * An empty row list clears the overlay.
 */
public record RiskOverlayPayload(int agentEntityId, List<Row> rows) implements CustomPacketPayload {
	public static final int MAX_ROWS = 32;
	public static final Type<RiskOverlayPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("arenaagents", "risk_overlay"));

	/** One entity: its risk score and whether it is actively engaging the agent (otherwise potential only). */
	public record Row(int entityId, float risk, boolean active) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Row> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Row::entityId,
				ByteBufCodecs.FLOAT, Row::risk,
				ByteBufCodecs.BOOL, Row::active,
				Row::new);
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, RiskOverlayPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, RiskOverlayPayload::agentEntityId,
			Row.CODEC.apply(ByteBufCodecs.list(MAX_ROWS)), RiskOverlayPayload::rows,
			RiskOverlayPayload::new);

	public RiskOverlayPayload {
		rows = List.copyOf(Objects.requireNonNull(rows, "rows must not be null"));
		if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("rows exceed " + MAX_ROWS);
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
	}

	/** Compact overhead label: "⚠ 42" while active, "potential 18" otherwise. */
	public static String label(float risk, boolean active) {
		long rounded = Math.round(Float.isFinite(risk) ? risk : 0.0F);
		return active ? "⚠ " + rounded : "potential " + rounded;
	}

	@Override
	public Type<RiskOverlayPayload> type() {
		return TYPE;
	}
}
