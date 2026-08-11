package dev.agaminggod.arenaagents.server.runtime.transaction;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ActionIdempotencyLedger {
	private final int maximumEntries;
	private final Map<ActionIdentity, RecordedAction> recorded = new LinkedHashMap<>();

	public ActionIdempotencyLedger(int maximumEntries) {
		if (maximumEntries <= 0) throw new IllegalArgumentException("maximumEntries must be positive");
		this.maximumEntries = maximumEntries;
	}

	public static ReplayKey key(AgentId agentId, long goalRevision, String actionId, JsonObject arguments) {
		return new ReplayKey(agentId, goalRevision, actionId, canonicalArgumentHash(arguments));
	}

	public static String canonicalArgumentHash(JsonObject arguments) {
		Objects.requireNonNull(arguments, "arguments must not be null");
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonicalJson(arguments).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public synchronized void record(ReplayKey key, ServerActionResult result) {
		Objects.requireNonNull(key, "key must not be null");
		Objects.requireNonNull(result, "result must not be null");
		if (!key.agentId().equals(result.agentId())
				|| key.goalRevision() != result.goalRevision()
				|| !key.actionId().equals(result.actionId())) {
			throw new IllegalArgumentException("result identity must match replay key");
		}
		ActionIdentity identity = ActionIdentity.from(key);
		RecordedAction existing = recorded.get(identity);
		if (existing != null && !existing.key().argumentHash().equals(key.argumentHash())) {
			throw changedArguments(key);
		}
		recorded.put(identity, new RecordedAction(key, result));
		while (recorded.size() > maximumEntries) {
			recorded.remove(recorded.keySet().iterator().next());
		}
	}

	public synchronized Optional<ServerActionResult> replay(ReplayKey key) {
		Objects.requireNonNull(key, "key must not be null");
		RecordedAction existing = recorded.get(ActionIdentity.from(key));
		if (existing == null) return Optional.empty();
		if (!existing.key().argumentHash().equals(key.argumentHash())) throw changedArguments(key);
		return Optional.of(existing.result());
	}

	public synchronized int size() {
		return recorded.size();
	}

	private static IllegalStateException changedArguments(ReplayKey key) {
		return new IllegalStateException("Action '" + key.actionId() + "' was reused with changed arguments");
	}

	private static String canonicalJson(JsonElement element) {
		if (element == null || element.isJsonNull()) return "null";
		if (element.isJsonPrimitive()) return element.toString();
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			StringBuilder result = new StringBuilder("[");
			for (int index = 0; index < array.size(); index++) {
				if (index > 0) result.append(',');
				result.append(canonicalJson(array.get(index)));
			}
			return result.append(']').toString();
		}
		StringBuilder result = new StringBuilder("{");
		boolean first = true;
		for (String field : element.getAsJsonObject().keySet().stream().sorted().toList()) {
			if (!first) result.append(',');
			first = false;
			result.append(new com.google.gson.JsonPrimitive(field)).append(':');
			result.append(canonicalJson(element.getAsJsonObject().get(field)));
		}
		return result.append('}').toString();
	}

	public record ReplayKey(AgentId agentId, long goalRevision, String actionId, String argumentHash) {
		public ReplayKey {
			Objects.requireNonNull(agentId, "agentId must not be null");
			if (goalRevision < 0L) throw new IllegalArgumentException("goalRevision must not be negative");
			if (actionId == null || actionId.isBlank()) throw new IllegalArgumentException("actionId must not be blank");
			if (argumentHash == null || argumentHash.isBlank()) throw new IllegalArgumentException("argumentHash must not be blank");
		}
	}

	private record ActionIdentity(AgentId agentId, long goalRevision, String actionId) {
		private static ActionIdentity from(ReplayKey key) {
			return new ActionIdentity(key.agentId(), key.goalRevision(), key.actionId());
		}
	}

	private record RecordedAction(ReplayKey key, ServerActionResult result) {
	}
}
