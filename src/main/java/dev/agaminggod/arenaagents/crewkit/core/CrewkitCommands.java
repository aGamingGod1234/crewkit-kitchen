package dev.agaminggod.arenaagents.crewkit.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.agaminggod.arenaagents.server.GoalControl;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /crewkit reset}, {@code /crewkit replay <file>}, {@code /crewkit event <event> <json>}.
 * Gamemaster permission, same as /arenaagent.
 */
final class CrewkitCommands {
	private CrewkitCommands() {}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("crewkit")
						.requires(GoalControl::mayControl)
						.then(Commands.literal("reset").executes(CrewkitCommands::reset))
						.then(Commands.literal("replay")
								.then(Commands.argument("file", StringArgumentType.greedyString()).executes(CrewkitCommands::replay)))
						.then(Commands.literal("event")
								.then(Commands.argument("event", StringArgumentType.word())
										.executes(context -> event(context, "{}"))
										.then(Commands.argument("json", StringArgumentType.greedyString())
												.executes(context -> event(context, StringArgumentType.getString(context, "json"))))))
		));
	}

	private static int reset(CommandContext<CommandSourceStack> context) {
		CrewkitDispatcher.resetAll(context.getSource().getServer(), true);
		context.getSource().sendSuccess(() -> Component.literal("CrewKit reset"), true);
		return 1;
	}

	private static int replay(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "file").strip();
		Path file = CrewkitReplay.resolve(source.getServer(), name);
		if (!Files.isRegularFile(file)) {
			source.sendFailure(Component.literal("CrewKit replay: no file " + file));
			return 0;
		}
		try {
			int count = CrewkitReplay.start(source.getServer(), file);
			source.sendSuccess(() -> Component.literal("CrewKit replay: " + count + " events from " + file.getFileName()), true);
			return count;
		} catch (Exception e) {
			source.sendFailure(Component.literal("CrewKit replay failed: " + e.getMessage()));
			return 0;
		}
	}

	private static int event(CommandContext<CommandSourceStack> context, String json) {
		CommandSourceStack source = context.getSource();
		String event = StringArgumentType.getString(context, "event");
		JsonObject data;
		try {
			data = JsonParser.parseString(json).getAsJsonObject();
		} catch (Exception e) {
			source.sendFailure(Component.literal("CrewKit event: data must be a JSON object"));
			return 0;
		}
		CrewkitDispatcher.dispatch(source.getServer(), event, data, -1);
		source.sendSuccess(() -> Component.literal("CrewKit event " + event + " sent"), false);
		return 1;
	}
}
