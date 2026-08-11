package dev.agaminggod.arenaagents.scenario.result;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class MatchResultWriter {
	private final Path directory;

	public MatchResultWriter(Path directory) {
		this.directory = Objects.requireNonNull(directory, "directory must not be null").toAbsolutePath().normalize();
	}

	public synchronized Path write(MatchResultV1 result) throws IOException {
		Objects.requireNonNull(result, "result must not be null");
		Path artifact = ensureArtifact(result);
		String canonical = result.canonicalJson();
		writeJournalIdempotently(result.matchId(), result.canonicalSha256(), canonical);
		return artifact;
	}

	/**
	 * Completes the first durable stage. A later {@link #write(MatchResultV1)} call
	 * idempotently repairs a journal missing after a crash at this boundary.
	 */
	public synchronized Path ensureArtifact(MatchResultV1 result) throws IOException {
		Objects.requireNonNull(result, "result must not be null");
		Files.createDirectories(directory);
		String canonical = result.canonicalJson();
		Path artifact = directory.resolve("match-" + result.matchId() + ".json");
		if (Files.exists(artifact)) {
			String existing = Files.readString(artifact, StandardCharsets.UTF_8);
			if (!existing.equals(canonical)) {
				throw new IOException("MATCH_RESULT_CONFLICT: existing artifact differs for " + result.matchId());
			}
		} else {
			writeAtomic(artifact, canonical);
		}
		return artifact;
	}

	public CompletableFuture<Path> writeAsync(MatchResultV1 result, Executor executor) {
		Objects.requireNonNull(executor, "executor must not be null");
		return CompletableFuture.supplyAsync(() -> {
			try {
				return write(result);
			} catch (IOException exception) {
				throw new java.io.UncheckedIOException(exception);
			}
		}, executor);
	}

	private void writeJournalIdempotently(String matchId, String hash, String canonical) throws IOException {
		Path journal = directory.resolve("match-results.jsonl");
		List<String> lines = Files.exists(journal)
				? new ArrayList<>(Files.readAllLines(journal, StandardCharsets.UTF_8))
				: new ArrayList<>();
		for (String line : lines) {
			if (line.isBlank()) continue;
			var object = JsonParser.parseString(line).getAsJsonObject();
			if (!object.has("matchId") || !object.get("matchId").getAsString().equals(matchId)) continue;
			String existingHash = object.has("canonicalSha256")
					? object.get("canonicalSha256").getAsString() : "";
			if (!existingHash.equals(hash) || !line.equals(canonical)) {
				throw new IOException("MATCH_RESULT_CONFLICT: journal differs for " + matchId);
			}
			return;
		}
		lines.add(canonical);
		writeAtomic(journal, String.join("\n", lines) + "\n");
	}

	private static void writeAtomic(Path target, String value) throws IOException {
		Path parent = target.getParent();
		Path temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
		boolean moved = false;
		try {
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			try (FileChannel channel = FileChannel.open(
					temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
			)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) channel.write(buffer);
				channel.force(true);
			}
			try {
				Files.move(temporary, target,
						StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				moved = true;
			} catch (AtomicMoveNotSupportedException exception) {
				throw new IOException("ATOMIC_RESULT_WRITE_UNAVAILABLE: " + target, exception);
			}
		} finally {
			if (!moved) Files.deleteIfExists(temporary);
		}
	}
}
