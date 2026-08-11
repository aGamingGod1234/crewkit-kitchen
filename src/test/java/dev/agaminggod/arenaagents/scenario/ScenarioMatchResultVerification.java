package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.scenario.result.MatchResultV1;
import dev.agaminggod.arenaagents.scenario.result.MatchResultWriter;
import dev.agaminggod.arenaagents.scenario.result.ScenarioResultPersistence;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicEvent;
import dev.agaminggod.arenaagents.scenario.result.ScenarioPublicFormatter;
import dev.agaminggod.arenaagents.scenario.runtime.ScenarioResetReceipt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScenarioMatchResultVerification {
	private ScenarioMatchResultVerification() {
	}

	public static int verify() throws IOException {
		ScenarioPublicFormatter formatter = new ScenarioPublicFormatter();
		ScenarioPublicEvent started = formatter.format(new ScenarioAgentEvent(
				12L,
				"agent-a",
				"Codex Sol High",
				ScenarioAgentEvent.Kind.ACTION_STARTED,
				ScenarioAgentEvent.ActionFamily.HARVEST,
				0.0D,
				ScenarioAgentEvent.PublicState.ACTING
		));
		String publicJson = started.canonicalJson();
		assertFalse(publicJson.contains("prompt"), "public event excludes prompt fields");
		assertFalse(publicJson.contains("observation"), "public event excludes observation fields");
		assertFalse(publicJson.contains("rawSummary"), "public event excludes raw summaries");
		assertFalse(publicJson.contains("arguments"), "public event excludes action arguments");
		assertFalse(publicJson.contains("\"message\""), "public event does not serialize arbitrary prose");
		assertTrue(started.message().contains("started harvesting"), "public event uses a fixed friendly template");

		ScenarioPublicEvent scored = formatter.format(new ScenarioAgentEvent(
				20L,
				"agent-b",
				"Gemini Pro High",
				ScenarioAgentEvent.Kind.SCORE_CHANGED,
				ScenarioAgentEvent.ActionFamily.SURVIVAL,
				2.5D,
				ScenarioAgentEvent.PublicState.ACTING
		));
		ScenarioResetReceipt reset = ScenarioResetReceipt.verified("blueprint-sha", "blueprint-sha", 100, 100, 100);
		ScenarioPublicEvent adversarialA = new ScenarioPublicEvent(
				20L, "agent-b", "Alpha", "score_changed", "survival", 10.0D, "acting"
		);
		ScenarioPublicEvent adversarialB = new ScenarioPublicEvent(
				20L, "agent-b", "Zulu", "score_changed", "survival", -10.0D, "acting"
		);
		MatchResultV1 first = new MatchResultV1(
				"match-001",
				"last-valley",
				"1.0.0",
				99L,
				101L,
				List.of(
						new MatchResultV1.Standing("agent-b", "Gemini Pro High", 7.0D, "completed"),
						new MatchResultV1.Standing("agent-a", "Codex Sol High", 7.0D, "completed"),
						new MatchResultV1.Standing("agent-c", "Kimi K2", 3.0D, "eliminated")
				),
				List.of(scored, adversarialB, started, adversarialA),
				reset,
				""
		);
		MatchResultV1 permuted = new MatchResultV1(
				"match-001", "last-valley", "1.0.0", 99L, 101L,
				List.of(first.standings().get(2), first.standings().get(0), first.standings().get(1)),
				List.of(adversarialA, started, adversarialB, scored), reset, ""
		);
		assertEquals(List.of("agent-a", "agent-b", "agent-c"),
				first.standings().stream().map(MatchResultV1.Standing::participantId).toList(),
				"standings rank by score then stable participant id");
		assertEquals(first.canonicalSha256(), permuted.canonicalSha256(), "permuted input has identical canonical hash");
		assertEquals(first.canonicalJson(), permuted.canonicalJson(), "permuted input has identical canonical JSON");

		Path directory = Files.createTempDirectory("arenaagents-results-");
		try {
			MatchResultWriter writer = new MatchResultWriter(directory);
			Path artifact = writer.ensureArtifact(first);
			assertFalse(Files.exists(directory.resolve("match-results.jsonl")),
					"simulated crash leaves an artifact without a journal");
			artifact = new MatchResultWriter(directory).write(first);
			writer.write(first);
			assertTrue(Files.isRegularFile(artifact), "per-match result artifact exists");
			List<String> journal = Files.readAllLines(directory.resolve("match-results.jsonl"));
			assertEquals(1, journal.size(), "idempotent retry does not duplicate journal entry");
			assertEquals(first.canonicalJson(), journal.getFirst(), "journal stores canonical result");

			AtomicInteger attempts = new AtomicInteger();
			Path durableArtifact = artifact;
			ScenarioResultPersistence persistence = new ScenarioResultPersistence(first, result -> {
				if (attempts.incrementAndGet() == 1) {
					return CompletableFuture.failedFuture(new IOException("simulated durable-write failure"));
				}
				return CompletableFuture.completedFuture(durableArtifact);
			});
			assertFalse(persistence.poll(0L).durable(), "first result write starts pending");
			assertFalse(persistence.poll(1L).durable(), "failed result write remains retryable");
			assertFalse(persistence.poll(20L).durable(), "retry backoff retains pending result state");
			assertFalse(persistence.poll(21L).durable(), "retry starts after bounded backoff");
			assertTrue(persistence.poll(22L).durable(), "successful retry marks result durable");
			assertEquals(2, persistence.poll(22L).attempts(), "result persistence records both attempts");
		} finally {
			deleteTree(directory);
		}
		return 24;
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}

	private static void assertFalse(boolean condition, String label) {
		assertTrue(!condition, label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
