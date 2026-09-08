package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.agaminggod.arenaagents.control.DirectorCommandRequestPayload;
import dev.agaminggod.arenaagents.control.DirectorCommandResultPayload;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Exercises the production command bridge, including denial and correlated domain errors. */
public final class DirectorCommandsVerification {
	private DirectorCommandsVerification() { }
	public static int verify() {
		var id = UUID.randomUUID();
		var request = new DirectorCommandRequestPayload(id, "codex skit off");
		var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
		try {
			DirectorCommandRequestPayload.CODEC.encode(buffer, request);
			check(request.equals(DirectorCommandRequestPayload.CODEC.decode(buffer)), "request codec preserves command and correlation");
			var response = new DirectorCommandResultPayload(id, false, "ACTOR_NAME_TAKEN: Already used");
			DirectorCommandResultPayload.CODEC.encode(buffer, response);
			check(response.equals(DirectorCommandResultPayload.CODEC.decode(buffer)), "result codec preserves the server failure");
            var voice = new dev.agaminggod.arenaagents.server.voice.VoiceProfile("voice.selene.v1", "excited", 1.25, 64);
            var snapshot = new dev.agaminggod.arenaagents.control.DirectorSnapshotPayload(true, true, java.util.List.of(
                    new dev.agaminggod.arenaagents.control.DirectorSnapshotPayload.Actor(id.toString(), "GPT 6-Astra", "GPT_6_Astra", "codex", false, true, "Ready", voice)));
            dev.agaminggod.arenaagents.control.DirectorSnapshotPayload.CODEC.encode(buffer, snapshot);
            check(snapshot.equals(dev.agaminggod.arenaagents.control.DirectorSnapshotPayload.CODEC.decode(buffer)), "snapshot preserves saved voice settings on reopen");
		} finally { buffer.release(); }
		try {
			new DirectorCommandRequestPayload(id, "x".repeat(DirectorCommandRequestPayload.MAX_COMMAND_LENGTH + 1));
			throw new AssertionError("oversized command was accepted");
		} catch (IllegalArgumentException expected) { }
		check(new DirectorCommandResultPayload(id, false, "x".repeat(600)).message().length() == DirectorCommandResultPayload.MAX_MESSAGE_LENGTH,
				"server messages remain within the wire bound");

		AtomicInteger calls = new AtomicInteger();
		var dispatcher = new CommandDispatcher<CommandSourceStack>();
		dispatcher.register(Commands.literal("codex").then(Commands.literal("skit")
				.then(Commands.literal("off").executes(context -> {
					calls.incrementAndGet();
					check(context.getSource().getTextName().equals("Audit"), "submission retains the original source identity");
					context.getSource().sendSuccess(() -> Component.literal("Skit mode disabled"), false);
					return 0;
				}))
				.then(Commands.literal("duplicate").executes(context -> {
					throw new SimpleCommandExceptionType(Component.literal("ACTOR_NAME_TAKEN: Already used")).create();
				}))));
		CommandSourceStack operator = source(PermissionSet.ALL_PERMISSIONS);
		check(!DirectorCommands.execute(request, dispatcher, source(PermissionSet.NO_PERMISSIONS)).success() && calls.get() == 0,
				"unauthorized submissions do not execute commands");
		check(!DirectorCommands.execute(new DirectorCommandRequestPayload(id, "kill @e"), dispatcher, operator).success() && calls.get() == 0,
				"the Director cannot submit unrelated commands");
		var success = DirectorCommands.execute(request, dispatcher, operator);
		check(success.success() && success.requestId().equals(id) && success.message().equals("Skit mode disabled") && calls.get() == 1,
				"a valid zero-result command returns its authoritative success message");
		var failure = DirectorCommands.execute(new DirectorCommandRequestPayload(id, "codex skit duplicate"), dispatcher, operator);
		check(!failure.success() && failure.requestId().equals(id) && failure.message().equals("ACTOR_NAME_TAKEN: Already used"),
				"Brigadier domain failure returns to the submitting form");
		check(!DirectorCommands.execute(new DirectorCommandRequestPayload(id, "codex skit missing"), dispatcher, operator).success(),
				"server command-tree mismatches return a failure instead of a success receipt");
		return 11;
	}

	private static CommandSourceStack source(PermissionSet permissions) {
		return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permissions, "Audit", Component.literal("Audit"), null, null);
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
