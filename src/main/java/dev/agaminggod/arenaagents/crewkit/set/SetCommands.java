package dev.agaminggod.arenaagents.crewkit.set;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * /crewkit build [here | at &lt;pos&gt;], /crewkit teardown, /crewkit stage.
 * Shares the /crewkit literal with the core track; Brigadier merges the children.
 */
public final class SetCommands {
	private SetCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(SetBuilder::loadOrigin);
		ServerEntityEvents.ENTITY_LOAD.register(SetBuilder::discardIfStale);
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("crewkit")
				.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
				.then(Commands.literal("build")
						.executes(ctx -> build(ctx, false))
						.then(Commands.literal("here").executes(ctx -> build(ctx, true)))
						.then(Commands.literal("at")
								.then(Commands.argument("origin", BlockPosArgument.blockPos())
										.executes(ctx -> buildAt(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "origin"), ctx.getSource().getLevel())))))
				.then(Commands.literal("teardown").executes(ctx -> teardown(ctx.getSource())))
				.then(Commands.literal("stage").executes(ctx -> stage(ctx.getSource()))));
	}

	private static int build(CommandContext<CommandSourceStack> ctx, boolean here) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		MinecraftServer server = source.getServer();
		SetSavedData data = SetSavedData.get(server);
		if (!here && data.origin != null) {
			return buildAt(source, data.origin, SetBuilder.levelOf(server, data.dimension));
		}
		ServerPlayer player = source.getPlayerOrException();
		// The set always faces north (camera at the open south side), so put it north of the player,
		// centred on them, with the floor flush with their feet and the open front 3 blocks ahead.
		BlockPos feet = player.blockPosition();
		BlockPos origin = feet.offset(-SetBuilder.WIDTH / 2, -1, -(SetBuilder.DEPTH + 2));
		return buildAt(source, origin, player.level());
	}

	private static int buildAt(CommandSourceStack source, BlockPos origin, ServerLevel level) {
		long started = System.nanoTime();
		int placed = SetBuilder.build(level, origin);
		long ms = (System.nanoTime() - started) / 1_000_000L;
		source.sendSuccess(() -> Component.literal("CrewKit kitchen built at " + origin.toShortString()
				+ " (" + placed + " blocks, " + ms + " ms). Camera: ck_player anchor (14,5,19), facing north."), true);
		// A chef summoned earlier follows the kitchen to its new spot.
		if (dev.agaminggod.arenaagents.crewkit.cast.ChefReady.find(
				dev.agaminggod.arenaagents.server.CodexAgentManager.get(source.getServer())).isPresent()) {
			String status = dev.agaminggod.arenaagents.crewkit.cast.ChefReady.ready(source.getServer());
			source.sendSuccess(() -> Component.literal(status), false);
		}
		return placed;
	}

	private static int teardown(CommandSourceStack source) {
		if (!SetBuilder.teardown(source.getServer())) {
			source.sendFailure(Component.literal("No CrewKit kitchen origin saved; nothing to tear down."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("CrewKit kitchen removed and terrain restored."), true);
		return 1;
	}

	/** Demo conditions: noon, clear sky, frozen time and weather, no mob spawning, and the Chef agent at its anchor. */
	private static int stage(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		CommandSourceStack quiet = server.createCommandSourceStack().withSuppressedOutput();
		server.getCommands().performPrefixedCommand(quiet, "time set noon");
		server.getCommands().performPrefixedCommand(quiet, "weather clear");
		GameRules rules = source.getLevel().getGameRules();
		rules.set(GameRules.ADVANCE_TIME, false, server);
		rules.set(GameRules.ADVANCE_WEATHER, false, server);
		rules.set(GameRules.SPAWN_MOBS, false, server);
		rules.set(GameRules.SPAWN_MONSTERS, false, server);
		rules.set(GameRules.SPAWN_PHANTOMS, false, server);
		rules.set(GameRules.SPAWN_PATROLS, false, server);
		rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
		source.sendSuccess(() -> Component.literal("CrewKit stage set: noon, clear, time/weather frozen, mob spawning off."), true);
		try {
			String chef = dev.agaminggod.arenaagents.crewkit.cast.ChefReady.ready(server);
			source.sendSuccess(() -> Component.literal(chef), true);
		} catch (RuntimeException exception) {
			// Staging still counts without an agent; the mannequin chef covers the run.
			source.sendFailure(Component.literal("Chef not ready: " + exception.getMessage()));
		}
		return 1;
	}
}
