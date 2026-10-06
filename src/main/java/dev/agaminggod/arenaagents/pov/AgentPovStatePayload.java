package dev.agaminggod.arenaagents.pov;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;

/** Everything about the watched agent except its pose; sent whenever any of it changes. */
public record AgentPovStatePayload(
		PovIdentity identity,
		PovVitals vitals,
		PovInventory inventory,
		List<MobEffectInstance> effects,
		Optional<PovMenu> menu,
		Optional<PovDeath> death
) implements CustomPacketPayload {
	public static final int MAX_EFFECTS = 32;
	public static final Type<AgentPovStatePayload> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "pov_state")
	);
	public static final StreamCodec<RegistryFriendlyByteBuf, AgentPovStatePayload> CODEC = StreamCodec.composite(
			PovIdentity.CODEC, AgentPovStatePayload::identity,
			PovVitals.CODEC, AgentPovStatePayload::vitals,
			PovInventory.CODEC, AgentPovStatePayload::inventory,
			MobEffectInstance.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_EFFECTS)), AgentPovStatePayload::effects,
			ByteBufCodecs.optional(PovMenu.CODEC), AgentPovStatePayload::menu,
			ByteBufCodecs.optional(PovDeath.CODEC), AgentPovStatePayload::death,
			AgentPovStatePayload::new
	);

	public AgentPovStatePayload {
		identity = Objects.requireNonNull(identity, "identity must not be null");
		vitals = Objects.requireNonNull(vitals, "vitals must not be null");
		inventory = Objects.requireNonNull(inventory, "inventory must not be null");
		Objects.requireNonNull(effects, "effects must not be null");
		if (effects.size() > MAX_EFFECTS) throw new IllegalArgumentException("effects exceed " + MAX_EFFECTS);
		// Effect instances tick down in place; copy for the same reason PovPayloads.copyStacks copies stacks.
		List<MobEffectInstance> copies = new ArrayList<>(effects.size());
		for (MobEffectInstance effect : effects) {
			copies.add(new MobEffectInstance(Objects.requireNonNull(effect, "effects must not contain null")));
		}
		effects = Collections.unmodifiableList(copies);
		menu = Objects.requireNonNull(menu, "menu must not be null");
		death = Objects.requireNonNull(death, "death must not be null");
	}

	public long sessionId() {
		return identity.sessionId();
	}

	@Override
	public Type<AgentPovStatePayload> type() {
		return TYPE;
	}
}
