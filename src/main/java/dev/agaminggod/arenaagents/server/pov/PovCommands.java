package dev.agaminggod.arenaagents.server.pov;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.mixin.CommandNodeAccessor;
import dev.agaminggod.arenaagents.pov.PovMode;
import dev.agaminggod.arenaagents.pov.PovStopPayload;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import dev.agaminggod.arenaagents.server.GoalControl;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /spectate and /takeover, plus the /codex spectate|takeover aliases. Grammar for both:
 * {@code <agent> [start|stop]} (no word after the agent means start). A bare {@code exit} or {@code stop}
 * still ends any session, but is read from the agent argument so suggestions list only agent names;
 * an agent with such a name stays reachable by its id. Vanilla /spectate (which moves the body) is
 * removed so this one replaces it.
 */
public final class PovCommands {
	static final String ARGUMENT_AGENT = "agent";
	private static final Logger LOGGER = LoggerFactory.getLogger(PovCommands.class);
	private static final String VANILLA_SPECTATE = "spectate";
	private static final DynamicCommandExceptionType COMMAND_FAILURE = new DynamicCommandExceptionType(
			message -> Component.literal(String.valueOf(message))
	);

	private PovCommands() {
	}

	/** {@code agentArgument} must produce the shared {@code agent} selector argument. */
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
			Supplier<RequiredArgumentBuilder<CommandSourceStack, String>> agentArgument) {
		removeVanillaSpectate(dispatcher.getRoot());
		dispatcher.register(tree(VANILLA_SPECTATE, PovMode.SPECTATE, agentArgument));
		dispatcher.register(tree("takeover", PovMode.TAKEOVER, agentArgument));
	}

	/** The same tree under /codex, so the in-game console keeps its "codex" command pattern. */
	public static LiteralArgumentBuilder<CommandSourceStack> codexAlias(String literal, PovMode mode,
			Supplier<RequiredArgumentBuilder<CommandSourceStack, String>> agentArgument) {
		return tree(literal, mode, agentArgument);
	}

	/** Runs in Fabric's registration callback, which fires after vanilla registered its own /spectate. */
	private static void removeVanillaSpectate(CommandNode<CommandSourceStack> root) {
		CommandNodeAccessor accessor = (CommandNodeAccessor) root;
		accessor.arenaagents$children().remove(VANILLA_SPECTATE);
		accessor.arenaagents$literals().remove(VANILLA_SPECTATE);
		accessor.arenaagents$arguments().remove(VANILLA_SPECTATE);
	}

	private static LiteralArgumentBuilder<CommandSourceStack> tree(String literal, PovMode mode,
			Supplier<RequiredArgumentBuilder<CommandSourceStack, String>> agentArgument) {
		return grammar(literal, agentArgument, GoalControl::mayControl,
				context -> start(context, mode), context -> exitAgent(context, mode), PovCommands::exit);
	}

	/**
	 * The pure command shape, usable with any source type so it can be verified without a server.
	 * {@code start} runs for {@code <agent>} and {@code <agent> start}, {@code exitAgent} for
	 * {@code <agent> stop} and {@code exitAny} for a bare {@code stop|exit}.
	 */
	static <S> LiteralArgumentBuilder<S> grammar(String literal, Supplier<RequiredArgumentBuilder<S, String>> agentArgument,
			Predicate<S> permission, Command<S> start, Command<S> exitAgent, Command<S> exitAny) {
		Command<S> startOrExit = context -> isExitWord(StringArgumentType.getString(context, ARGUMENT_AGENT))
				? exitAny.run(context) : start.run(context);
		return LiteralArgumentBuilder.<S>literal(literal)
				.requires(permission)
				.then(agentArgument.get()
						.executes(startOrExit)
						.then(LiteralArgumentBuilder.<S>literal("start").executes(start))
						.then(LiteralArgumentBuilder.<S>literal("stop").executes(exitAgent)));
	}

	private static boolean isExitWord(String selector) {
		return selector.equalsIgnoreCase("exit") || selector.equalsIgnoreCase("stop");
	}

	static String exitCommand(PovMode mode) {
		return mode == PovMode.TAKEOVER ? "/takeover exit" : "/spectate exit";
	}

	static String startMessage(PovMode mode, String agentName, boolean alreadyActive) {
		String exitCommand = exitCommand(mode);
		if (alreadyActive) return "You are already in " + agentName + "'s view. Type " + exitCommand + " to return.";
		if (mode == PovMode.TAKEOVER) {
			return "You are controlling " + agentName + ". Its model is paused until you type " + exitCommand
					+ ". Your own body stays here; if it loses 2 hearts the takeover ends.";
		}
		return "Viewing " + agentName + ". Type " + exitCommand + " to return.";
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
			String message = startMessage(mode, session.agentName(), alreadyActive);
			context.getSource().sendSuccess(() -> Component.literal(message), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure(commandName(mode), exception);
		}
	}

	// "<agent> stop": only that agent's session ends, so a typo never drops a different view.
	private static int exitAgent(CommandContext<CommandSourceStack> context, PovMode mode) throws CommandSyntaxException {
		ServerPlayer operator = context.getSource().getPlayerOrException();
		try {
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			CodexAgentManager manager = CodexAgentManager.get(context.getSource().getServer());
			AgentRecord record = manager.resolve(selector);
			PovSession session = PovSessionRuntime.session(operator)
					.filter(current -> current.agentId().equals(record.agentId()))
					.orElseThrow(() -> new AgentDomainException("POV_NOT_ACTIVE",
							"You are not viewing or controlling " + manager.displayName(record) + "; type " + exitCommand(mode)
									+ " to leave your current view"));
			if (!PovSessionRuntime.exit(operator, PovExitReason.MANUAL)) {
				throw new AgentDomainException("POV_NOT_ACTIVE", "You are not viewing or controlling " + session.agentName());
			}
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure(commandName(mode) + " exit", exception);
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

	private static String commandName(PovMode mode) {
		return mode == PovMode.TAKEOVER ? "takeover" : "spectate";
	}

	private static CommandSyntaxException commandFailure(AgentDomainException exception) {
		return COMMAND_FAILURE.create(exception.code() + ": " + exception.getMessage());
	}

	private static CommandSyntaxException unexpectedFailure(String operation, RuntimeException exception) {
		LOGGER.error("Unexpected /{} failure", operation, exception);
		return COMMAND_FAILURE.create("INTERNAL_ERROR: " + operation + " failed; see the server log");
	}
}
