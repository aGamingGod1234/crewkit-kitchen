package dev.agaminggod.arenaagents.server.pov;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.bridge.MultiplexedServerBridge;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** A /takeover reservation refuses the model's detached (no-task) body actions on the operator's body. */
public final class DetachedActionReservationVerification {
	private DetachedActionReservationVerification() {
	}

	public static int verify() {
		MinecraftServer server = allocateServer();
		AgentRecord idle = new AgentSavedData().registry().create(
				"codex", "gpt-5.6-sol", "high", Optional.of("ReservedIdle"), AgentGameMode.SURVIVAL, 5_000L);
		ActionProvenance provenance = new ActionProvenance(
				"codex", "gpt-5.6-sol", "high", "priority", "native-danger", 1L, "step-fight", 1L, "trace-reserved");
		ServerActionRequest fight = new ServerActionRequest(idle.agentId(), idle.goalRevision(), "reserved-fight",
				ActionType.FIGHT_TARGET, new JsonObject(), provenance, "trace-reserved");
		ServerActionRequest breakBlock = new ServerActionRequest(idle.agentId(), idle.goalRevision(), "reserved-break",
				ActionType.BREAK_BLOCK, new JsonObject(), provenance, "trace-reserved");
		int assertions = 0;
		MultiplexedServerBridge.requireDetachedBodyActionAllowed(server, idle, fight);
		assertions++;
		if (MultiplexedServerBridge.acceptsActionRevision(idle, breakBlock)) {
			throw new AssertionError("with no task only self-preservation runs detached; breaking needs takeTask");
		}
		assertions++;
		UUID operator = UUID.randomUUID();
		AgentControlReservations.reserve(server, idle.agentId(), operator);
		try {
			MultiplexedServerBridge.requireDetachedBodyActionAllowed(server, idle, fight);
			throw new AssertionError("a takeover of an idle agent must refuse the model's detached action");
		} catch (AgentDomainException expected) {
			if (!AgentControlReservations.RESERVED_CODE.equals(expected.code())) throw new AssertionError("wrong refusal " + expected.code());
		} finally {
			AgentControlReservations.release(server, idle.agentId(), operator);
		}
		assertions++;
		MultiplexedServerBridge.requireDetachedBodyActionAllowed(server, idle, fight);
		assertions++;
		AgentControlReservations.releaseAll(server);
		return assertions;
	}

	private static MinecraftServer allocateServer() {
		try {
			java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
			// Identity is all the reservation table needs; no server is started.
			return (MinecraftServer) unsafe.allocateInstance(net.minecraft.server.dedicated.DedicatedServer.class);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not allocate a reservation server key", exception);
		}
	}
}
