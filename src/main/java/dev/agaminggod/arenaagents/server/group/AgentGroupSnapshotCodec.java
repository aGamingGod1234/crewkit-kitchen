package dev.agaminggod.arenaagents.server.group;

import com.google.gson.Gson;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.Objects;

public final class AgentGroupSnapshotCodec {
	private static final Gson GSON = new Gson();
	public static final int MAX_ENCODED_LENGTH = 32_767;

	private AgentGroupSnapshotCodec() {
	}

	public static String encode(AgentGroupRegistry.Snapshot snapshot) {
		String encoded = GSON.toJson(Objects.requireNonNull(snapshot, "snapshot must not be null"));
		if (encoded.length() > MAX_ENCODED_LENGTH) {
			throw new AgentDomainException("GROUP_SNAPSHOT_TOO_LARGE", "Saved groups exceed the storage limit");
		}
		return encoded;
	}

	public static AgentGroupRegistry.Snapshot decode(String encoded) {
		String checked = Objects.requireNonNull(encoded, "encoded must not be null");
		if (checked.length() > MAX_ENCODED_LENGTH) {
			throw new AgentDomainException("GROUP_SNAPSHOT_TOO_LARGE", "Saved groups exceed the storage limit");
		}
		try {
			AgentGroupRegistry.Snapshot decoded = GSON.fromJson(checked, AgentGroupRegistry.Snapshot.class);
			if (decoded == null) throw new IllegalArgumentException("snapshot must be a JSON object");
			return new AgentGroupRegistry.Snapshot(decoded.schemaVersion(), decoded.groups());
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new AgentDomainException("INVALID_GROUP_SNAPSHOT", "Saved-group data is invalid");
		}
	}
}
