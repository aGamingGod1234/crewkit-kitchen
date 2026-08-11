package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.RespawnPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AgentControlVerification {
	private static final long NOW_EPOCH_MS = 1_750_000_000_000L;
	private static final String AGENT_UUID = "12345678-1234-5678-9abc-123456789abc";

	private AgentControlVerification() {
	}

	public static int verify() {
		int assertions = 0;
		assertions += verifySnapshotRoundTripAndBounds();
		assertions += verifyProviderPresets();
		assertions += verifyCommandConstruction();
		assertions += verifySelectionStability();
		assertions += verifySnapshotOrdering();
		return assertions;
	}

	private static int verifySnapshotRoundTripAndBounds() {
		AgentRecord record = new AgentRecord(
				1,
				new AgentId(UUID.fromString(AGENT_UUID)),
				Optional.of(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")),
				Optional.empty(),
				new AgentProfile("kimi", "kimi-code/k3", "max", Optional.of("Builder"), 2),
				AgentLifecycleState.ACTING,
				Optional.of(AgentGoal.create("Build a safe house", NOW_EPOCH_MS)),
				3L,
				List.of(AgentGoal.create("Collect food", NOW_EPOCH_MS)),
				"Placed the foundation",
				"",
				false,
				RespawnPolicy.PAUSE_UNTIL_RESPAWN,
				NOW_EPOCH_MS,
				NOW_EPOCH_MS,
				""
		);
		AgentControlSnapshot snapshot = AgentControlSnapshot.fromRecords(true, NOW_EPOCH_MS, List.of(record));
		AgentControlSnapshot decoded = AgentControlSnapshotCodec.decode(AgentControlSnapshotCodec.encode(snapshot));
		AgentControlAgent agent = decoded.agents().getFirst();

		assertEquals(snapshot, decoded, "snapshot JSON round trip");
		assertEquals(AGENT_UUID, agent.agentId(), "snapshot carries stable full agent ID");
		assertEquals("Builder", agent.displayName(), "snapshot carries optional user name");
		assertEquals("Build a safe house", agent.currentGoal(), "snapshot carries active goal");
		assertEquals(1, agent.queuedGoalCount(), "snapshot carries queue count");
		assertEquals(false, agent.automaticProgress(), "snapshot carries automatic progress preference");
		assertTrue(agent.entityPresent(), "snapshot carries entity presence");
		expectFailure(() -> AgentControlSnapshotCodec.decode("{\"schemaVersion\":999}"), "unsupported snapshot schema");
		expectFailure(
				() -> new AgentControlSnapshot(true, NOW_EPOCH_MS, java.util.Collections.nCopies(17, agent)),
				"snapshot agent bound"
		);
		return 9;
	}

	private static int verifyProviderPresets() {
		assertEquals(List.of("codex", "gemini", "kimi"), AgentControlCatalog.providers(), "provider order");
		assertEquals("gpt-5.6-sol", AgentControlCatalog.defaultModel("codex"), "Codex model default");
		assertEquals("gemini-3.1-pro", AgentControlCatalog.defaultModel("gemini"), "Gemini model default");
		assertEquals("kimi-code/k3", AgentControlCatalog.defaultModel("kimi"), "Kimi model default");
		assertEquals(List.of("high", "low"), AgentControlCatalog.reasoningEfforts("gemini", "gemini-3.1-pro"),
				"Gemini Pro efforts");
		assertEquals(List.of("high", "medium", "low"),
				AgentControlCatalog.reasoningEfforts("gemini", "gemini-3.6-flash"), "Gemini Flash efforts");
		assertTrue(AgentControlCatalog.models("kimi").contains("kimi-code/k3-256k"),
				"Kimi live 256k model preset");
		assertEquals(List.of("low", "high", "max"), AgentControlCatalog.reasoningEfforts("kimi", "kimi-code/k3-256k"),
				"Kimi K3-256k efforts");
		assertEquals(List.of("low", "high", "max"), AgentControlCatalog.reasoningEfforts("kimi", "kimi-code/k3"),
				"Kimi K3 efforts");
		assertEquals(List.of("high"), AgentControlCatalog.reasoningEfforts("kimi", "kimi-code/kimi-for-coding"),
				"Kimi fixed effort");
		assertEquals(List.of("low", "medium", "high", "xhigh", "max"),
				AgentControlCatalog.reasoningEfforts("codex", "gpt-5.6-sol"), "Codex effort choices");
		expectFailure(() -> AgentControlCatalog.defaultModel("unknown"), "unknown provider");
		return 11;
	}

	private static int verifyCommandConstruction() {
		assertEquals(
				"codex summon-configured codex gpt-5.6-sol high survival \"Builder One\"",
				AgentControlCommandBuilder.summon("codex", "gpt-5.6-sol", "high", "Builder One"),
				"Codex summon command"
		);
		assertEquals(
				"codex summon-configured kimi \"kimi-code/k3\" max survival Scout",
				AgentControlCommandBuilder.summon("kimi", "kimi-code/k3", "max", "Scout"),
				"Kimi summon command"
		);
		assertEquals(
				"codex start " + AGENT_UUID + " Build a safe shelter",
				AgentControlCommandBuilder.prompt("start", AGENT_UUID, "  Build\na safe\tshelter  "),
				"prompt whitespace normalization"
		);
		assertEquals(
				"codex stop " + AGENT_UUID,
				AgentControlCommandBuilder.agent("stop", AGENT_UUID),
				"agent lifecycle command"
		);
		expectFailure(
				() -> AgentControlCommandBuilder.agent("remove;op", AGENT_UUID),
				"operation allowlist"
		);
		expectFailure(
				() -> AgentControlCommandBuilder.prompt("start", AGENT_UUID, " "),
				"blank prompt"
		);
		return 6;
	}

	private static int verifySelectionStability() {
		AgentControlAgent first = agent(AGENT_UUID, "First");
		AgentControlAgent second = agent("87654321-4321-8765-cba9-987654321abc", "Second");
		assertEquals(first.agentId(), AgentControlSelection.resolve("", List.of(first, second)), "select first initially");
		assertEquals(second.agentId(), AgentControlSelection.resolve(second.agentId(), List.of(first, second)),
				"retain existing selection");
		assertEquals(first.agentId(), AgentControlSelection.resolve("missing", List.of(first, second)),
				"fall back after removal");
		assertEquals("", AgentControlSelection.resolve(first.agentId(), List.of()), "clear empty selection");
		return 4;
	}

	private static int verifySnapshotOrdering() {
		AgentControlSnapshotStore store = new AgentControlSnapshotStore();
		AgentControlSnapshot newer = new AgentControlSnapshot(true, NOW_EPOCH_MS + 2L, List.of());
		AgentControlSnapshot older = new AgentControlSnapshot(false, NOW_EPOCH_MS + 1L, List.of());
		assertTrue(store.accept(newer), "new snapshot accepted");
		assertTrue(!store.accept(older), "stale snapshot rejected");
		assertEquals(newer, store.current().orElseThrow(), "stale snapshot does not replace state");
		store.clear();
		assertTrue(store.current().isEmpty(), "disconnect clears snapshot state");
		return 4;
	}

	private static AgentControlAgent agent(String id, String name) {
		return new AgentControlAgent(
				id,
				id.substring(0, 8),
				name,
				"codex",
				"gpt-5.6-sol",
				"high",
				"IDLE",
				"",
				0,
				"",
				"",
				true,
				true
		);
	}

	private static void expectFailure(Runnable operation, String label) {
		try {
			operation.run();
			throw new AssertionError(label + " should fail");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static void assertTrue(boolean actual, String label) {
		if (!actual) {
			throw new AssertionError(label);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
