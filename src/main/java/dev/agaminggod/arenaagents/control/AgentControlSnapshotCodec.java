package dev.agaminggod.arenaagents.control;

import com.google.gson.Gson;
import java.util.Objects;

public final class AgentControlSnapshotCodec {
	private static final Gson GSON = new Gson();
	public static final int MAX_ENCODED_LENGTH = 32_767;

	private AgentControlSnapshotCodec() {
	}

	public static String encode(AgentControlSnapshot snapshot) {
		String encoded = GSON.toJson(Objects.requireNonNull(snapshot, "snapshot must not be null"));
		if (encoded.length() > MAX_ENCODED_LENGTH) {
			throw new IllegalArgumentException("Control snapshot exceeds the wire limit");
		}
		return encoded;
	}

	public static AgentControlSnapshot decode(String encoded) {
		String checked = Objects.requireNonNull(encoded, "encoded must not be null");
		if (checked.length() > MAX_ENCODED_LENGTH) {
			throw new IllegalArgumentException("Control snapshot exceeds the wire limit");
		}
		try {
			AgentControlSnapshot snapshot = GSON.fromJson(checked, AgentControlSnapshot.class);
			if (snapshot == null) {
				throw new IllegalArgumentException("Control snapshot must be a JSON object");
			}
			return new AgentControlSnapshot(
					snapshot.schemaVersion(),
					snapshot.canControl(),
					snapshot.automationAvailable(),
					snapshot.automationStatus(),
					snapshot.generatedAtEpochMs(),
					snapshot.agents(),
					snapshot.catalog()
			);
		} catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid control snapshot", exception);
		}
	}
}
