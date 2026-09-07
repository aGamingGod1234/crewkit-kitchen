package dev.agaminggod.arenaagents.client.control;

import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.DirectorSnapshotPayload;
import java.util.List;
import java.util.Optional;

/** Server-confirmed Director state, separate from keybindings and the agent roster. */
public final class DirectorClientState {
	private static DirectorSnapshotPayload snapshot;
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
	public static void clear() { snapshot = null; importCandidates = List.of(); }
}
