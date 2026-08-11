package dev.agaminggod.arenaagents.server;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.agaminggod.arenaagents.agent.AgentConstants;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.agent.AgentRegistrySnapshotCodec;
import dev.agaminggod.arenaagents.agent.AgentTransition;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AgentSavedData extends SavedData {
	private static final Logger LOGGER = LoggerFactory.getLogger(AgentSavedData.class);
	private static final String PAYLOAD_FIELD = "payload";
	private static final AgentRegistrySnapshotCodec SNAPSHOT_CODEC = new AgentRegistrySnapshotCodec();
	private static final Codec<AgentSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf(PAYLOAD_FIELD).forGetter(AgentSavedData::encodePayload)
	).apply(instance, AgentSavedData::decodePayload));
	public static final SavedDataType<AgentSavedData> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("arenaagents", "codex_agents"),
			AgentSavedData::new,
			CODEC,
			DataFixTypes.SAVED_DATA_COMMAND_STORAGE
	);

	private final AgentRegistry registry;
	private AgentRuntimeHooks runtimeHooks = AgentRuntimeHooks.NO_OP;

	public AgentSavedData() {
		this(new AgentRegistry.Snapshot(
				AgentConstants.SCHEMA_VERSION,
				AgentConstants.DEFAULT_AGENT_LIMIT,
				AgentConstants.DEFAULT_QUEUE_LIMIT,
				List.of()
		));
	}

	private AgentSavedData(AgentRegistry.Snapshot snapshot) {
		boolean recoveredUncertainState = snapshot.records().stream()
				.anyMatch(record -> record.state().isReloadUncertain());
		this.registry = AgentRegistry.restore(
				snapshot,
				this::setDirty,
				this::dispatchTransition,
				System.currentTimeMillis()
		);
		if (recoveredUncertainState) {
			setDirty();
		}
	}

	public static AgentSavedData get(MinecraftServer server) {
		Objects.requireNonNull(server, "server must not be null");
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public AgentRegistry registry() {
		return registry;
	}

	public void setRuntimeHooks(AgentRuntimeHooks runtimeHooks) {
		this.runtimeHooks = Objects.requireNonNull(runtimeHooks, "runtimeHooks must not be null");
	}

	private String encodePayload() {
		return SNAPSHOT_CODEC.encode(registry.snapshot());
	}

	private static AgentSavedData decodePayload(String payload) {
		return new AgentSavedData(SNAPSHOT_CODEC.decode(payload));
	}

	private void dispatchTransition(AgentTransition transition) {
		try {
			runtimeHooks.onTransition(transition);
		} catch (RuntimeException exception) {
			LOGGER.error("Codex agent runtime transition hook failed for {}", transition.after().agentId(), exception);
		}
	}
}
