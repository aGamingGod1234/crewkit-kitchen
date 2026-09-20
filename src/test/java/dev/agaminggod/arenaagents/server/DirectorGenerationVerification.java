package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.control.DirectorGenerationPayload.*;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;

public final class DirectorGenerationVerification {

	private DirectorGenerationVerification() {}

	public static int verify() {
		var start = new SkitPlacement("minecraft:overworld", 0, 70, 0, 0, 0);
		var here = new SkitPlacement("minecraft:overworld", 10, 70, 10, 90, 0);
		String move =
			"{\"action\":\"move\",\"arguments\":\"40\",\"destination\":\"here\",\"right\":0,\"up\":3,\"forward\":0}";
		String wait =
			"{\"action\":\"wait\",\"arguments\":\"20\",\"destination\":\"previous\",\"right\":0,\"up\":0,\"forward\":0}";
		var script = DirectorScriptGeneration.parse(
			"fly",
			"actor",
			"{\"steps\":[" + move + "," + wait + "]}",
			start,
			here
		);
		check(script.steps().size() == 3, "Luna draft contains sequential actions");
		check(
			script.steps().get(1).placement().x() == 10 && script.steps().get(1).placement().y() == 73,
			"here uses the captured operator mark and height offset"
		);
		check(
			script.steps().getLast().placement().equals(script.steps().get(1).placement()),
			"wait preserves previous endpoint"
		);
		check(
			script.steps().stream().skip(1).noneMatch(SkitStep::placeBeforeActions),
			"generated actions never insert implicit teleports"
		);
		for (SkitPlacement displaced : java.util.List.of(
			here,
			new SkitPlacement("minecraft:overworld", -50, 80, -50, 0, 0)
		)) {
			SkitPlacement[] pose = { displaced };
			SkitPlacement[] flightOrigin = { null };
			SkitModeRuntime.Performer performer = new SkitModeRuntime.Performer() {
				public SkitPlacement position() {
					return pose[0];
				}

				public void place(SkitPlacement mark) {
					pose[0] = mark;
				}

				public void perform(
					SkitStep step,
					SkitAction action,
					SkitPlacement origin,
					long elapsed,
					boolean firstTick
				) {
					flightOrigin[0] = origin;
				}

				public void stop(SkitAction action) {}
			};
			SkitModeRuntime.advancePlayback(SkitModeRuntime.Playback.waiting(script.steps(), 0), 0, performer);
			check(
				start.equals(flightOrigin[0]),
				"Replaying from a displaced or previous endpoint restores the captured flight start"
			);
		}
		reject(() -> DirectorScriptGeneration.parse("fly", "actor", "{\"steps\":[]}", start, here));
		reject(() ->
			DirectorScriptGeneration.parse(
				"fly",
				"actor",
				"{\"steps\":[" + move.replace("\"40\"", "\"1201\"") + "]}",
				start,
				here
			)
		);
		reject(() ->
			DirectorScriptGeneration.parse(
				"fly",
				"actor",
				"{\"steps\":[" + move.replace("\"up\":3", "\"up\":129") + "]}",
				start,
				here
			)
		);
		reject(() ->
			DirectorScriptGeneration.parse(
				"fly",
				"actor",
				"{\"steps\":[" + move.replace("\"move\"", "\"shell\"") + "]}",
				start,
				here
			)
		);
		reject(() ->
			DirectorScriptGeneration.parse(
				"fly",
				"actor",
				"{\"steps\":[" + move.replace("\"up\":3", "\"up\":\"NaN\"") + "]}",
				start,
				here
			)
		);
		var buffer = new RegistryFriendlyByteBuf(
			io.netty.buffer.Unpooled.buffer(),
			net.minecraft.core.RegistryAccess.EMPTY
		);
		try {
			var request = new Request(UUID.randomUUID(), UUID.randomUUID(), "new-draft", "Fly here, then pause");
			Request.CODEC.encode(buffer, request);
			check(request.equals(Request.CODEC.decode(buffer)), "request preserves actor, description and correlation");
			var response = new Result(request.requestId(), true, "Ready for review", "new-draft");
			Result.CODEC.encode(buffer, response);
			check(response.equals(Result.CODEC.decode(buffer)), "result preserves completion and script selection");
		} finally {
			buffer.release();
		}
		return 13;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static void reject(Runnable run) {
		try {
			run.run();
		} catch (RuntimeException expected) {
			return;
		}
		throw new AssertionError("Invalid generated action was accepted");
	}
}
