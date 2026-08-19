package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.agent.AgentGoal;
import dev.agaminggod.arenaagents.agent.AgentGameMode;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.agent.AgentLifecycleState;
import dev.agaminggod.arenaagents.agent.AgentProfile;
import dev.agaminggod.arenaagents.agent.AgentRecord;
import dev.agaminggod.arenaagents.agent.AgentVisualIdentity;
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
		assertions += verifyWorldNamePolicy();
		assertions += verifyProviderPresets();
		assertions += verifyRuntimeCatalogBecomesAuthoritative();
		assertions += verifyCommandConstruction();
		assertions += verifySelectionStability();
		assertions += verifyActionSafety();
		assertions += verifySnapshotOrdering();
		return assertions;
	}

	private static int verifyWorldNamePolicy() {
		assertEquals(Optional.of("⌁ Rook · Sol"),
				AgentWorldNamePolicy.tag(worldAgent("Rook", "codex", "gpt-5.6-sol", 0)),
				"named Codex agent gets a friendly world tag");
		assertEquals(Optional.of("⌁ Rook · Sol WM"),
				AgentWorldNamePolicy.tag(worldAgent("Rook", "codex", "gpt-5.6-sol-wm", 0)),
				"world tag uses the canonical exact-model short label rather than only its visual family");
		assertEquals(Optional.of("✦ Astra · 3.1 Pro"),
				AgentWorldNamePolicy.tag(worldAgent("Astra", "gemini", "gemini-3.1-pro", 1)),
				"named Gemini agent gets a provider-specific world tag");
		assertEquals(Optional.of("☾ Luna · K3 256K"),
				AgentWorldNamePolicy.tag(worldAgent("Luna", "kimi", "kimi-code/k3-256k", 2)),
				"named Kimi agent gets the exact family label");
		assertEquals(Optional.of("➤ Dash · Grok 4.6"),
				AgentWorldNamePolicy.tag(worldAgent("Dash", "cursor", "grok-4.6", 3)),
				"named Cursor agent keeps Cursor identity");
		assertTrue(AgentWorldNamePolicy.tag(worldAgent("", "codex", "gpt-5.6-sol", 0)).isEmpty(),
				"empty friendly name stays hidden");
		assertTrue(AgentWorldNamePolicy.tag(worldAgent("   ", "cursor", "composer-2.5", 0)).isEmpty(),
				"whitespace-only friendly name stays hidden");
		return 7;
	}

	private static AgentControlAgent worldAgent(
			String friendlyName,
			String provider,
			String model,
			int skinVariant
	) {
		return new AgentControlAgent(
				AGENT_UUID,
				AGENT_UUID.substring(0, 8),
				friendlyName.isBlank() ? "Agent" : friendlyName,
				friendlyName,
				provider,
				model,
				"high",
				"c00_12345678",
				skinVariant,
				"IDLE",
				"",
				0,
				"",
				"",
				true,
				true
		);
	}

	private static int verifySnapshotRoundTripAndBounds() {
		AgentRecord namedRecord = new AgentRecord(
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
				Optional.empty(),
				NOW_EPOCH_MS,
				NOW_EPOCH_MS,
				""
		);
		List<AgentRecord> records = List.of(
				namedRecord,
				record("193a9add-1234-5678-9abc-123456789abc", "codex", "gpt-5.6-sol", "high", 2),
				record("293a9add-1234-5678-9abc-123456789abc", "gemini", "gemini-3.1-pro", "high", 1),
				record("393a9add-1234-5678-9abc-123456789abc", "kimi", "kimi-code/k3", "max", 3),
				record("493a9add-1234-5678-9abc-123456789abc", "cursor", "composer-2.5", "high", 0)
		);
		AgentControlSnapshot snapshot = AgentControlSnapshot.fromRecords(true, NOW_EPOCH_MS, records);
		String encoded = AgentControlSnapshotCodec.encode(snapshot);
		AgentControlSnapshot decoded = AgentControlSnapshotCodec.decode(encoded);
		AgentControlAgent agent = decoded.agents().getFirst();
		AgentControlAgent unnamed = decoded.agents().get(1);
		AgentControlAgent normalizedLegacyVariant = AgentControlSnapshot.fromRecords(
				true,
				NOW_EPOCH_MS,
				List.of(record("593a9add-1234-5678-9abc-123456789abc", "codex", "gpt-5.6-sol", "high", 6))
		).agents().getFirst();

		assertEquals(snapshot, decoded, "snapshot JSON round trip");
		assertEquals(6, decoded.schemaVersion(), "schema 6 identity snapshot round trip");
		assertEquals(AGENT_UUID, agent.agentId(), "snapshot carries stable full agent ID");
		assertEquals("Builder", agent.displayName(), "named snapshot carries the canonical operator display name");
		assertEquals("Builder", agent.friendlyName(), "named snapshot carries the exact explicit friendly name separately");
		assertEquals("Sol GTqa3RI0VniavBI0VniavA", unnamed.displayName(),
				"unnamed snapshot carries the stable ID-aware canonical display name");
		assertEquals("", unnamed.friendlyName(), "unnamed snapshot carries a blank explicit friendly name");
		assertEquals(2, normalizedLegacyVariant.skinVariant(),
				"legacy persisted variants normalize before crossing the control snapshot boundary");
		assertEquals(List.of("kimi", "codex", "gemini", "kimi", "cursor"),
				decoded.agents().stream().map(AgentControlAgent::provider).toList(),
				"schema 6 round-trips all four providers without changing the named record");
		assertEquals("Build a safe house", agent.currentGoal(), "snapshot carries active goal");
		assertEquals(1, agent.queuedGoalCount(), "snapshot carries queue count");
		assertEquals(false, agent.automaticProgress(), "snapshot carries automatic progress preference");
		assertTrue(agent.entityPresent(), "snapshot carries entity presence");
		assertTrue(decoded.automationAvailable(), "legacy ready snapshot reports available automation");
		assertEquals("Automation ready", decoded.automationStatus(), "snapshot carries a human-readable readiness message");
		expectFailure(
				() -> AgentControlSnapshotCodec.decode(encoded
						.replaceFirst("\\\"schemaVersion\\\":6", "\\\"schemaVersion\\\":5")),
				"schema 5 control snapshot"
		);
		expectFailure(
				() -> AgentControlSnapshotCodec.decode(encoded
						.replaceFirst("\\\"schemaVersion\\\":6", "\\\"schemaVersion\\\":\\\"6\\\"")),
				"string control snapshot schema"
		);
		expectFailure(
				() -> AgentControlSnapshotCodec.decode(encoded
						.replaceFirst("\\\"schemaVersion\\\":6", "\\\"schemaVersion\\\":6.9")),
				"fractional control snapshot schema"
		);
		expectFailure(
				() -> AgentControlSnapshotCodec.decode(encoded
						.replaceFirst("\\\"schemaVersion\\\":6", "\\\"schemaVersion\\\":4294967302")),
				"overflowing control snapshot schema"
		);
		expectFailure(() -> AgentControlSnapshotCodec.decode("{}"), "missing control snapshot schema");
		expectFailure(() -> AgentControlSnapshotCodec.decode("{\"schemaVersion\":999}"), "unsupported snapshot schema");
		expectFailure(
				() -> new AgentControlSnapshot(true, NOW_EPOCH_MS, java.util.Collections.nCopies(17, agent)),
				"snapshot agent bound"
		);
		expectFailure(() -> new AgentControlAgent(
				agent.agentId(), agent.shortId(), agent.displayName(), agent.friendlyName(), agent.provider(), agent.model(),
				agent.reasoning(), agent.playerName(), AgentVisualIdentity.INDIVIDUAL_VARIANT_COUNT, agent.state(),
				agent.currentGoal(), agent.queuedGoalCount(), agent.lastSummary(), agent.lastError(),
				agent.automaticProgress(), agent.entityPresent()
		), "manifest skin variant bound");
		return 22;
	}

	private static AgentRecord record(
			String id,
			String provider,
			String model,
			String reasoning,
			int skinVariant
	) {
		return new AgentRecord(
				1,
				new AgentId(UUID.fromString(id)),
				Optional.empty(),
				Optional.empty(),
				new AgentProfile(provider, model, reasoning, Optional.empty(), skinVariant),
				AgentLifecycleState.IDLE,
				Optional.empty(),
				0L,
				List.of(),
				"",
				"",
				true,
				RespawnPolicy.PAUSE_UNTIL_RESPAWN,
				Optional.empty(),
				NOW_EPOCH_MS,
				NOW_EPOCH_MS,
				""
		);
	}

	private static int verifyProviderPresets() {
		assertEquals(List.of("codex", "gemini", "kimi", "cursor"), AgentControlCatalog.providers(), "provider order");
		assertEquals("gpt-5.6-sol", AgentControlCatalog.defaultModel("codex"), "Codex model default");
		assertEquals("gemini-3.1-pro", AgentControlCatalog.defaultModel("gemini"), "Gemini model default");
		assertEquals("kimi-code/k3", AgentControlCatalog.defaultModel("kimi"), "Kimi model default");
		assertEquals("composer-2.5", AgentControlCatalog.defaultModel("cursor"), "Cursor model default");
		assertEquals(List.of("low", "medium", "high", "xhigh"), AgentControlCatalog.reasoningEfforts("cursor", "grok-4.6"),
				"Cursor Grok effort choices");
		assertEquals(List.of("high"), AgentControlCatalog.reasoningEfforts("cursor", "composer-2.5"),
				"Composer exposes speed without inventing a thinking control");
		assertTrue(AgentControlCatalog.hasSpeedMode("cursor", "composer-2.5"),
				"Cursor exposes its native fast model override");
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
		assertEquals("K2.7 Coding Highspeed",
				AgentControlCatalog.displayName("kimi", "kimi-code/kimi-for-coding-highspeed"),
				"Kimi aliases use the installed CLI display name instead of a guessed label");
		assertEquals(List.of("low", "medium", "high", "xhigh", "max", "ultra"),
				AgentControlCatalog.reasoningEfforts("codex", "gpt-5.6-sol"), "Codex effort choices");
		assertEquals(List.of("priority", "fast"), AgentControlCatalog.serviceTiers("codex", "gpt-5.6-sol"),
				"Codex speed choices");
		assertTrue(AgentControlCatalog.hasSpeedMode("codex", "gpt-5.6-sol"),
				"Codex exposes speed mode only when the live profile advertises it");
		assertTrue(!AgentControlCatalog.hasSpeedMode("gemini", "gemini-3.1-pro"),
				"providers without a speed capability do not show a fake speed choice");
		expectFailure(() -> AgentControlCatalog.defaultModel("unknown"), "unknown provider");
		return 18;
	}

	private static int verifyCommandConstruction() {
		assertEquals(
				"codex summon-configured codex gpt-5.6-sol high priority survival \"Builder One\"",
				AgentControlCommandBuilder.summon("codex", "gpt-5.6-sol", "high", "Builder One"),
				"Codex summon command"
		);
		assertEquals(
				"codex summon-configured kimi \"kimi-code/k3\" max priority survival Scout",
				AgentControlCommandBuilder.summon("kimi", "kimi-code/k3", "max", "Scout"),
				"Kimi summon command"
		);
		assertEquals(
				"codex summon-configured codex gpt-5.6-sol ultra fast survival Speedy",
				AgentControlCommandBuilder.summon(
						"codex", "gpt-5.6-sol", "ultra", "fast", "Speedy", AgentGameMode.SURVIVAL),
				"Codex fast-mode command"
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
				() -> AgentControlCommandBuilder.agent("respawn", AGENT_UUID),
				"respawn is model-only"
		);
		expectFailure(
				() -> AgentControlCommandBuilder.prompt("start", AGENT_UUID, " "),
				"blank prompt"
		);
		return 7;
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
		assertEquals(first.agentId(), AgentControlSelection.move(second.agentId(), -1, List.of(first, second)),
				"previous moves both the visual anchor and command selection");
		assertEquals(second.agentId(), AgentControlSelection.move(first.agentId(), 1, List.of(first, second)),
				"next moves both the visual anchor and command selection");
		assertEquals("Remove 2 agents? First and Second and their saved state will be removed.",
				AgentControlSelection.removalDescription(List.of(first, second)),
				"batch removal confirmation identifies the exact destructive scope");
		assertEquals(second.agentId(), AgentControlSelection.resolveSelected(
				"missing", java.util.Set.of(second.agentId()), List.of(first, second)
		), "snapshot refresh keeps the visual anchor inside the command selection");
		return 8;
	}

	private static int verifyRuntimeCatalogBecomesAuthoritative() {
		List<AgentControlModelOption> live = List.of(
				new AgentControlModelOption(
						"codex", "gpt-future", "GPT Future", List.of("medium", "ultra"), List.of("priority", "fast")
				),
				new AgentControlModelOption(
						"kimi", "kimi-code/live", "Kimi Live", List.of("high"), List.of()
				)
		);
		try {
			AgentControlCatalog.installRuntimeCatalog(live);
			assertEquals(List.of("codex", "kimi"), AgentControlCatalog.providers(),
					"runtime catalog controls provider order");
			assertEquals(List.of("gpt-future"), AgentControlCatalog.models("codex"),
					"runtime catalog controls model choices");
			assertEquals("GPT Future", AgentControlCatalog.displayName("codex", "gpt-future"),
					"runtime catalog preserves provider display names");
			assertEquals(List.of("medium", "ultra"), AgentControlCatalog.reasoningEfforts("codex", "gpt-future"),
					"runtime catalog controls reasoning choices");
			assertEquals(List.of("priority", "fast"), AgentControlCatalog.serviceTiers("codex", "gpt-future"),
					"runtime catalog controls speed choices");
			AgentControlSnapshot snapshot = new AgentControlSnapshot(
					AgentControlSnapshot.SCHEMA_VERSION, true, true, "Automation ready", NOW_EPOCH_MS, List.of(), live
			);
			assertEquals(live, AgentControlSnapshotCodec.decode(AgentControlSnapshotCodec.encode(snapshot)).catalog(),
					"runtime catalog survives the client snapshot wire format");
		} finally {
			AgentControlCatalog.resetRuntimeCatalog();
		}
		assertEquals("gpt-5.6-sol", AgentControlCatalog.defaultModel("codex"),
				"disconnect reset restores the safe fallback catalog");
		return 7;
	}

	private static int verifyActionSafety() {
		AgentControlAgent idle = agent(AGENT_UUID, "Idle");
		AgentControlAgent paused = agent(
				"87654321-4321-8765-cba9-987654321abc", "Paused", "PAUSED", "Build shelter"
		);
		AgentControlAgent acting = agent(
				"aaaaaaaa-4321-8765-cba9-987654321abc", "Acting", "ACTING", "Gather food"
		);
		AgentControlAgent dead = agent(
				"bbbbbbbb-4321-8765-cba9-987654321abc", "Dead", "DEAD", ""
		);
		AgentControlAgent disconnected = agent(
				"cccccccc-4321-8765-cba9-987654321abc", "Disconnected", "DISCONNECTED", "Gather food"
		);
		assertTrue(AgentControlActions.supports(idle, "start"), "idle agent can start");
		assertTrue(!AgentControlActions.supports(acting, "start"), "acting agent cannot start another current goal");
		assertTrue(AgentControlActions.supports(acting, "steer"), "active goal can be steered");
		assertTrue(AgentControlActions.supports(paused, "resume"), "paused agent can resume");
		assertTrue(AgentControlActions.supports(disconnected, "resume"), "disconnected agent can resume after coordinator recovery");
		assertTrue(!AgentControlActions.supports(dead, "respawn"), "dead agent has no operator respawn action");
		assertTrue(!AgentControlActions.supports(idle, "respawn"), "living agent has no respawn action");
		assertTrue(!AgentControlActions.everySupports(List.of(paused, acting), "resume"),
				"batch action is disabled when any selected agent is incompatible");
		assertEquals("Starting up...", AgentControlPresentation.stateLabel("STARTING"),
				"technical starting state is presented as plain language");
		assertEquals("Working", AgentControlPresentation.stateLabel("ACTING"),
				"technical acting state is presented as plain language");
		assertEquals("Dead - awaiting model", AgentControlPresentation.stateLabel("DEAD"),
				"dead state does not advertise an operator respawn affordance");
		assertEquals("GPT 5.6 Sol | High", AgentControlPresentation.profileLabel(idle),
				"agent profile copy is human readable");
		assertEquals("Fast mode", AgentControlPresentation.speedLabel("fast"),
				"fast service tier has a human-readable label");
		assertEquals("Normal", AgentControlPresentation.speedLabel("priority"),
				"provider-native priority tier is presented as the normal player speed");
		return 12;
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
		return agent(id, name, "IDLE", "");
	}

	private static AgentControlAgent agent(String id, String name, String state, String currentGoal) {
		return new AgentControlAgent(
				id,
				id.substring(0, 8),
				name,
				name,
				"codex",
				"gpt-5.6-sol",
				"high",
				"SolCyan_" + id.substring(0, 8).toUpperCase(java.util.Locale.ROOT),
				0,
				state,
				currentGoal,
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
