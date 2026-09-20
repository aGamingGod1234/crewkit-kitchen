package dev.agaminggod.arenaagents.server;

import com.google.gson.*;
import dev.agaminggod.arenaagents.control.DirectorGenerationPayload.*;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Luna writes an editable draft; only explicit playback ever moves an actor. */
public final class DirectorScriptGeneration {

	private record Pending(
		UUID operator,
		Request request,
		SkitActor actor,
		SkitPlacement start,
		SkitPlacement here,
		long deadline
	) {}

	private static final Map<MinecraftServer, Map<UUID, Pending>> PENDING = new HashMap<>();

	private DirectorScriptGeneration() {}

	public static void request(ServerPlayer player, Request request) {
		MinecraftServer server = player.level().getServer();
		boolean reserved = false;
		try {
			if (!GoalControl.mayControl(player.createCommandSourceStack())) throw new IllegalArgumentException(
				"You do not have permission to generate skits"
			);
			SkitModeRuntime.requireEnabled(server);
			var pending = PENDING.computeIfAbsent(server, ignored -> new HashMap<>());
			if (
				pending
					.values()
					.stream()
					.anyMatch(p -> p.operator().equals(player.getUUID()))
			) throw new IllegalArgumentException("Your previous Luna draft is still being written");
			if (pending.containsKey(request.requestId())) throw new IllegalArgumentException(
				"That request is already being generated"
			);
			if (pending.size() >= 4) throw new IllegalArgumentException("Luna is busy. Try again shortly");
			if (SkitModeSavedData.get(server).script(request.name()) != null) throw new IllegalArgumentException(
				"Choose a new script name so your saved work is preserved"
			);
			SkitActor actor = SkitActors.resolve(server, request.actorId().toString());
			ServerPlayer body = SkitActors.find(server, actor.agentId())
				.filter(ServerPlayer::isAlive)
				.orElseThrow(() -> new IllegalArgumentException("Respawn the actor before generating a script"));
			SkitPlacement start = SkitPlacement.fromPlayer(body),
				here = SkitPlacement.fromPlayer(player);
			if (!start.dimension().equals(here.dimension())) throw new IllegalArgumentException(
				"Stand in the same dimension as the actor"
			);
			Pending value = new Pending(
				player.getUUID(),
				request,
				actor,
				start,
				here,
				System.currentTimeMillis() + 120_000
			);
			pending.put(request.requestId(), value);
			reserved = true;
			JsonObject wire = new JsonObject();
			wire.addProperty("requestId", request.requestId().toString());
			wire.addProperty("description", request.description());
			wire.addProperty("actorName", actor.name());
			if (!CodexAgentServerRuntime.requestDirectorScript(server, wire)) {
				pending.remove(request.requestId());
				throw new IllegalArgumentException(
					"The coordinator is offline. Start it before asking Luna to write a script"
				);
			}
		} catch (RuntimeException failure) {
			if (reserved) PENDING.get(server).remove(request.requestId());
			send(player, new Result(request.requestId(), false, message(failure), request.name()));
		}
	}

	public static void accept(MinecraftServer server, JsonObject payload) {
		UUID id;
		try {
			id = UUID.fromString(payload.get("requestId").getAsString());
		} catch (RuntimeException invalid) {
			return;
		}
		Map<UUID, Pending> requests = PENDING.get(server);
		Pending pending = requests == null ? null : requests.remove(id);
		if (pending == null) return;
		ServerPlayer operator = server.getPlayerList().getPlayer(pending.operator());
		if (operator == null) return;
		try {
			if (System.currentTimeMillis() > pending.deadline()) throw new IllegalArgumentException(
				"Luna took too long. Generate a new draft"
			);
			if (!GoalControl.mayControl(operator.createCommandSourceStack())) throw new IllegalArgumentException(
				"You no longer have permission to generate skits"
			);
			SkitModeRuntime.requireEnabled(server);
			if (
				!SkitActors.resolve(server, pending.actor().agentId().toString()).equals(pending.actor())
			) throw new IllegalArgumentException("The selected actor changed. Generate a new draft");
			if (
				SkitActors.find(server, pending.actor().agentId()).filter(ServerPlayer::isAlive).isEmpty()
			) throw new IllegalArgumentException("The actor was removed or died. Respawn and generate again");
			String error = payload.get("error").getAsString();
			if (!error.isEmpty()) throw new IllegalArgumentException(error);
			var data = SkitModeSavedData.get(server);
			if (data.script(pending.request().name()) != null) throw new IllegalArgumentException(
				"That script name was used while Luna was writing. Choose a new name"
			);
			SkitScript script = parse(
				pending.request().name(),
				pending.actor().agentId().toString(),
				payload.get("script").getAsString(),
				pending.start(),
				pending.here()
			);
			for (var step : script.steps())
				for (var action : step.actions())
					if (
						action.type() == SkitAction.Type.EQUIP &&
						!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(
							net.minecraft.resources.Identifier.parse(action.itemId())
						)
					) throw new IllegalArgumentException("Luna chose an unknown item: " + action.itemId());
			data.putScript(script);
			send(
				operator,
				new Result(
					id,
					true,
					"Luna finished " +
						script.name() +
						" for " +
						pending.actor().name() +
						". Review the actions, then play when ready.",
					script.name()
				)
			);
		} catch (RuntimeException failure) {
			send(operator, new Result(id, false, message(failure), pending.request().name()));
		}
	}

	static SkitScript parse(String name, String actor, String text, SkitPlacement start, SkitPlacement here) {
		if (text.length() > 32768) throw new IllegalArgumentException("Luna returned too much script data");
		JsonObject root = JsonParser.parseString(text).getAsJsonObject();
		if (!root.keySet().equals(Set.of("steps"))) throw new IllegalArgumentException(
			"Luna returned an invalid script"
		);
		JsonArray rows = root.getAsJsonArray("steps");
		if (rows.isEmpty() || rows.size() > 64) throw new IllegalArgumentException(
			"Generated scripts must contain 1-64 actions"
		);
		List<SkitStep> steps = new ArrayList<>();
		steps.add(new SkitStep(0, start));
		SkitPlacement previous = start;
		int duration = 0;
		for (JsonElement value : rows) {
			JsonObject row = value.getAsJsonObject();
			if (
				!row.keySet().equals(Set.of("action", "arguments", "destination", "right", "up", "forward"))
			) throw new IllegalArgumentException("Luna returned an invalid action");
			String arguments = row.get("arguments").getAsString();
			if (arguments.length() > 128) throw new IllegalArgumentException("Generated action arguments are too long");
			String actionName = row.get("action").getAsString();
			SkitAction action = CodexAgentCommands.parseSkitAction(actionName, arguments);
			if (actionName.equals("move") && action.type() != SkitAction.Type.MOVE) throw new IllegalArgumentException(
				"Generated flying movements require a duration without walking inputs"
			);
			if (
				action.durationTicks() > 1200 || (duration += action.durationTicks()) > 6000
			) throw new IllegalArgumentException(
				"Generated scripts may last at most five minutes, with actions up to one minute"
			);
			SkitPlacement base = switch (row.get("destination").getAsString()) {
				case "start" -> start;
				case "here" -> here;
				case "previous" -> previous;
				default -> throw new IllegalArgumentException("Unknown generated destination");
			};
			double right = offset(row, "right"),
				up = offset(row, "up"),
				forward = offset(row, "forward");
			if (
				action.type() != SkitAction.Type.MOVE && (right != 0 || up != 0 || forward != 0)
			) throw new IllegalArgumentException("Only a flying movement can change its destination");
			SkitPlacement target =
				action.type() == SkitAction.Type.MOVE ? SkitPlacement.relativeTo(base, right, up, forward) : previous;
			steps.add(new SkitStep(0, target, List.of(action), false));
			if (action.type() == SkitAction.Type.MOVE) previous = target;
		}
		return new SkitScript(name, actor, steps);
	}

	private static double offset(JsonObject row, String key) {
		double value = row.get(key).getAsDouble();
		if (!Double.isFinite(value) || Math.abs(value) > 128) throw new IllegalArgumentException(
			"Generated movements must stay within 128 blocks of a mark"
		);
		return value;
	}

	private static String message(RuntimeException failure) {
		return failure.getMessage() == null
			? "Luna returned an invalid draft. Try a more specific description"
			: failure.getMessage();
	}

	private static void send(ServerPlayer player, Result result) {
		if (ServerPlayNetworking.canSend(player, Result.TYPE)) ServerPlayNetworking.send(player, result);
		player.sendSystemMessage(Component.literal(result.message()));
	}

	public static void tick(MinecraftServer server) {
		var pending = PENDING.get(server);
		if (pending == null) return;
		var iterator = pending.values().iterator();
		while (iterator.hasNext()) {
			var p = iterator.next();
			if (System.currentTimeMillis() <= p.deadline()) continue;
			iterator.remove();
			var player = server.getPlayerList().getPlayer(p.operator());
			if (player != null) send(
				player,
				new Result(
					p.request().requestId(),
					false,
					"Luna timed out. Try again when the coordinator is ready",
					p.request().name()
				)
			);
		}
	}

	public static void release(MinecraftServer server) {
		PENDING.remove(server);
	}
}
