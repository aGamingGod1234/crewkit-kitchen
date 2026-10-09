package dev.agaminggod.arenaagents.crewkit.cast;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /crewkit chef ready | &lt;player&gt; | clear : ready summons or reuses the real Chef agent at the anchor; who wears the chef skin and gets walked around.
 * /crewkit cast brief [names] | item | completed | reset : rehearse the cast without the coordinator
 * (animation needs the CrewKit dispatcher to be ticking the feature).
 */
public final class CastCommands {
	private CastCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
		ChefReady.register();
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("crewkit")
				.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
				.then(Commands.literal("chef")
						.then(Commands.literal("ready").executes(context -> {
							try {
								String status = ChefReady.ready(context.getSource().getServer());
								context.getSource().sendSuccess(() -> Component.literal(status), true);
								return 1;
							} catch (RuntimeException exception) {
								context.getSource().sendFailure(Component.literal("Chef not ready: " + exception.getMessage()));
								return 0;
							}
						}))
						.then(Commands.literal("clear").executes(context -> {
							CastFeature.assignChef(context.getSource().getServer(), null);
							context.getSource().sendSuccess(() -> Component.literal("CrewKit chef cleared"), true);
							return 1;
						}))
						.then(Commands.argument("player", EntityArgument.player()).executes(context -> {
							ServerPlayer player = EntityArgument.getPlayer(context, "player");
							CastFeature.assignChef(context.getSource().getServer(), player.getScoreboardName());
							context.getSource().sendSuccess(() -> Component.literal(
									"CrewKit chef is now " + player.getScoreboardName()), true);
							return 1;
						})))
				.then(Commands.literal("cast")
						.then(Commands.literal("brief")
								.executes(context -> brief(context.getSource(),
										"James John Sandy Guest4 Guest5 Guest6 Guest7 Guest8 Guest9 Guest10 Guest11 Guest12"))
								.then(Commands.argument("names", StringArgumentType.greedyString())
										.executes(context -> brief(context.getSource(),
												StringArgumentType.getString(context, "names")))))
						.then(Commands.literal("item").executes(context -> event(context.getSource(), "item_added")))
						.then(Commands.literal("completed").executes(context -> event(context.getSource(), "completed")))
						.then(Commands.literal("reset").executes(context -> event(context.getSource(), "reset")))));
	}

	private static int brief(CommandSourceStack source, String names) {
		JsonArray guests = new JsonArray();
		for (String name : names.trim().split("\\s+")) {
			if (name.isEmpty()) continue;
			JsonObject guest = new JsonObject();
			guest.addProperty("name", name);
			guests.add(guest);
		}
		JsonObject data = new JsonObject();
		data.add("guests", guests);
		new CastFeature().onEvent(source.getServer(), "brief", data, 0);
		source.sendSuccess(() -> Component.literal("CrewKit cast: " + guests.size() + " guests on their way"), true);
		return guests.size();
	}

	private static int event(CommandSourceStack source, String event) {
		new CastFeature().onEvent(source.getServer(), event, new JsonObject(), 0);
		source.sendSuccess(() -> Component.literal("CrewKit cast: " + event), true);
		return 1;
	}
}
