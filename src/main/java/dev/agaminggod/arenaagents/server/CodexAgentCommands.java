package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentCommands {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentCommands.class);
	private static final String DEFAULT_MODEL = "gpt-5.6-sol";
	private static final String DEFAULT_REASONING = "high";
	private static final String PROVIDER_CODEX = "codex";
	private static final String PROVIDER_GEMINI = "gemini";
	private static final String PROVIDER_KIMI = "kimi";
	private static final String ARGUMENT_AGENT = "agent";
	private static final String ARGUMENT_GAME_MODE = "game_mode";
	private static final String ARGUMENT_MODEL = "model";
	private static final String ARGUMENT_NAME = "name";
	private static final String ARGUMENT_PROVIDER = "provider";
	private static final String ARGUMENT_PROMPT = "prompt";
	private static final String ARGUMENT_REASONING = "reasoning";
	private static final DynamicCommandExceptionType COMMAND_FAILURE = new DynamicCommandExceptionType(
			message -> Component.literal(String.valueOf(message))
	);

	private CodexAgentCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(
				Commands.literal("codex")
						.requires(GoalControl::mayControl)
						.then(Commands.literal("summon")
								.executes(context -> summon(context, PROVIDER_CODEX, DEFAULT_MODEL, DEFAULT_REASONING, Optional.empty()))
								.then(providerSummon(PROVIDER_GEMINI))
								.then(providerSummon(PROVIDER_KIMI))
								.then(Commands.argument(ARGUMENT_MODEL, AgentModelArgumentType.model())
										.then(Commands.argument(ARGUMENT_REASONING, StringArgumentType.word())
												.executes(context -> summon(
												context,
												PROVIDER_CODEX,
												StringArgumentType.getString(context, ARGUMENT_MODEL),
														StringArgumentType.getString(context, ARGUMENT_REASONING),
														Optional.empty()
												))
												.then(Commands.argument(ARGUMENT_NAME, StringArgumentType.string())
														.executes(context -> summon(
														context,
														PROVIDER_CODEX,
														StringArgumentType.getString(context, ARGUMENT_MODEL),
																StringArgumentType.getString(context, ARGUMENT_REASONING),
																Optional.of(StringArgumentType.getString(context, ARGUMENT_NAME))
														))))))
						.then(configuredSummon())
						.then(promptCommand("start", CodexAgentManager::start))
						.then(agentCommand("stop", CodexAgentManager::stop))
						.then(agentCommand("resume", CodexAgentManager::resume))
						.then(Commands.literal("respawn")
								.then(agentArgument().executes(CodexAgentCommands::respawn)))
						.then(promptCommand("queue", CodexAgentManager::queue))
						.then(promptCommand("steer", CodexAgentManager::steer))
						.then(Commands.literal("status")
								.executes(CodexAgentCommands::statusAll)
								.then(agentArgument().executes(CodexAgentCommands::statusOne)))
						.then(Commands.literal("list").executes(CodexAgentCommands::statusAll))
						.then(Commands.literal("remove")
								.then(agentArgument().executes(CodexAgentCommands::remove)))
						.then(Commands.literal("auto")
								.then(agentArgument().executes(CodexAgentCommands::toggleAutomatic)))
		);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> configuredSummon() {
		return Commands.literal("summon-configured").then(
				Commands.argument(ARGUMENT_PROVIDER, StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								List.of(PROVIDER_CODEX, PROVIDER_GEMINI, PROVIDER_KIMI), builder))
						.then(Commands.argument(ARGUMENT_MODEL, AgentModelArgumentType.model()).then(
								Commands.argument(ARGUMENT_REASONING, StringArgumentType.word()).then(
										Commands.argument(ARGUMENT_GAME_MODE, StringArgumentType.word())
												.suggests((context, builder) -> SharedSuggestionProvider.suggest(
														List.of("survival", "creative", "adventure"), builder))
												.then(Commands.argument(ARGUMENT_NAME, StringArgumentType.string())
														.executes(context -> summon(
																context,
																StringArgumentType.getString(context, ARGUMENT_PROVIDER),
																StringArgumentType.getString(context, ARGUMENT_MODEL),
																StringArgumentType.getString(context, ARGUMENT_REASONING),
																Optional.of(StringArgumentType.getString(context, ARGUMENT_NAME)).filter(value -> !value.isBlank()),
																AgentGameMode.parse(StringArgumentType.getString(context, ARGUMENT_GAME_MODE))
														))))
		)));
	}
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> providerSummon(String provider) {
		return Commands.literal(provider).then(
				Commands.argument(ARGUMENT_MODEL, AgentModelArgumentType.model()).then(
						Commands.argument(ARGUMENT_REASONING, StringArgumentType.word())
								.executes(context -> summon(
										context,
										provider,
										StringArgumentType.getString(context, ARGUMENT_MODEL),
										StringArgumentType.getString(context, ARGUMENT_REASONING),
										Optional.empty()
								))
								.then(Commands.argument(ARGUMENT_NAME, StringArgumentType.string())
										.executes(context -> summon(
												context,
												provider,
												StringArgumentType.getString(context, ARGUMENT_MODEL),
												StringArgumentType.getString(context, ARGUMENT_REASONING),
												Optional.of(StringArgumentType.getString(context, ARGUMENT_NAME))
										)))
				)
		);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> promptCommand(
			String literal,
			PromptOperation operation
	) {
		return Commands.literal(literal).then(
				agentArgument().then(
						Commands.argument(ARGUMENT_PROMPT, StringArgumentType.greedyString())
								.executes(context -> runPromptOperation(context, literal, operation))
				)
		);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> agentCommand(
			String literal,
			AgentOperation operation
	) {
		return Commands.literal(literal).then(
				agentArgument().executes(context -> runAgentOperation(context, literal, operation))
		);
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> agentArgument() {
		return Commands.argument(ARGUMENT_AGENT, StringArgumentType.word())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						manager(context).selectors(),
						builder
				));
	}

	private static int summon(
			CommandContext<CommandSourceStack> context,
			String provider,
			String model,
			String reasoning,
			Optional<String> userName
	)
			throws CommandSyntaxException {
		return summon(context, provider, model, reasoning, userName, AgentGameMode.SURVIVAL);
	}

	private static int summon(
			CommandContext<CommandSourceStack> context,
			String provider,
			String model,
			String reasoning,
			Optional<String> userName,
			AgentGameMode gameMode
	)
			throws CommandSyntaxException {
		try {
			Vec3 summonPosition = context.getSource().getPosition();
			if (context.getSource().getEntity() != null) {
				summonPosition = AgentSpawnPlacement.availableNear(
						context.getSource().getLevel(),
						summonPosition,
						context.getSource().getEntity().getLookAngle()
				);
			}
			AgentRecord record = manager(context).summon(
					context.getSource().getLevel(),
					summonPosition,
					provider,
					model,
					reasoning,
					userName,
					gameMode
			);
			context.getSource().sendSuccess(
					() -> Component.literal("Summoned " + provider + " agent " + formatIdentity(record)),
					false
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("summon", exception);
		}
	}

	private static int runPromptOperation(
			CommandContext<CommandSourceStack> context,
			String operationName,
			PromptOperation operation
	) throws CommandSyntaxException {
		try {
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			String prompt = StringArgumentType.getString(context, ARGUMENT_PROMPT);
			AgentTransition transition = operation.apply(manager(context), selector, prompt);
			reportTransition(context, operationName, transition);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure(operationName, exception);
		}
	}

	private static int runAgentOperation(
			CommandContext<CommandSourceStack> context,
			String operationName,
			AgentOperation operation
	) throws CommandSyntaxException {
		try {
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			AgentTransition transition = operation.apply(manager(context), selector);
			reportTransition(context, operationName, transition);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure(operationName, exception);
		}
	}

	private static int statusOne(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentRecord record = manager(context).resolve(StringArgumentType.getString(context, ARGUMENT_AGENT));
			context.getSource().sendSuccess(() -> Component.literal(formatStatus(record)), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("status", exception);
		}
	}

	private static int statusAll(CommandContext<CommandSourceStack> context) {
		List<AgentRecord> records = manager(context).records();
		if (records.isEmpty()) {
			context.getSource().sendSuccess(() -> Component.literal("No AI agents are registered"), false);
			return 0;
		}
		for (AgentRecord record : records) {
			context.getSource().sendSuccess(() -> Component.literal(formatStatus(record)), false);
		}
		return records.size();
	}

	private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentRecord removed = manager(context).remove(StringArgumentType.getString(context, ARGUMENT_AGENT));
			context.getSource().sendSuccess(
					() -> Component.literal("Removed AI agent " + formatIdentity(removed)),
					false
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("remove", exception);
		}
	}

	private static int respawn(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentRecord record = manager(context).respawn(
					StringArgumentType.getString(context, ARGUMENT_AGENT),
					context.getSource().getLevel(),
					context.getSource().getPosition()
			);
			context.getSource().sendSuccess(
					() -> Component.literal("Respawned AI agent " + formatIdentity(record)),
					false
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("respawn", exception);
		}
	}

	private static int toggleAutomatic(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			boolean enabled = manager(context).toggleAutomaticProgress(selector);
			context.getSource().sendSuccess(
					() -> Component.literal("Automatic agent progress " + (enabled ? "enabled" : "disabled")),
					false
			);
			return enabled ? 1 : 0;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("auto", exception);
		}
	}

	private static void reportTransition(
			CommandContext<CommandSourceStack> context,
			String operation,
			AgentTransition transition
	) {
		context.getSource().sendSuccess(
				() -> Component.literal(
						"AI agent " + operation + " accepted for " + formatIdentity(transition.after())
								+ " (state=" + transition.after().state()
								+ ", revision=" + transition.after().goalRevision() + ")"
				),
				false
		);
	}

	private static String formatStatus(AgentRecord record) {
		String currentGoal = record.currentGoal().map(goal -> goal.prompt()).orElse("none");
		return formatIdentity(record)
				+ " state=" + record.state()
				+ " revision=" + record.goalRevision()
				+ " queued=" + record.queuedGoals().size()
				+ " goal=" + currentGoal;
	}

	private static String formatIdentity(AgentRecord record) {
		String name = record.profile().userName().map(value -> value + "/").orElse("");
		return name + record.agentId().shortValue() + " [" + record.profile().nameTag() + "]";
	}

	private static CodexAgentManager manager(CommandContext<CommandSourceStack> context) {
		return CodexAgentManager.get(context.getSource().getServer());
	}

	private static CommandSyntaxException commandFailure(AgentDomainException exception) {
		return COMMAND_FAILURE.create(exception.code() + ": " + exception.getMessage());
	}

	private static CommandSyntaxException unexpectedFailure(String operation, RuntimeException exception) {
		LOGGER.error("Unexpected /codex {} failure", operation, exception);
		return COMMAND_FAILURE.create("INTERNAL_ERROR: " + operation + " failed; see the server log");
	}

	@FunctionalInterface
	private interface PromptOperation {
		AgentTransition apply(CodexAgentManager manager, String selector, String prompt);
	}

	@FunctionalInterface
	private interface AgentOperation {
		AgentTransition apply(CodexAgentManager manager, String selector);
	}
}
