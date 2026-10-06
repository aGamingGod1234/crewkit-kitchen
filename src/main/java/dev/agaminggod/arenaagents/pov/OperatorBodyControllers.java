package dev.agaminggod.arenaagents.pov;

import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.Objects;
import java.util.function.BiFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Seam between the POV session runtime and the Carpet-backed body implementation. Until an implementation is
 * installed, {@link #create} returns a controller that never becomes active, so takeover can be refused cleanly.
 */
public final class OperatorBodyControllers {
	private static final BiFunction<MinecraftServer, AgentId, OperatorBodyController> DEFAULT_FACTORY =
			(server, agentId) -> NoOpController.INSTANCE;
	private static volatile BiFunction<MinecraftServer, AgentId, OperatorBodyController> factory = DEFAULT_FACTORY;

	private OperatorBodyControllers() {
	}

	public static void install(BiFunction<MinecraftServer, AgentId, OperatorBodyController> factory) {
		OperatorBodyControllers.factory = Objects.requireNonNull(factory, "factory must not be null");
	}

	/** The server is passed to the installed factory as given; the default factory ignores it. */
	public static OperatorBodyController create(MinecraftServer server, AgentId agentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		return Objects.requireNonNull(factory.apply(server, agentId), "body controller factory returned null");
	}

	static void reset() {
		factory = DEFAULT_FACTORY;
	}

	private static final class NoOpController implements OperatorBodyController {
		private static final NoOpController INSTANCE = new NoOpController();

		@Override
		public void begin(ServerPlayer operator) {
		}

		@Override
		public void applyFrame(OperatorInputPayload frame) {
		}

		@Override
		public void applyAction(ServerPlayer operator, OperatorActionPayload action) {
		}

		@Override
		public void tick() {
		}

		@Override
		public void end() {
		}

		@Override
		public boolean active() {
			return false;
		}
	}
}
