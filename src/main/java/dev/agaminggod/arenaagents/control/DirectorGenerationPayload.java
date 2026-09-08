package dev.agaminggod.arenaagents.control;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class DirectorGenerationPayload {

	private DirectorGenerationPayload() {}

	public record Request(
		UUID requestId,
		UUID actorId,
		String name,
		String description
	) implements CustomPacketPayload {
		public Request {
			if (
				requestId == null ||
				actorId == null ||
				name == null ||
				!name.matches("[A-Za-z0-9_.-]{1,64}") ||
				description == null ||
				description.isBlank() ||
				description.length() > 2048 ||
				description.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')
			) throw new IllegalArgumentException("Enter a script name and a description of up to 2048 characters");
		}

		public static final Type<Request> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "director_generation_request")
		);
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
			public Request decode(RegistryFriendlyByteBuf b) {
				return new Request(b.readUUID(), b.readUUID(), b.readUtf(64), b.readUtf(2048));
			}

			public void encode(RegistryFriendlyByteBuf b, Request v) {
				b.writeUUID(v.requestId());
				b.writeUUID(v.actorId());
				b.writeUtf(v.name(), 64);
				b.writeUtf(v.description(), 2048);
			}
		};

		public Type<Request> type() {
			return TYPE;
		}
	}

	public record Result(
		UUID requestId,
		boolean success,
		String message,
		String scriptName
	) implements CustomPacketPayload {
		public Result {
			if (message.length() > 512) message = message.substring(0, 512);
		}

		public static final Type<Result> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("arenaagents", "director_generation_result")
		);
		public static final StreamCodec<RegistryFriendlyByteBuf, Result> CODEC = new StreamCodec<>() {
			public Result decode(RegistryFriendlyByteBuf b) {
				return new Result(b.readUUID(), b.readBoolean(), b.readUtf(512), b.readUtf(64));
			}

			public void encode(RegistryFriendlyByteBuf b, Result v) {
				b.writeUUID(v.requestId());
				b.writeBoolean(v.success());
				b.writeUtf(v.message(), 512);
				b.writeUtf(v.scriptName(), 64);
			}
		};

		public Type<Result> type() {
			return TYPE;
		}
	}
}
