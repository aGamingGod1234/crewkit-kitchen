package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import dev.agaminggod.arenaagents.server.group.AgentGroup;
import dev.agaminggod.arenaagents.server.group.AgentGroupSpawnCoordinator;
import dev.agaminggod.arenaagents.server.goal.GoalDraftChoice;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import dev.agaminggod.arenaagents.server.voice.VoiceConsentRegistry;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CodexAgentCommands {
	private static final Logger LOGGER = LoggerFactory.getLogger(CodexAgentCommands.class);
	private static final String DEFAULT_MODEL = "gpt-5.6-luna";
	private static final String DEFAULT_REASONING = "xhigh";
	private static final String DEFAULT_CODEX_SERVICE_TIER = "fast";
	private static final String PROVIDER_CODEX = "codex";
	private static final String PROVIDER_GEMINI = "gemini";
	private static final String PROVIDER_KIMI = "kimi";
	private static final String PROVIDER_CURSOR = "cursor";
	private static final String ARGUMENT_AGENT = "agent";
	private static final String ARGUMENT_GAME_MODE = "game_mode";
	private static final String ARGUMENT_DRAFT = "draft_id";
	private static final String ARGUMENT_GROUP = "group";
	private static final String ARGUMENT_GROUP_MEMBERS = "members";
	private static final String ARGUMENT_MODEL = "model";
	private static final String ARGUMENT_NAME = "name";
	private static final String ARGUMENT_MESSAGE = "message";
	private static final String ARGUMENT_PROVIDER = "provider";
	private static final String ARGUMENT_PROMPT = "prompt";
	private static final String ARGUMENT_REASONING = "reasoning";
	private static final String ARGUMENT_SERVICE_TIER = "speed_mode";
	private static final DynamicCommandExceptionType COMMAND_FAILURE = new DynamicCommandExceptionType(
			message -> Component.literal(String.valueOf(message))
	);

	private CodexAgentCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("agent").then(goalDraftCommands()));
		dispatcher.register(
				Commands.literal("verbose")
						.requires(GoalControl::mayControl)
						.then(Commands.literal("on").executes(context -> verbose(context, true)))
						.then(Commands.literal("off").executes(context -> verbose(context, false)))
		);
		dispatcher.register(
				Commands.literal("codex")
						.then(Commands.literal("summon")
								.requires(GoalControl::mayControl)
								.executes(context -> summon(context, PROVIDER_CODEX, DEFAULT_MODEL, DEFAULT_REASONING, Optional.empty()))
								.then(providerSummon(PROVIDER_GEMINI))
								.then(providerSummon(PROVIDER_KIMI))
								.then(providerSummon(PROVIDER_CURSOR))
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
						.then(Commands.literal("dm")
								.then(agentArgument().then(
										Commands.argument(ARGUMENT_MESSAGE, StringArgumentType.greedyString())
												.executes(CodexAgentCommands::directMessage)
								)))
						.then(Commands.literal("group")
								.requires(GoalControl::mayControl)
								.then(Commands.literal("save")
										.then(Commands.argument(ARGUMENT_GROUP, StringArgumentType.string())
												.then(Commands.argument(ARGUMENT_GROUP_MEMBERS, StringArgumentType.greedyString())
														.executes(CodexAgentCommands::saveGroup))))
								.then(Commands.literal("spawn")
										.then(groupArgument().executes(CodexAgentCommands::spawnGroup)))
								.then(Commands.literal("delete")
										.then(groupArgument().executes(CodexAgentCommands::deleteGroup))))
						.then(Commands.literal("voice-consent")
								.then(Commands.literal("on").executes(context -> voiceConsent(context, true)))
								.then(Commands.literal("off").executes(context -> voiceConsent(context, false)))
								.then(Commands.literal("status").executes(CodexAgentCommands::voiceConsentStatus)))
						.then(goalDraftCommands())
						.then(promptCommand("start", CodexAgentManager::start))
						.then(agentCommand("stop", CodexAgentManager::stop))
						.then(agentCommand("resume", CodexAgentManager::resume))
						.then(Commands.literal("respawn")
								.requires(GoalControl::mayControl)
								.then(agentArgument().executes(CodexAgentCommands::respawn)))
						.then(promptCommand("queue", CodexAgentManager::queue))
						.then(promptCommand("steer", (manager, selector, prompt, ignored) -> manager.steer(selector, prompt)))
						.then(Commands.literal("status")
								.requires(GoalControl::mayControl)
								.executes(CodexAgentCommands::statusAll)
								.then(agentArgument().executes(CodexAgentCommands::statusOne)))
						.then(Commands.literal("list").requires(GoalControl::mayControl).executes(CodexAgentCommands::statusAll))
						.then(Commands.literal("remove")
								.requires(GoalControl::mayControl)
								.then(agentArgument().executes(CodexAgentCommands::remove)))
						.then(Commands.literal("auto")
								.requires(GoalControl::mayControl)
								.then(agentArgument().executes(CodexAgentCommands::toggleAutomatic)))
		);
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> goalDraftCommands() {
		return Commands.literal("goal")
				.then(goalDraftChoice("confirm", GoalDraftChoice.CONFIRM))
				.then(goalDraftChoice("replace", GoalDraftChoice.REPLACE))
				.then(goalDraftChoice("queue", GoalDraftChoice.QUEUE))
				.then(goalDraftChoice("cancel", GoalDraftChoice.CANCEL))
				.then(Commands.literal("complete")
						.requires(GoalControl::mayControl)
						.then(agentArgument().executes(CodexAgentCommands::confirmCompletion)));
	}

	private static int confirmCompletion(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentRecord record = manager(context).resolve(StringArgumentType.getString(context, ARGUMENT_AGENT));
			CodexAgentServerRuntime.confirmCurrentGoal(context.getSource().getServer(), record.agentId());
			context.getSource().sendSuccess(
					() -> Component.literal("Confirmed completion for " + manager(context).displayName(record) + "."),
					false
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("goal completion confirmation", exception);
		}
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> goalDraftChoice(
			String literal,
			GoalDraftChoice choice
	) {
		return Commands.literal(literal).then(
				Commands.argument(ARGUMENT_DRAFT, StringArgumentType.word())
						.executes(context -> resolveGoalDraft(context, choice))
		);
	}

	private static int resolveGoalDraft(
			CommandContext<CommandSourceStack> context,
			GoalDraftChoice choice
	) throws CommandSyntaxException {
		try {
			if (choice != GoalDraftChoice.CANCEL) {
				CodexAgentServerRuntime.requireAutomation(context.getSource().getServer());
			}
			UUID draftId = UUID.fromString(StringArgumentType.getString(context, ARGUMENT_DRAFT));
			UUID actorId = context.getSource().getEntity() instanceof ServerPlayer player
					? player.getUUID()
					: new UUID(0L, 0L);
			Optional<CodexAgentManager.GoalDraftResult> resolved = manager(context).resolveGoalDraft(
					draftId, actorId, GoalControl.mayControl(context.getSource()), choice);
			if (resolved.isEmpty()) {
				context.getSource().sendSuccess(() -> Component.literal("Goal draft was already resolved."), false);
				return 0;
			}
			CodexAgentManager.GoalDraftResult result = resolved.orElseThrow();
			result.transition().ifPresent(transition -> reportTransition(
					context,
					switch (result.operation()) {
						case START -> "start";
						case REPLACE -> "replace";
						case QUEUE -> "queue";
						case CANCEL -> "cancel";
					},
					transition
			));
			if (result.operation() == dev.agaminggod.arenaagents.server.goal.GoalDraftResolution.Operation.CANCEL) {
				context.getSource().sendSuccess(() -> Component.literal("Cancelled goal draft " + draftId + "."), false);
			}
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (IllegalArgumentException exception) {
			throw COMMAND_FAILURE.create("INVALID_GOAL_DRAFT_ID: Draft ID must be a UUID");
		} catch (RuntimeException exception) {
			throw unexpectedFailure("goal draft", exception);
		}
	}

	private static int verbose(CommandContext<CommandSourceStack> context, boolean enabled) {
		CodexAgentServerRuntime.setVerbose(context.getSource().getServer(), enabled);
		context.getSource().sendSuccess(
				() -> Component.literal("Verbose agent activity " + (enabled ? "enabled" : "disabled")
						+ " for operators for this server session."),
				false
		);
		return 1;
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> configuredSummon() {
		return Commands.literal("summon-configured").requires(GoalControl::mayControl).then(
				Commands.argument(ARGUMENT_PROVIDER, StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								List.of(PROVIDER_CODEX, PROVIDER_GEMINI, PROVIDER_KIMI, PROVIDER_CURSOR), builder))
						.then(Commands.argument(ARGUMENT_MODEL, AgentModelArgumentType.model()).then(
								Commands.argument(ARGUMENT_REASONING, StringArgumentType.word()).then(
										Commands.argument(ARGUMENT_SERVICE_TIER, StringArgumentType.word())
												.suggests((context, builder) -> SharedSuggestionProvider.suggest(
														List.of("priority", "fast"), builder))
												.then(Commands.argument(ARGUMENT_GAME_MODE, StringArgumentType.word())
												.suggests((context, builder) -> SharedSuggestionProvider.suggest(
														List.of("survival", "creative", "adventure"), builder))
												.then(Commands.argument(ARGUMENT_NAME, StringArgumentType.string())
														.executes(context -> summon(
																context,
																StringArgumentType.getString(context, ARGUMENT_PROVIDER),
														StringArgumentType.getString(context, ARGUMENT_MODEL),
														StringArgumentType.getString(context, ARGUMENT_REASONING),
														StringArgumentType.getString(context, ARGUMENT_SERVICE_TIER),
														Optional.of(StringArgumentType.getString(context, ARGUMENT_NAME)).filter(value -> !value.isBlank()),
														AgentGameMode.parse(StringArgumentType.getString(context, ARGUMENT_GAME_MODE))
												)))))
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
		return Commands.literal(literal).requires(GoalControl::mayControl).then(
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
		return Commands.literal(literal).requires(GoalControl::mayControl).then(
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
		return summon(context, provider, model, reasoning,
				PROVIDER_CODEX.equals(provider) ? DEFAULT_CODEX_SERVICE_TIER : "priority", userName, gameMode);
	}

	private static int summon(
			CommandContext<CommandSourceStack> context,
			String provider,
			String model,
			String reasoning,
			String serviceTier,
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
			CodexAgentManager manager = manager(context);
			AgentRecord record = manager.summon(
					context.getSource().getLevel(),
					summonPosition,
					provider,
					model,
					reasoning,
					serviceTier,
					userName,
					gameMode
			);
			context.getSource().sendSuccess(
					() -> Component.literal("Creating " + manager.displayName(record) + ". It will be ready when its player joins."),
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
			CodexAgentServerRuntime.requireAutomation(context.getSource().getServer());
			String selector = StringArgumentType.getString(context, ARGUMENT_AGENT);
			String prompt = StringArgumentType.getString(context, ARGUMENT_PROMPT);
			AgentTransition transition = operation.apply(manager(context), selector, prompt, context.getSource().getLevel());
			reportTransition(context, operationName, transition);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure(operationName, exception);
		}
	}

	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> groupArgument() {
		return Commands.argument(ARGUMENT_GROUP, StringArgumentType.string())
				.suggests((context, builder) -> SharedSuggestionProvider.suggest(
						manager(context).groups().stream()
								.map(AgentGroup::name)
								.map(StringArgumentType::escapeIfRequired)
								.toList(),
						builder
				));
	}

	private static int directMessage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
				throw new AgentDomainException("PLAYER_REQUIRED", "Only an in-game player can send an agent DM");
			}
			CodexAgentManager manager = manager(context);
			AgentRecord target = manager.resolve(StringArgumentType.getString(context, ARGUMENT_AGENT));
			CodexAgentServerRuntime.sendDirectMessage(
					context.getSource().getServer(),
					player,
					target.agentId(),
					StringArgumentType.getString(context, ARGUMENT_MESSAGE)
			);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("dm", exception);
		}
	}

	private static int saveGroup(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			List<AgentId> members = Arrays.stream(StringArgumentType.getString(context, ARGUMENT_GROUP_MEMBERS).strip().split("\\s+"))
					.filter(value -> !value.isBlank())
					.map(AgentId::parse)
					.toList();
			AgentGroup group = manager(context).saveGroup(
					StringArgumentType.getString(context, ARGUMENT_GROUP),
					members
			);
			context.getSource().sendSuccess(
					() -> Component.literal("Saved " + group.name() + " with " + group.memberIds().size()
							+ (group.memberIds().size() == 1 ? " agent." : " agents.")),
					false
			);
			return group.memberIds().size();
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("group save", exception);
		}
	}

	private static int spawnGroup(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			String name = StringArgumentType.getString(context, ARGUMENT_GROUP);
			AgentGroupSpawnCoordinator.Result result = manager(context).spawnGroup(name);
			String missing = result.missingIds().isEmpty() ? ""
					: " Missing: " + String.join(", ", result.missingIds().stream().map(AgentId::shortValue).toList()) + ".";
			context.getSource().sendSuccess(
					() -> Component.literal("Group " + name + ": " + result.present() + " already present, "
							+ result.restoring() + " restoring." + missing),
					false
			);
			return result.present() + result.restoring();
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("group spawn", exception);
		}
	}

	private static int deleteGroup(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			AgentGroup group = manager(context).deleteGroup(StringArgumentType.getString(context, ARGUMENT_GROUP));
			context.getSource().sendSuccess(() -> Component.literal("Deleted saved group " + group.name() + "."), false);
			return 1;
		} catch (AgentDomainException exception) {
			throw commandFailure(exception);
		} catch (RuntimeException exception) {
			throw unexpectedFailure("group delete", exception);
		}
	}

	private static int voiceConsent(CommandContext<CommandSourceStack> context, boolean enabled) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(context);
		requireVoiceAvailable(context.getSource());
		if (enabled) VoiceConsentRegistry.grant(context.getSource().getServer(), player.getUUID());
		else VoiceConsentRegistry.revoke(context.getSource().getServer(), player.getUUID());
		context.getSource().sendSuccess(
				() -> Component.literal(voiceConsentConfirmation(enabled)),
				false
		);
		return enabled ? 1 : 0;
	}

	static String voiceConsentConfirmation(boolean enabled) {
		return "Agent voice transcription " + (enabled ? "enabled for this session." : "disabled.");
	}

	private static int voiceConsentStatus(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(context);
		requireVoiceAvailable(context.getSource());
		boolean enabled = VoiceConsentRegistry.granted(context.getSource().getServer(), player.getUUID());
		context.getSource().sendSuccess(
				() -> Component.literal("Agent voice transcription is " + (enabled ? "enabled." : "disabled.")),
				false
		);
		return enabled ? 1 : 0;
	}

	private static void requireVoiceAvailable(CommandSourceStack source) throws CommandSyntaxException {
		if (!VoiceSubsystemRuntime.available(source.getServer())) {
			throw COMMAND_FAILURE.create(voiceUnavailableMessage());
		}
	}

	static String voiceUnavailableMessage() {
		return "VOICE_UNAVAILABLE: Install and start the Arena Agents Voice add-on with Simple Voice Chat";
	}

	private static ServerPlayer requirePlayer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		if (context.getSource().getEntity() instanceof ServerPlayer player) return player;
		throw COMMAND_FAILURE.create("PLAYER_REQUIRED: Only an in-game player can change voice consent");
	}

	private static int runAgentOperation(
			CommandContext<CommandSourceStack> context,
			String operationName,
			AgentOperation operation
	) throws CommandSyntaxException {
		try {
			if (operationName.equals("resume")) {
				CodexAgentServerRuntime.requireAutomation(context.getSource().getServer());
			}
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
			context.getSource().sendSuccess(() -> Component.literal("You have not created any agents yet."), false);
			return 0;
		}
		for (AgentRecord record : records) {
			context.getSource().sendSuccess(() -> Component.literal(formatStatus(record)), false);
		}
		return records.size();
	}

	private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		try {
			CodexAgentManager manager = manager(context);
			AgentRecord removed = manager.remove(StringArgumentType.getString(context, ARGUMENT_AGENT));
			context.getSource().sendSuccess(
					() -> Component.literal("Removed " + manager.displayName(removed) + "."),
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
			CodexAgentManager manager = manager(context);
			AgentRecord record = manager.requestRespawn(StringArgumentType.getString(context, ARGUMENT_AGENT));
			context.getSource().sendSuccess(
					() -> Component.literal("Respawning " + manager.displayName(record) + "..."),
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
		String name = manager(context).displayName(transition.after());
		String message = switch (operation) {
			case "start" -> "Starting a task for " + name + "...";
			case "queue" -> "Added a task to " + name + "'s queue.";
			case "replace" -> "Replacing " + name + "'s current task...";
			case "steer" -> "Updating " + name + "'s current task...";
			case "stop" -> "Paused " + name + ".";
			case "resume" -> "Resuming " + name + "...";
			default -> "Updated " + name + ".";
		};
		context.getSource().sendSuccess(
				() -> Component.literal(message),
				false
		);
	}

	private static String formatStatus(AgentRecord record) {
		String currentGoal = record.currentGoal().map(goal -> goal.prompt()).orElse("none");
		return dev.agaminggod.arenaagents.agent.AgentIdentity.displayName(record.agentId(), record.profile())
				+ " | " + dev.agaminggod.arenaagents.control.AgentControlPresentation.stateLabel(record.state().name())
				+ ". Current task: " + currentGoal
				+ ". Queued tasks: " + record.queuedGoals().size() + ".";
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
		AgentTransition apply(CodexAgentManager manager, String selector, String prompt, ServerLevel sourceLevel);
	}

	@FunctionalInterface
	private interface AgentOperation {
		AgentTransition apply(CodexAgentManager manager, String selector);
	}
}
