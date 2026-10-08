package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionObservation;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class DurableActionJournalVerification {
	private DurableActionJournalVerification() {
	}

	public static int verify() {
		Path directory;
		try {
			directory = Files.createTempDirectory("arenaagents-action-journal-");
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
		Path path = directory.resolve("journal.json");
		AgentId agentId = AgentId.random();
		UUID goalId = UUID.randomUUID();
		ServerActionRequest request = request(agentId, 8L, "action-accepted", "step-accepted", 1L);
		verifyTickGroupCommit(directory, agentId, goalId);
		verifyCrashBoundaries(directory);

		DurableActionJournal accepted = DurableActionJournal.open(path);
		accepted.accept(request, goalId);
		accepted.close();
		DurableActionJournal acceptedReload = DurableActionJournal.open(path);
		if (persistentChannel(acceptedReload) != null) {
			throw new AssertionError("Loading an existing journal must not acquire an append channel before its owner starts");
		}
		assertEntry(acceptedReload, DurableActionJournal.Phase.ACCEPTED, request, null);
		expectFailure(() -> acceptedReload.accept(request, goalId), "ACTION_REPLAY");

		ServerActionResult uncertain = recoveryResult(request);
		acceptedReload.terminal(uncertain);
		FileChannel appendChannel = persistentChannel(acceptedReload);
		if (appendChannel == null || !appendChannel.isOpen()) {
			throw new AssertionError("First mutation must acquire a persistent append channel");
		}
		acceptedReload.close();
		if (appendChannel.isOpen()) throw new AssertionError("Closing the journal must release its append channel");
		DurableActionJournal terminalReload = DurableActionJournal.open(path);
		assertEntry(terminalReload, DurableActionJournal.Phase.TERMINAL, request, uncertain);
		UUID replacementGoalId = UUID.randomUUID();
		terminalReload.retainGoal(agentId, replacementGoalId);
		assertEntry(terminalReload, DurableActionJournal.Phase.TERMINAL, request, uncertain);
		terminalReload.terminal(uncertain);
		if (!terminalReload.acknowledge(agentId, 8L, request.actionId())) throw new AssertionError("Terminal result was not acknowledged");
		terminalReload.close();

		DurableActionJournal acknowledgedReload = DurableActionJournal.open(path);
		assertEntry(acknowledgedReload, DurableActionJournal.Phase.ACKNOWLEDGED, request, uncertain);
		if (!acknowledgedReload.acknowledge(agentId, 8L, request.actionId())) throw new AssertionError("Duplicate acknowledgement was not idempotent");
		acknowledgedReload.retainGoal(agentId, replacementGoalId);
		if (!acknowledgedReload.snapshot().isEmpty()) throw new AssertionError("Superseded acknowledged entry was not reclaimed");
		acknowledgedReload.close();

		Path capacityPath = directory.resolve("capacity.json");
		DurableActionJournal capacity = DurableActionJournal.open(capacityPath, 2);
		ServerActionRequest first = request(agentId, 9L, "capacity-1", "capacity-step-1", 2L);
		ServerActionRequest second = request(agentId, 9L, "capacity-2", "capacity-step-2", 3L);
		ServerActionRequest third = request(agentId, 9L, "capacity-3", "capacity-step-3", 4L);
		capacity.accept(first, goalId);
		capacity.accept(second, goalId);
		expectFailure(() -> capacity.accept(third, goalId), "ACTION_JOURNAL_FULL");
		ServerActionResult firstResult = result(first, "DONE");
		capacity.terminal(firstResult);
		capacity.acknowledge(agentId, 9L, first.actionId());
		capacity.accept(third, goalId);
		capacity.close();
		DurableActionJournal capacityReload = DurableActionJournal.open(capacityPath, 2);
		if (capacityReload.snapshot().size() != 2
				|| capacityReload.snapshot().stream().anyMatch(entry -> entry.request().actionId().equals(first.actionId()))) {
			throw new AssertionError("Acknowledged entry was not pressure-evicted before protected entries");
		}
		capacity.close();
		capacityReload.close();

		Path batchPath = directory.resolve("batch.journal");
		ServerActionRequest batchFirst = request(agentId, 10L, "batch-1", "batch-step-1", 5L);
		ServerActionRequest batchSecond = request(agentId, 10L, "batch-2", "batch-step-2", 6L);
		DurableActionJournal batch = DurableActionJournal.open(batchPath, 4);
		batch.accept(batchFirst, goalId);
		batch.accept(batchSecond, goalId);
		batch.close();
		DurableActionJournal batchReload = DurableActionJournal.open(batchPath, 4);
		int eventsBeforeRecovery = batchReload.persistedEventCountForVerification();
		batchReload.terminalizeAccepted(DurableActionJournalVerification::recoveryResult);
		if (batchReload.persistedEventCountForVerification() != eventsBeforeRecovery + 1) {
			throw new AssertionError("Accepted-only startup recovery was not persisted as one batched event");
		}
		DurableActionJournal recoveredBatch = DurableActionJournal.open(batchPath, 4);
		if (recoveredBatch.snapshot().size() != 2
				|| recoveredBatch.snapshot().stream().anyMatch(entry -> entry.phase() != DurableActionJournal.Phase.TERMINAL)) {
			throw new AssertionError("Batched uncertain outcomes did not survive reload");
		}
		if (batchReload.performanceSnapshotForVerification().appendCount() != 1L) {
			throw new AssertionError("Journal append instrumentation did not count each durable mutation");
		}
		batchReload.close();
		recoveredBatch.close();

		Path compactPath = directory.resolve("compact.journal");
		DurableActionJournal compact = DurableActionJournal.open(compactPath, 4, 4);
		ServerActionRequest compacted = request(agentId, 11L, "compact-1", "compact-step-1", 7L);
		compact.accept(compacted, goalId);
		compact.terminal(result(compacted, "DONE"));
		compact.acknowledge(agentId, 11L, compacted.actionId());
		compact.retainGoal(agentId, replacementGoalId);
		ServerActionRequest afterCompaction = request(agentId, 12L, "compact-2", "compact-step-2", 8L);
		compact.accept(afterCompaction, replacementGoalId);
		if (compact.persistedEventCountForVerification() != 1) {
			throw new AssertionError("Journal did not compact bounded history before the next append");
		}
		if (compact.performanceSnapshotForVerification().compactionCount() != 1L) {
			throw new AssertionError("Journal compaction instrumentation did not record the bounded rewrite");
		}
		compact.close();
		long validSize;
		try {
			validSize = Files.size(compactPath);
			Files.write(compactPath, new byte[] {0x01, 0x02, 0x03}, StandardOpenOption.APPEND);
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
		DurableActionJournal repaired = DurableActionJournal.open(compactPath, 4, 4);
		try {
			if (Files.size(compactPath) != validSize) throw new AssertionError("Incomplete crash tail was not truncated");
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
		assertEntry(repaired, DurableActionJournal.Phase.ACCEPTED, afterCompaction, null);
		repaired.terminal(result(afterCompaction, "DONE"));
		repaired.close();
		DurableActionJournal repairedReload = DurableActionJournal.open(compactPath, 4);
		assertEntry(repairedReload, DurableActionJournal.Phase.TERMINAL, afterCompaction, result(afterCompaction, "DONE"));
		repairedReload.close();

		Path detachedPath = directory.resolve("detached.json");
		DurableActionJournal detached = DurableActionJournal.open(detachedPath);
		ServerActionRequest detachedRequest = request(agentId, 13L, "detached-reply", "detached-step", 9L);
		ServerActionResult detachedResult = result(detachedRequest, "DONE");
		if (detached.terminalIfAccepted(detachedResult)) {
			throw new AssertionError("A detached reply must not require a journal entry");
		}
		if (!detached.snapshot().isEmpty()) {
			throw new AssertionError("Skipping journal terminalization must not invent an entry");
		}
		detached.accept(detachedRequest, goalId);
		if (!detached.terminalIfAccepted(detachedResult)) {
			throw new AssertionError("Accepted actions must still terminalize");
		}
		assertEntry(detached, DurableActionJournal.Phase.TERMINAL, detachedRequest, detachedResult);
		detached.close();

		verifyObservationPersistence(directory, agentId, goalId);
		verifyFailedAppendPreservesPhase(directory, agentId, goalId);
		try (var files = Files.list(directory)) {
			if (files.anyMatch(file -> file.getFileName().toString().contains(".tmp-"))) {
				throw new AssertionError("Journal left a temporary file after atomic replacement");
			}
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
		return 57;
	}

	private static void verifyCrashBoundaries(Path directory) {
		AgentId crashAgent = AgentId.parse("00000000-0000-4000-8000-000000000991");
		UUID crashGoal = UUID.fromString("00000000-0000-4000-8000-000000000992");
		Path acceptPath = directory.resolve("crash-before-force.journal");
		Path acceptMarker = directory.resolve("crash-before-force.marker");
		ServerActionRequest acceptedBeforeCrash = request(crashAgent, 31L, "crash-accept", "crash-accept-step", 1L);
		ServerActionRequest seed = request(crashAgent, 30L, "crash-seed", "crash-seed-step", 2L);
		try (DurableActionJournal journal = DurableActionJournal.open(acceptPath)) {
			journal.accept(seed, crashGoal);
		}
		int acceptExit = runCrashWorker("before-force", acceptPath, acceptMarker);
		if (acceptExit != DurableActionJournalCrashWorker.BEFORE_FORCE_EXIT) {
			throw new AssertionError("crash worker did not halt between frame write and force: " + acceptExit
					+ "; " + readCrashWorkerOutput(acceptPath));
		}
		if (Files.exists(acceptMarker)) throw new AssertionError("an accepted action executed before its journal force");
		try (DurableActionJournal recovered = DurableActionJournal.open(acceptPath)) {
			DurableActionJournal.Entry maybeAccepted = recovered.snapshot().stream()
					.filter(entry -> entry.request().actionId().equals(acceptedBeforeCrash.actionId())).findFirst().orElse(null);
			if (maybeAccepted != null && maybeAccepted.phase() != DurableActionJournal.Phase.ACCEPTED) {
				throw new AssertionError("pre-force crash replay produced a terminal result for an unexecuted action");
			}
		}

		Path terminalPath = directory.resolve("crash-after-force.journal");
		Path sendMarker = directory.resolve("crash-after-force.marker");
		ServerActionRequest terminalRequest = request(crashAgent, 32L, "crash-terminal", "crash-terminal-step", 3L);
		try (DurableActionJournal journal = DurableActionJournal.open(terminalPath)) {
			journal.accept(terminalRequest, crashGoal);
		}
		int terminalExit = runCrashWorker("after-force", terminalPath, sendMarker);
		if (terminalExit != DurableActionJournalCrashWorker.AFTER_FORCE_EXIT) {
			throw new AssertionError("crash worker did not halt after force and before send: " + terminalExit
					+ "; " + readCrashWorkerOutput(terminalPath));
		}
		if (Files.exists(sendMarker)) throw new AssertionError("result was sent before the post-force callback");
		try (DurableActionJournal replayed = DurableActionJournal.open(terminalPath)) {
			assertEntry(replayed, DurableActionJournal.Phase.TERMINAL, terminalRequest, result(terminalRequest, "DONE"));
		}
	}

	private static String readCrashWorkerOutput(Path journal) {
		try {
			return Files.readString(journal.resolveSibling(journal.getFileName() + ".worker.log"));
		} catch (IOException exception) {
			return "worker output unavailable: " + exception.getMessage();
		}
	}

	private static int runCrashWorker(String mode, Path journal, Path marker) {
		Path argumentFile = journal.resolveSibling(journal.getFileName() + ".args");
		Path outputFile = journal.resolveSibling(journal.getFileName() + ".worker.log");
		String runtimeClassPath = System.getProperty("java.class.path").replace("\r", "").replace("\n", "");
		String gsonJar = java.util.Arrays.stream(runtimeClassPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
				.filter(entry -> entry.endsWith("gson-2.13.2.jar")).findFirst()
				.orElseThrow(() -> new AssertionError("could not find Gson on the verification classpath"));
		String classPath = (Path.of("build", "classes", "java", "test").toAbsolutePath() + java.io.File.pathSeparator
				+ Path.of("build", "classes", "java", "main").toAbsolutePath() + java.io.File.pathSeparator + gsonJar)
				.replace('\\', '/');
		String journalArgument = journal.toAbsolutePath().normalize().toString().replace('\\', '/');
		String markerArgument = marker.toAbsolutePath().normalize().toString().replace('\\', '/');
		String arguments = "-cp " + classPath + "\r\n"
				+ DurableActionJournalCrashWorker.class.getName() + "\r\n"
				+ mode + "\r\n" + journalArgument + "\r\n" + markerArgument + "\r\n";
		try {
			Files.writeString(argumentFile, arguments, StandardCharsets.UTF_8);
			String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
			Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", executable);
			Process process = new ProcessBuilder(javaExecutable.toString(), "@" + argumentFile.toAbsolutePath())
					.redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
			if (!process.waitFor(30L, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor();
				throw new AssertionError("journal crash worker timed out; see " + outputFile);
			}
			return process.exitValue();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("could not run journal crash worker", exception);
		} catch (IOException exception) {
			throw new AssertionError("could not run journal crash worker", exception);
		}
	}

	private static void verifyTickGroupCommit(Path directory, AgentId agentId, UUID goalId) {
		Path path = directory.resolve("tick-group.journal");
		ServerActionRequest first = request(agentId, 20L, "tick-first", "tick-step-first", 1L);
		ServerActionRequest second = request(agentId, 20L, "tick-second", "tick-step-second", 2L);
		ServerActionRequest third = request(agentId, 21L, "tick-third", "tick-step-third", 3L);
		try (DurableActionJournal journal = DurableActionJournal.open(path)) {
			journal.acceptForTick(first, goalId);
			journal.acceptForTick(second, goalId);
			if (!journal.snapshot().isEmpty()) throw new AssertionError("Staged acceptances are not visible as durable journal state");
			if (journal.performanceSnapshotForVerification().appendCount() != 0L) {
				throw new AssertionError("Staging actions must not write or force the journal");
			}
			journal.flushTickGroup();
			if (journal.performanceSnapshotForVerification().appendCount() != 1L) {
				throw new AssertionError("Two accepted actions in one tick must share one journal force");
			}
			assertContainsEntry(journal, DurableActionJournal.Phase.ACCEPTED, first, null);
			assertContainsEntry(journal, DurableActionJournal.Phase.ACCEPTED, second, null);

			ServerActionResult terminal = result(first, "DONE");
			if (!journal.terminalIfAcceptedForTick(terminal)) throw new AssertionError("A durable acceptance must stage its terminal result");
			journal.flushTickGroup();
			if (journal.performanceSnapshotForVerification().appendCount() != 2L) {
				throw new AssertionError("A terminal result group must append and force once");
			}
			assertContainsEntry(journal, DurableActionJournal.Phase.TERMINAL, first, terminal);

			journal.queueAcknowledgement(agentId, first.goalRevision(), first.actionId());
			journal.acceptForTick(third, goalId);
			journal.flushTickGroup();
			if (journal.performanceSnapshotForVerification().appendCount() != 3L) {
				throw new AssertionError("An acceptance and its tick's ACK must share one journal force");
			}
			assertContainsEntry(journal, DurableActionJournal.Phase.ACKNOWLEDGED, first, terminal);
			assertContainsEntry(journal, DurableActionJournal.Phase.ACCEPTED, third, null);
		}
	}

	private static void verifyFailedAppendPreservesPhase(Path directory, AgentId agentId, UUID goalId) {
		Path path = directory.resolve("failed-observed-append.journal");
		ServerActionRequest request = request(agentId, 16L, "failed-observed-append", "failed-observed-step", 11L);
		ServerActionResult result = withObservation(result(request, "DONE"), observation(1.0D));
		try (DurableActionJournal journal = DurableActionJournal.open(path)) {
			journal.accept(request, goalId);
		}
		try (DurableActionJournal journal = DurableActionJournal.open(path)) {
			appendIncompleteTail(path);
			expectFailure(() -> journal.terminal(result), "ACTION_JOURNAL_IO");
			assertEntry(journal, DurableActionJournal.Phase.ACCEPTED, request, null);
		}
		try (DurableActionJournal journal = DurableActionJournal.open(path)) {
			assertEntry(journal, DurableActionJournal.Phase.ACCEPTED, request, null);
			journal.terminal(result);
		}
		try (DurableActionJournal journal = DurableActionJournal.open(path)) {
			appendIncompleteTail(path);
			expectFailure(() -> journal.acknowledge(agentId, request.goalRevision(), request.actionId()), "ACTION_JOURNAL_IO");
			assertEntry(journal, DurableActionJournal.Phase.TERMINAL, request, result);
		}
		try (DurableActionJournal journal = DurableActionJournal.open(path)) {
			assertEntry(journal, DurableActionJournal.Phase.TERMINAL, request, result);
		}
	}

	private static void appendIncompleteTail(Path path) {
		try {
			Files.write(path, new byte[] {0x01}, StandardOpenOption.APPEND);
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
	}

	private static void verifyObservationPersistence(Path directory, AgentId agentId, UUID goalId) {
		ServerActionRequest request = request(agentId, 14L, "observed", "observed-step", 10L);
		ServerActionResult result = withObservation(result(request, "DONE"), observation(0.0D));
		ServerActionResult changed = withObservation(result, observation(-0.0D));
		Path path = directory.resolve("observed.journal");
		try (DurableActionJournal journal = DurableActionJournal.open(path, 4, 2)) {
			journal.accept(request, goalId);
			journal.terminal(result);
			// JSON callers and request snapshots must not mutate the stored evidence or request.
			DurableActionJournal.encodeResult(result).getAsJsonObject("actionObservation").addProperty("yaw", 99.0D);
			journal.snapshot().get(0).request().arguments().addProperty("durationMs", 99L);
			assertEntry(journal, DurableActionJournal.Phase.TERMINAL, request, result);
		}
		try (DurableActionJournal journal = DurableActionJournal.open(path, 4, 2)) {
			assertEntry(journal, DurableActionJournal.Phase.TERMINAL, request, result);
			int beforeRetry = journal.persistedEventCountForVerification();
			journal.terminal(result);
			if (beforeRetry != journal.persistedEventCountForVerification()) throw new AssertionError("Identical retry appended again");
			expectFailure(() -> journal.terminal(changed), "ACTION_RESULT_REPLAY_CONFLICT");
			journal.acknowledge(agentId, request.goalRevision(), request.actionId());
			if (journal.performanceSnapshotForVerification().compactionCount() != 1L) {
				throw new AssertionError("Observed terminal result was not compacted before acknowledgement");
			}
		}
		try (DurableActionJournal journal = DurableActionJournal.open(path, 4, 2)) {
			assertEntry(journal, DurableActionJournal.Phase.ACKNOWLEDGED, request, result);
			journal.terminal(result);
			expectFailure(() -> journal.terminal(changed), "ACTION_RESULT_REPLAY_CONFLICT");
		}

		JsonObject legacy = legacySnapshot(request, result(request, "DONE"));
		Path legacyPath = directory.resolve("legacy-observation-absent.json");
		writeJson(legacyPath, legacy);
		try (DurableActionJournal journal = DurableActionJournal.open(legacyPath)) {
			assertEntry(journal, DurableActionJournal.Phase.TERMINAL, request, result(request, "DONE"));
			journal.terminal(result(request, "DONE"));
		}
		try (DurableActionJournal journal = DurableActionJournal.open(legacyPath)) {
			assertEntry(journal, DurableActionJournal.Phase.TERMINAL, request, result(request, "DONE"));
		}

		ServerActionObservation sparse = new ServerActionObservation(null, 1L, null, null, -0.0D, 0.0D, null, null, null, null, null);
		Path sparsePath = directory.resolve("sparse-observation.json");
		ServerActionResult sparseResult = withObservation(result, sparse);
		writeJson(sparsePath, legacySnapshot(request, sparseResult));
		try (DurableActionJournal journal = DurableActionJournal.open(sparsePath)) {
			assertEntry(journal, DurableActionJournal.Phase.TERMINAL, request, sparseResult);
		}

		JsonObject malformed = legacySnapshot(request, result);
		malformed.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("result")
				.getAsJsonObject("actionObservation").remove("yaw");
		Path malformedPath = directory.resolve("malformed-observation.json");
		writeJson(malformedPath, malformed);
		expectFailure(() -> DurableActionJournal.open(malformedPath), "ACTION_JOURNAL_CORRUPT");
	}

	static ServerActionObservation observation(double yaw) {
		return new ServerActionObservation(
				42L, 1_750_000_000_001L,
				new ServerActionObservation.Position(1.25D, 64.0D, -2.5D),
				new ServerActionObservation.Position(-0.0D, 0.25D, 0.0D), yaw, -12.5D,
				new ServerActionObservation.Collision(true, false, true),
				new ServerActionObservation.RayTarget("block", new ServerActionObservation.Position(2.0D, 64.0D, -2.0D), "minecraft:stone", "up", 1.5D),
				new ServerActionObservation.Reach(1.5D, 4.5D, true),
				new ServerActionObservation.Target("block", new ServerActionObservation.Position(2.0D, 64.0D, -2.0D),
						"minecraft:stone", "minecraft:air", "minecraft:stone", "minecraft:air", true, 0.5D, 0.75D, false),
				new ServerActionObservation.Progress(1.0D, "world_mutation", true)
		);
	}

	static ServerActionResult withObservation(ServerActionResult result, ServerActionObservation observation) {
		return new ServerActionResult(result.agentId(), result.goalRevision(), result.actionId(), result.actionType(),
				result.traceId(), result.state(), result.reasonCode(), result.message(), result.elapsedMs(),
				result.observedAtEpochMs(), result.executionStarted(), result.physicalAttempted(), observation);
	}

	private static JsonObject legacySnapshot(ServerActionRequest request, ServerActionResult result) {
		JsonObject provenance = new JsonObject();
		provenance.addProperty("provider", "codex");
		provenance.addProperty("model", "gpt-5.6-sol");
		provenance.addProperty("reasoningEffort", "high");
		provenance.addProperty("serviceTier", "priority");
		provenance.addProperty("programId", "program");
		provenance.addProperty("programVersion", 1L);
		provenance.addProperty("sourceStepId", request.provenance().sourceStepId());
		provenance.addProperty("eventSequence", request.provenance().eventSequence());
		JsonObject encodedRequest = new JsonObject();
		encodedRequest.addProperty("agentId", request.agentId().toString());
		encodedRequest.addProperty("goalRevision", request.goalRevision());
		encodedRequest.addProperty("actionId", request.actionId());
		encodedRequest.addProperty("actionType", request.type().wireName());
		encodedRequest.add("arguments", request.arguments());
		encodedRequest.add("provenance", provenance);
		JsonObject entry = new JsonObject();
		entry.addProperty("phase", "TERMINAL");
		entry.add("request", encodedRequest);
		entry.add("result", DurableActionJournal.encodeResult(result));
		JsonArray entries = new JsonArray();
		entries.add(entry);
		JsonObject snapshot = new JsonObject();
		snapshot.addProperty("schemaVersion", 1);
		snapshot.add("entries", entries);
		return snapshot;
	}

	private static void writeJson(Path path, JsonObject json) {
		try {
			Files.writeString(path, json.toString());
		} catch (IOException exception) {
			throw new AssertionError(exception);
		}
	}

	private static FileChannel persistentChannel(DurableActionJournal journal) {
		try {
			var field = DurableActionJournal.class.getDeclaredField("persistentChannel");
			field.setAccessible(true);
			return (FileChannel) field.get(journal);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Could not inspect journal channel ownership", exception);
		}
	}

	private static void assertEntry(
			DurableActionJournal journal,
			DurableActionJournal.Phase phase,
			ServerActionRequest request,
			ServerActionResult result
	) {
		if (journal.snapshot().size() != 1) throw new AssertionError("Expected exactly one journal entry");
		DurableActionJournal.Entry entry = journal.snapshot().get(0);
		if (entry.phase() != phase || !entry.request().equals(request) || !java.util.Objects.equals(entry.result(), result)) {
			throw new AssertionError("Journal entry did not survive the crash-boundary reload");
		}
	}

	private static void assertContainsEntry(
			DurableActionJournal journal,
			DurableActionJournal.Phase phase,
			ServerActionRequest request,
			ServerActionResult result
	) {
		DurableActionJournal.Entry entry = journal.snapshot().stream()
				.filter(candidate -> candidate.request().actionId().equals(request.actionId())).findFirst()
				.orElseThrow(() -> new AssertionError("Journal entry was not persisted"));
		if (entry.phase() != phase || !entry.request().equals(request) || !java.util.Objects.equals(entry.result(), result)) {
			throw new AssertionError("Journal entry did not survive the tick group");
		}
	}

	private static ServerActionRequest request(AgentId agentId, long revision, String actionId, String stepId, long sequence) {
		JsonObject arguments = new JsonObject();
		arguments.addProperty("durationMs", 25L);
		return new ServerActionRequest(
				agentId, revision, actionId, ActionType.WAIT, arguments,
				new ActionProvenance("codex", "gpt-5.6-sol", "high", "priority", "program", 1L, stepId, sequence)
		);
	}

	private static ServerActionResult result(ServerActionRequest request, String reason) {
		return new ServerActionResult(
				request.agentId(), request.goalRevision(), request.actionId(), request.type(), request.traceId(),
				ServerActionState.SUCCEEDED, reason, "Done", 1L, 1L, true, true
		);
	}

	private static ServerActionResult recoveryResult(ServerActionRequest request) {
		return new ServerActionResult(
				request.agentId(), request.goalRevision(), request.actionId(), request.type(), request.traceId(),
				ServerActionState.FAILED, "RECOVERY_UNCERTAIN",
				"Server restarted after accepting the action; its physical outcome is uncertain and it will not be replayed",
				0L, 1L, true, true
		);
	}

	private static void expectFailure(Runnable operation, String code) {
		try {
			operation.run();
			throw new AssertionError("Expected " + code);
		} catch (AgentDomainException exception) {
			if (!code.equals(exception.code())) throw new AssertionError("Expected " + code + " but got " + exception.code());
		}
	}
}
