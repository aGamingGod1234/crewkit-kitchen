package dev.agaminggod.arenaagents.client.control;

import dev.agaminggod.arenaagents.control.AgentControlAgent;
import dev.agaminggod.arenaagents.control.DirectorSnapshotPayload;
import java.util.List;
import java.util.Optional;

/** Server-confirmed Director state, separate from keybindings and the agent roster. */
public final class DirectorClientState {
	private static DirectorSnapshotPayload snapshot;
	private static List<AgentControlAgent> importCandidates = List.of();
	private DirectorClientState() { }
	public static Optional<DirectorSnapshotPayload> snapshot() { return Optional.ofNullable(snapshot); }
	public static boolean accept(DirectorSnapshotPayload value) {
		boolean changed = !value.equals(snapshot);
		snapshot = value;
		return changed;
	}
	public static List<AgentControlAgent> importCandidates() { return importCandidates; }
	public static void setImportCandidates(List<AgentControlAgent> agents) { importCandidates = List.copyOf(agents); }
	public static void clear() { snapshot = null; importCandidates = List.of(); }
}
