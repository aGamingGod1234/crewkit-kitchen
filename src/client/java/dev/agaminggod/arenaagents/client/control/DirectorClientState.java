package dev.agaminggod.arenaagents.client.control;

import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.DirectorSnapshotPayload;
import java.util.List;
import java.util.Optional;

/** Server-confirmed Director state, separate from keybindings and the agent roster. */
public final class DirectorClientState {
	private static DirectorSnapshotPayload snapshot;
	private static dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Request generation;
	private static dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Result generationResult;
	public static boolean generationPending() { return generation != null; }
	public static void beginGeneration(dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Request request) { generation = request; generationResult = null; }
	public static boolean acceptGeneration(dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Result result) {
		if (generation == null || !generation.requestId().equals(result.requestId())) return false;
		generation = null; generationResult = result;
		return true;
	}
	public static Optional<dev.agaminggod.arenaagents.control.DirectorGenerationPayload.Result> takeGenerationResult() {
		var result = generationResult; generationResult = null; return Optional.ofNullable(result);
	}
	private static final java.util.Map<String, String> DRAFTS = new java.util.HashMap<>();
	public static java.util.Map<String, String> drafts() { return DRAFTS; }
	public record ImportCandidate(String agentId, String displayName) { }
	private static List<ImportCandidate> importCandidates = List.of();
	private DirectorClientState() { }
	public static Optional<DirectorSnapshotPayload> snapshot() { return Optional.ofNullable(snapshot); }
	public static boolean accept(DirectorSnapshotPayload value) {
		boolean changed = !value.equals(snapshot);
		snapshot = value;
		return changed;
	}
	public static List<ImportCandidate> importCandidates() { return importCandidates; }
	public static boolean setImportCandidates(List<AgentControlAgent> agents) {
		List<ImportCandidate> next = agents.stream()
				.map(agent -> new ImportCandidate(agent.agentId(), agent.displayName())).toList();
		boolean changed = !next.equals(importCandidates);
		importCandidates = next;
		return changed;
	}
	public static void clear() { snapshot = null; generation = null; generationResult = null; importCandidates = List.of(); DRAFTS.clear(); }
}
