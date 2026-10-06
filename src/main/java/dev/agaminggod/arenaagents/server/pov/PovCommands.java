package dev.agaminggod.arenaagents.server.pov;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.pov.PovMode;
import dev.agaminggod.arenaagents.pov.PovStopPayload;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.GoalControl;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /spectator and /takeover, plus the /codex spectate|takeover aliases. The literal "exit" wins over
 * an agent named "exit" (Brigadier prefers literals); such an agent stays reachable by its id.
 */
public final class PovCommands {
	private static final Logger LOGGER = LoggerFactory.getLogger(PovCommands.class);
	private static final String ARGUMENT_AGENT = "agent";
	private static final DynamicCommandExceptionType COMMAND_FAILURE = new DynamicCommandExceptionType(
			message -> Component.literal(String.valueOf(message))
	);

	private PovCommands() {
	}

	/** {@code agentArgument} must produce the shared {@code agent} selector argument. */
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
			Supplier<RequiredArgumentBuilder<CommandSourceStack, String>> agentArgument) {
		dispatcher.register(tree("spectator", PovMode.SPECTATE, agentArgument));
		dispatcher.register(tree("takeover", PovMode.TAKEOVER, agentArgument));
	}

	/** The same tree under /codex, so the in-game console keeps its "codex" command pattern. */
	public static LiteralArgumentBuilder<CommandSourceStack> codexAlias(String literal, PovMode mode,
			Supplier<RequiredArgumentBuilder<CommandSourceStack, String>> agentArgument) {
		return tree(literal, mode, agentArgument);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> tree(String literal, PovMode mode,
			Supplier<RequiredArgumentBuilder<CommandSourceStack, String>> agentArgument) {
		return Commands.literal(literal)
				.requires(GoalControl::mayControl)
				.then(Commands.literal("exit").executes(PovCommands::exit))
				.then(agentArgument.get().executes(context -> start(context, mode)));
	}

	private static int start(CommandContext<CommandSourceStack> context, PovMode mode) throws CommandSyntaxException {
		ServerPlayer operator = context.getSource().getPlayerOrException();
		try {
			if (!ServerPlayNetworking.canSend(operator, PovStopPayload.TYPE)) {
				throw new AgentDomainException("POV_CLIENT_REQUIRED",
						"Agent view needs the Arena Agents client mod. Install it, then try again");
			}
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			CodexAgentManager manager = CodexAgentManager.get(context.getSource().getServer());
			AgentRecord record = manager.resolve(selector);
			boolean alreadyActive = PovSessionRuntime.session(operator)
					.filter(session -> session.agentId().equals(record.agentId()) && session.mode() == mode)
					.isPresent();
			PovSession session = PovSessionRuntime.start(operator, record.agentId(), mode, selector);
			String exitCommand = mode == PovMode.TAKEOVER ? "/takeover exit" : "/spectator exit";
			String message;
			if (alreadyActive) {
				message = "You are already in " + session.agentName() + "'s view. Type " + exitCommand + " to return.";
			} else if (mode == PovMode.TAKEOVER) {
				message = "You are controlling " + session.agentName() + ". Its model is paused until you type "
						+ exitCommand + ". Your own body stays here; if it loses 2 hearts the takeover ends."
						+ " (This is not vanilla /spectate, which would move your body.)";
			} else {
				message = "Viewing " + session.agentName() + " (read-only). Type " + exitCommand
						+ " to return. (This is not vanilla /spectate, which would move your body.)";
			}
			context.getSource().sendSuccess(() -> Component.literal(message), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure(mode == PovMode.TAKEOVER ? "takeover" : "spectator", exception);
		}
	}

	private static int exit(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer operator = context.getSource().getPlayerOrException();
		try {
			if (!PovSessionRuntime.exit(operator, PovExitReason.MANUAL)) {
				throw new AgentDomainException("POV_NOT_ACTIVE", "You are not viewing or controlling an agent");
			}
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("view exit", exception);
		}
	}

	private static CommandSyntaxException commandFailure(AgentDomainException exception) {
		return COMMAND_FAILURE.create(exception.code() + ": " + exception.getMessage());
	}

	private static CommandSyntaxException unexpectedFailure(String operation, RuntimeException exception) {
		LOGGER.error("Unexpected /{} failure", operation, exception);
		return COMMAND_FAILURE.create("INTERNAL_ERROR: " + operation + " failed; see the server log");
	}
}
