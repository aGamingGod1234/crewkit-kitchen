package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.util.List;

final class DirectorTakeCommands {
	static LiteralArgumentBuilder<CommandSourceStack> commands() {
		var root = Commands.literal("take");
		for (String operation : List.of("create", "play")) root.then(Commands.literal(operation).then(Commands.argument("name",StringArgumentType.string()).executes(c -> execute(c, operation))));
		root.then(Commands.literal("stop").executes(c -> execute(c,"stop")));
		root.then(Commands.literal("camera").then(Commands.argument("name",StringArgumentType.string()).then(Commands.argument("path",StringArgumentType.string()).then(Commands.argument("ticks",IntegerArgumentType.integer(0,72000)).executes(c -> execute(c,"camera"))))));
		return root;
	}
	private static int execute(CommandContext<CommandSourceStack> context, String operation) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		try {
			var source = context.getSource(); var server = source.getServer(); var player = source.getPlayerOrException();
			if (operation.equals("stop")) DirectorTakeRuntime.stop(server,player.getUUID(),"Take stopped");
			else {
				SkitModeRuntime.requireEnabled(server);
				String name = StringArgumentType.getString(context,"name"); var data = SkitModeSavedData.get(server);
				if (operation.equals("create")) {
					if (data.take(name) != null) throw new IllegalArgumentException("A take with that name already exists");
					data.putTake(new DirectorTake(name,List.of(),"",0));
				} else if (operation.equals("play")) DirectorTakeRuntime.play(player,name);
				else {
					var take = data.take(name); if (take == null) throw new IllegalArgumentException("Create or load a take first");
					String path = StringArgumentType.getString(context,"path");
					data.putTake(take.camera(path.equals("-") ? "" : path,IntegerArgumentType.getInteger(context,"ticks")));
				}
			}
			source.sendSuccess(() -> Component.literal(operation.equals("play") ? "Take starts in 3 seconds" : operation.equals("stop") ? "Take stopped" : "Take saved"),false);
			return 1;
		} catch (IllegalArgumentException error) { throw new SimpleCommandExceptionType(Component.literal(error.getMessage())).create(); }
	}
}
