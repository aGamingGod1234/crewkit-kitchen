package dev.agaminggod.arenaagents.pov;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/** Registration and shared validation for the agent POV payloads. */
public final class PovPayloads {
	private static boolean registered;

	private PovPayloads() {
	}

	public static synchronized void registerTypes() {
		if (registered) {
			return;
		}
		PayloadTypeRegistry.clientboundPlay().register(AgentPovStatePayload.TYPE, AgentPovStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AgentPovPosePayload.TYPE, AgentPovPosePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(AgentPovMenuPayload.TYPE, AgentPovMenuPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PovStopPayload.TYPE, PovStopPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(OperatorInputPayload.TYPE, OperatorInputPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(OperatorActionPayload.TYPE, OperatorActionPayload.CODEC);
		// A full book is larger than the default serverbound custom payload limit, as vanilla's own packet is.
		PayloadTypeRegistry.serverboundPlay().registerLarge(OperatorTextPayload.TYPE, OperatorTextPayload.CODEC,
				OperatorTextPayload.MAX_ENCODED_BYTES);
		PayloadTypeRegistry.clientboundPlay().register(AgentPovBookPayload.TYPE, AgentPovBookPayload.CODEC);
		registered = true;
	}

	/** Ordinal codec that rejects unknown ordinals instead of clamping them like vanilla's ByIdMap strategies. */
	static <E extends Enum<E>> StreamCodec<ByteBuf, E> enumCodec(E[] values) {
		return ByteBufCodecs.VAR_INT.map(ordinal -> {
			if (ordinal < 0 || ordinal >= values.length) {
				throw new DecoderException("unknown " + values.getClass().getComponentType().getSimpleName()
						+ " ordinal " + ordinal);
			}
			return values[ordinal];
		}, Enum::ordinal);
	}

	/**
	 * Copies stacks into an immutable list. Singleplayer connections hand payload objects to the client thread without
	 * encoding them, and dedicated servers encode later on the network thread, so a payload must not alias live stacks.
	 */
	static List<ItemStack> copyStacks(List<ItemStack> stacks, int maxSize, String name) {
		Objects.requireNonNull(stacks, name + " must not be null");
		if (stacks.size() > maxSize) throw new IllegalArgumentException(name + " exceeds " + maxSize + " stacks");
		List<ItemStack> copies = new ArrayList<>(stacks.size());
		for (ItemStack stack : stacks) {
			copies.add(Objects.requireNonNull(stack, name + " must not contain null; use ItemStack.EMPTY").copy());
		}
		return Collections.unmodifiableList(copies);
	}

	static void requireFinite(float value, String name) {
		if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
	}

	static void requireNonNegative(float value, String name) {
		if (!Float.isFinite(value) || value < 0f) throw new IllegalArgumentException(name + " must be finite and non-negative");
	}

	static void requireRange(float value, float min, float max, String name) {
		if (!Float.isFinite(value) || value < min || value > max) {
			throw new IllegalArgumentException(name + " must be within " + min + ".." + max);
		}
	}
}
