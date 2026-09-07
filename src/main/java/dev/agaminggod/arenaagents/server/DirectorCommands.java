package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.agaminggod.arenaagents.control.DirectorCommandRequestPayload;
import dev.agaminggod.arenaagents.control.DirectorCommandResultPayload;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/** Executes only skit commands with the submitting player's identity and permissions. */
public final class DirectorCommands {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(DirectorCommands.class);
	private DirectorCommands() { }

	public static DirectorCommandResultPayload execute(DirectorCommandRequestPayload request,
			CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source) {
		if (!GoalControl.mayControl(source)) return result(request, false, "You do not have permission to control skit actors");
		if (!request.command().equals("codex skit") && !request.command().startsWith("codex skit "))
			return result(request, false, "Only skit commands can be submitted from the Director");
		Feedback feedback = new Feedback(source);
		try {
			dispatcher.execute(request.command(), source.withSource(feedback));
			return result(request, true, feedback.message);
		} catch (CommandSyntaxException exception) {
			Component error = Component.literal(exception.getRawMessage().getString());
			source.sendFailure(error);
			return result(request, false, error.getString());
		} catch (RuntimeException exception) {
			LOGGER.warn("Director request failed: requestId={}", request.requestId(), exception);
			String message = "Could not complete the skit command. Check the server log for details";
			source.sendFailure(Component.literal(message));
			return result(request, false, message);
		}
	}

	private static DirectorCommandResultPayload result(DirectorCommandRequestPayload request, boolean success, String message) {
		return new DirectorCommandResultPayload(request.requestId(), success, message);
	}

	private static final class Feedback implements CommandSource {
		private final CommandSourceStack original;
		private String message = "Skit command accepted";
		private Feedback(CommandSourceStack original) { this.original = original; }
		@Override public void sendSystemMessage(Component value) { message = value.getString(); original.sendSystemMessage(value); }
		@Override public boolean acceptsSuccess() { return true; }
		@Override public boolean acceptsFailure() { return true; }
		@Override public boolean shouldInformAdmins() { return false; }
		@Override public boolean alwaysAccepts() { return true; }
	}
}
