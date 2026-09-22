package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import java.util.Optional;
import java.util.UUID;

public final class PendingDragonKillVerification {
	public static int verify() {
		AgentSavedData data = new AgentSavedData();
		AgentRecord created = data.registry().create("gpt-5.6-luna", "low", Optional.of("DragonCredit"), 1000);
		var id = created.agentId();
		data.registry().start(id, "Kill dragon", 1001);
		var goalId = data.registry().require(id).currentGoal().orElseThrow().goalId();
		UUID dragonId = UUID.randomUUID();
		var pending = new AgentSavedData.PendingDragonKill(dragonId, "minecraft:the_end", id, goalId);
		data.stageDragonKill(pending);
		data.stageDragonKill(pending);
		data.registry().stop(id, 1002);
		data.registry().resume(id, 1003);
		data = roundTrip(data);
		if (!data.finishDragonKill(dragonId, "minecraft:overworld", true).isEmpty()) throw new AssertionError("dimension mismatch consumed credit");
		if (!data.finishDragonKill(dragonId, "minecraft:the_end", true).equals(Optional.of(id))) throw new AssertionError("reload and revision change lost logical goal credit");
		data = roundTrip(data);
		if (!data.finishDragonKill(dragonId, "minecraft:the_end", true).isEmpty()) throw new AssertionError("duplicate completion credited after reload");
		data.stageDragonKill(pending);
		if (!data.finishDragonKill(dragonId, "minecraft:the_end", false).isEmpty()) throw new AssertionError("discard credited as kill");
		if (!data.finishDragonKill(dragonId, "minecraft:the_end", true).isEmpty()) throw new AssertionError("discard left pending credit");
		data.stageDragonKill(pending);
		data.registry().stop(id, 1004);
		data.registry().start(id, "Different goal", 1005);
		if (!data.finishDragonKill(dragonId, "minecraft:the_end", true).isEmpty()) throw new AssertionError("new goal inherited old kill");
		return 6;
	}

	@SuppressWarnings("unchecked")
	private static AgentSavedData roundTrip(AgentSavedData data) {
		try {
			var field = AgentSavedData.class.getDeclaredField("CODEC");
			field.setAccessible(true);
			var codec = (Codec<AgentSavedData>) field.get(null);
			return codec.parse(JsonOps.INSTANCE, codec.encodeStart(JsonOps.INSTANCE, data).getOrThrow()).getOrThrow();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}
}
