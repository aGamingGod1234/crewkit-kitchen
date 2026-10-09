package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.protocol.ProtocolCodec;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionObservation;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.CRC32C;

/** Synchronous write-ahead journal for coordinator-issued physical actions. */
final class DurableActionJournal implements AutoCloseable {
	static final int MAX_ENTRIES = 4_096;
	private static final int SCHEMA_VERSION = 1;
	private static final Gson OBSERVATION_CODEC = new GsonBuilder().serializeNulls().create();
	private static final byte[] LOG_HEADER = "AAAJNL2\n".getBytes(StandardCharsets.US_ASCII);
	private static final int FRAME_HEADER_BYTES = Integer.BYTES * 2;
	private static final int MAX_FRAME_BYTES = 64 * 1024 * 1024;
	private final Path path;
	private final int maximumEntries;
	private final int compactionEventLimit;
	private final ForceHook forceHook;
	private LinkedHashMap<ActionKey, Entry> entries;
	private LinkedHashMap<ActionKey, Entry> stagedEntries;
	private final List<Mutation> pendingTickMutations = new ArrayList<>();
	// Server-thread ACKs survive failed commits, bounded by the retained journal identities.
	private final LinkedHashSet<ActionKey> pendingAcknowledgements = new LinkedHashSet<>();
	private int persistedEventCount;
	private long persistedBytes;
	// Hash committed bytes incrementally; only channel recovery/reopening rereads the prefix.
	private MessageDigest committedPrefix;
	// Only this instance's failed frame may be discarded during a live retry.
	private byte[] failedAppendFrame;
	private FileChannel persistentChannel;
	private long appendCount;
	private long compactionCount;
	private long appendNanos;
	private long slowestAppendNanos;
	private long compactionNanos;
	private long slowestCompactionNanos;
	private boolean closed;

	private DurableActionJournal(Path path, int maximumEntries, int compactionEventLimit, Loaded loaded, ForceHook forceHook) {
		this.path = path;
		this.maximumEntries = maximumEntries;
		this.compactionEventLimit = compactionEventLimit;
		this.forceHook = forceHook;
		this.entries = loaded.entries();
		this.persistedEventCount = loaded.eventCount();
		this.persistedBytes = loaded.persistedBytes();
		this.committedPrefix = loaded.committedPrefix();
	}

	static DurableActionJournal open(Path path) {
		return open(path, MAX_ENTRIES);
	}

	static DurableActionJournal open(Path path, int maximumEntries) {
		return open(path, maximumEntries, Math.max(128, maximumEntries * 4));
	}

	static DurableActionJournal open(Path path, int maximumEntries, int compactionEventLimit) {
		return open(path, maximumEntries, compactionEventLimit, ForceHook.NONE);
	}

	static DurableActionJournal open(Path path, int maximumEntries, int compactionEventLimit, ForceHook forceHook) {
		Objects.requireNonNull(path, "path must not be null");
		Objects.requireNonNull(forceHook, "forceHook must not be null");
		if (maximumEntries <= 0) throw new IllegalArgumentException("maximumEntries must be positive");
		if (compactionEventLimit <= 0) throw new IllegalArgumentException("compactionEventLimit must be positive");
		Path normalized = path.toAbsolutePath().normalize();
		Loaded loaded = read(normalized, maximumEntries);
		if (loaded.legacy()) {
			writeSnapshot(normalized, loaded.entries());
			loaded = read(normalized, maximumEntries);
		} else if (loaded.persistedBytes() >= 0L && Files.exists(normalized)) {
			truncateTail(normalized, loaded.persistedBytes());
		}
		return new DurableActionJournal(normalized, maximumEntries, compactionEventLimit, loaded, forceHook);
	}

	static DurableActionJournal inMemory() {
		return new DurableActionJournal(null, MAX_ENTRIES, Integer.MAX_VALUE,
				new Loaded(new LinkedHashMap<>(), 0, 0L, false, newDigest()), ForceHook.NONE);
	}

	synchronized void accept(ServerActionRequest request, UUID logicalGoalId) {
		Mutation mutation = acceptanceMutation(request, logicalGoalId, entries);
		persist(mutation);
	}

	/** Stages server-thread acceptance for the next journal group; actions remain gated until that group is forced. */
	synchronized void acceptForTick(ServerActionRequest request, UUID logicalGoalId) {
		LinkedHashMap<ActionKey, Entry> current = tickEntries();
		Mutation mutation = acceptanceMutation(request, logicalGoalId, current);
		stageTickMutation(mutation);
	}

	private Mutation acceptanceMutation(
			ServerActionRequest request,
			UUID logicalGoalId,
			LinkedHashMap<ActionKey, Entry> current
	) {
		Objects.requireNonNull(request, "request must not be null");
		ActionKey key = ActionKey.from(request);
		Entry prior = current.get(key);
		if (prior != null) {
			if (!prior.request().equals(request) || !Objects.equals(prior.logicalGoalId(), logicalGoalId)) {
				throw new AgentDomainException("ACTION_PROVENANCE_MISMATCH", "Action ID is already bound to a different durable request");
			}
			throw new AgentDomainException("ACTION_REPLAY", "Action ID has already been durably accepted");
		}
		for (Entry entry : current.values()) {
			if (entry.request().agentId().equals(request.agentId())
					&& Objects.equals(entry.logicalGoalId(), logicalGoalId)
					&& entry.request().provenance().equals(request.provenance())) {
				throw new AgentDomainException("ACTION_REPLAY", "Program step is already bound to action ID " + entry.request().actionId());
			}
		}
		List<ActionKey> removed = admissionRemovals(current);
		Entry accepted = new Entry(request, logicalGoalId, Phase.ACCEPTED, null);
		return new Mutation(removed, List.of(accepted), List.of(), List.of());
	}

	synchronized boolean terminalIfAccepted(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		if (entries.get(ActionKey.from(result)) == null) return false;
		terminal(result);
		return true;
	}

	/** Stages a terminal result to be forced at the next tick boundary before it can be published. */
	synchronized boolean terminalIfAcceptedForTick(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		ActionKey key = ActionKey.from(result);
		Entry prior = tickEntries().get(key);
		if (prior == null) return false;
		verifyResult(prior.request(), result);
		if (prior.phase() != Phase.ACCEPTED) {
			if (prior.result().equals(result)) return true;
			throw new AgentDomainException("ACTION_RESULT_REPLAY_CONFLICT", "Action already has a different durable terminal result");
		}
		stageTickMutation(new Mutation(List.of(), List.of(), List.of(result), List.of()));
		return true;
	}

	synchronized void terminal(ServerActionResult result) {
		Objects.requireNonNull(result, "result must not be null");
		ActionKey key = ActionKey.from(result);
		Entry prior = entries.get(key);
		if (prior == null) {
			throw new AgentDomainException("ACTION_JOURNAL_MISSING", "Terminal action has no durable acceptance record");
		}
		verifyResult(prior.request(), result);
		if (prior.phase() != Phase.ACCEPTED) {
			if (prior.result().equals(result)) return;
			throw new AgentDomainException("ACTION_RESULT_REPLAY_CONFLICT", "Action already has a different durable terminal result");
		}
		persist(new Mutation(List.of(), List.of(), List.of(result), List.of()));
	}

	synchronized boolean acknowledge(AgentId agentId, long goalRevision, String actionId) {
		ActionKey key = new ActionKey(agentId, goalRevision, actionId);
		Entry prior = entries.get(key);
		if (prior == null) return false;
		if (prior.phase() == Phase.ACCEPTED) return false;
		if (prior.phase() == Phase.ACKNOWLEDGED) return true;
		persist(new Mutation(List.of(), List.of(), List.of(), List.of(key)));
		return true;
	}

	/** Stages an authenticated terminal ACK; no durable or replay state changes yet. */
	synchronized boolean queueAcknowledgement(AgentId agentId, long goalRevision, String actionId) {
		ActionKey key = new ActionKey(agentId, goalRevision, actionId);
		Entry entry = entries.get(key);
		if (entry == null || entry.phase() == Phase.ACCEPTED) return false;
		pendingAcknowledgements.add(key);
		return true;
	}

	/** One durable group per tick. A failed append leaves every identity available for retry. */
	synchronized List<ActionKey> flushAcknowledgements() {
		return flushTickGroup();
	}

	/** Requests accepted this tick whose acceptance is not durable yet and which have not finished. */
	synchronized List<ServerActionRequest> stagedAcceptances() {
		if (stagedEntries == null) return List.of();
		List<ServerActionRequest> staged = new ArrayList<>();
		for (Mutation mutation : pendingTickMutations) {
			for (Entry entry : mutation.put()) {
				ActionKey key = ActionKey.from(entry.request());
				if (entry.phase() != Phase.ACCEPTED || entries.containsKey(key)) continue;
				Entry current = stagedEntries.get(key);
				if (current != null && current.phase() == Phase.ACCEPTED) staged.add(current.request());
			}
		}
		return staged;
	}

	/** Persists staged acceptances, terminal results and ACKs in one append and one force. */
	synchronized List<ActionKey> flushTickGroup() {
		if (pendingTickMutations.isEmpty() && pendingAcknowledgements.isEmpty()) return List.of();
		List<ActionKey> completed = List.copyOf(pendingAcknowledgements);
		List<ActionKey> acknowledged = completed.stream().filter(key -> {
			Entry entry = tickEntries().get(key);
			return entry != null && entry.phase() == Phase.TERMINAL;
		}).toList();
		Mutation group = tickGroupMutation(acknowledged);
		if (!group.removed().isEmpty() || !group.put().isEmpty() || !group.terminal().isEmpty() || !group.acknowledged().isEmpty()) {
			persistTickGroup(group);
		}
		pendingTickMutations.clear();
		pendingAcknowledgements.clear();
		stagedEntries = null;
		return completed;
	}

	/** Converts crash-stranded acceptances with one durable append and one fsync. */
	synchronized void terminalizeAccepted(Function<ServerActionRequest, ServerActionResult> resultFactory) {
		Objects.requireNonNull(resultFactory, "resultFactory must not be null");
		List<ServerActionResult> terminalized = new ArrayList<>();
		for (Entry entry : entries.values()) {
			if (entry.phase() != Phase.ACCEPTED) continue;
			ServerActionResult result = Objects.requireNonNull(resultFactory.apply(entry.request()), "recovery result must not be null");
			verifyResult(entry.request(), result);
			terminalized.add(result);
		}
		if (!terminalized.isEmpty()) persist(new Mutation(List.of(), List.of(), terminalized, List.of()));
	}

	synchronized boolean rollbackAccepted(ServerActionRequest request) {
		ActionKey key = ActionKey.from(request);
		Entry staged = stagedEntries == null ? null : stagedEntries.get(key);
		if (staged != null && staged.phase() == Phase.ACCEPTED && staged.request().equals(request)) {
			boolean removed = pendingTickMutations.removeIf(mutation -> mutation.put().stream()
					.anyMatch(entry -> ActionKey.from(entry.request()).equals(key)));
			if (removed) {
				rebuildStagedEntries();
				return true;
			}
		}
		Entry prior = entries.get(key);
		if (prior == null || prior.phase() != Phase.ACCEPTED || !prior.request().equals(request)) return false;
		persist(new Mutation(List.of(key), List.of(), List.of(), List.of()));
		return true;
	}

	synchronized void retainGoal(AgentId agentId, UUID logicalGoalId) {
		List<ActionKey> removed = retainedGoalRemovals(entries, agentId, logicalGoalId);
		if (!removed.isEmpty()) persist(new Mutation(removed, List.of(), List.of(), List.of()));
	}

	synchronized void retainGoalForTick(AgentId agentId, UUID logicalGoalId) {
		List<ActionKey> removed = retainedGoalRemovals(tickEntries(), agentId, logicalGoalId);
		if (!removed.isEmpty()) stageTickMutation(new Mutation(removed, List.of(), List.of(), List.of()));
	}

	private List<ActionKey> retainedGoalRemovals(Map<ActionKey, Entry> current, AgentId agentId, UUID logicalGoalId) {
		List<ActionKey> removed = new ArrayList<>();
		for (Map.Entry<ActionKey, Entry> entry : current.entrySet()) {
			if (entry.getKey().agentId().equals(agentId)
					&& entry.getValue().phase() == Phase.ACKNOWLEDGED
					&& !Objects.equals(entry.getValue().logicalGoalId(), logicalGoalId)) {
				removed.add(entry.getKey());
			}
		}
		return List.copyOf(removed);
	}

	synchronized void remove(AgentId agentId) {
		List<ActionKey> removed = keysForAgent(entries, agentId);
		if (!removed.isEmpty()) persist(new Mutation(removed, List.of(), List.of(), List.of()));
		pendingAcknowledgements.removeIf(key -> key.agentId().equals(agentId));
	}

	synchronized void removeForTick(AgentId agentId) {
		List<ActionKey> removed = keysForAgent(tickEntries(), agentId);
		if (!removed.isEmpty()) stageTickMutation(new Mutation(removed, List.of(), List.of(), List.of()));
		pendingAcknowledgements.removeIf(key -> key.agentId().equals(agentId));
	}

	private List<ActionKey> keysForAgent(Map<ActionKey, Entry> current, AgentId agentId) {
		List<ActionKey> removed = new ArrayList<>();
		for (ActionKey key : current.keySet()) {
			if (key.agentId().equals(agentId)) removed.add(key);
		}
		return List.copyOf(removed);
	}

	synchronized List<Entry> snapshot() {
		return List.copyOf(entries.values());
	}

	synchronized int persistedEventCountForVerification() {
		return persistedEventCount;
	}

	synchronized PerformanceSnapshot performanceSnapshotForVerification() {
		return new PerformanceSnapshot(
				persistedEventCount, persistedBytes, appendCount, compactionCount,
				appendNanos, slowestAppendNanos, compactionNanos, slowestCompactionNanos
		);
	}

	private List<ActionKey> admissionRemovals() {
		return admissionRemovals(entries);
	}

	private List<ActionKey> admissionRemovals(Map<ActionKey, Entry> current) {
		if (current.size() < maximumEntries) return List.of();
		List<ActionKey> removed = new ArrayList<>(1);
		int retainedSize = current.size();
		for (Map.Entry<ActionKey, Entry> candidate : current.entrySet()) {
			if (retainedSize < maximumEntries) break;
			if (candidate.getValue().phase() != Phase.ACKNOWLEDGED) continue;
			removed.add(candidate.getKey());
			retainedSize--;
		}
		if (retainedSize >= maximumEntries) {
			throw new AgentDomainException("ACTION_JOURNAL_FULL", "Durable action journal is full of unacknowledged actions");
		}
		return List.copyOf(removed);
	}

	private LinkedHashMap<ActionKey, Entry> tickEntries() {
		return stagedEntries == null ? entries : stagedEntries;
	}

	private void stageTickMutation(Mutation mutation) {
		if (stagedEntries == null) stagedEntries = new LinkedHashMap<>(entries);
		applyMutation(stagedEntries, mutation);
		pendingTickMutations.add(mutation);
	}

	private void rebuildStagedEntries() {
		stagedEntries = pendingTickMutations.isEmpty() ? null : new LinkedHashMap<>(entries);
		if (stagedEntries == null) return;
		for (Mutation mutation : pendingTickMutations) applyMutation(stagedEntries, mutation);
	}

	private Mutation tickGroupMutation(List<ActionKey> queuedAcknowledgements) {
		List<ActionKey> removed = new ArrayList<>();
		List<Entry> put = new ArrayList<>();
		List<ServerActionResult> terminal = new ArrayList<>();
		LinkedHashSet<ActionKey> acknowledged = new LinkedHashSet<>(queuedAcknowledgements);
		for (Map.Entry<ActionKey, Entry> durable : entries.entrySet()) {
			Entry current = tickEntries().get(durable.getKey());
			if (current == null) {
				removed.add(durable.getKey());
				continue;
			}
			if (durable.getValue().phase() == current.phase() && Objects.equals(durable.getValue().result(), current.result())) continue;
			if (durable.getValue().phase() == Phase.ACCEPTED && current.phase() != Phase.ACCEPTED) {
				terminal.add(current.result());
				if (current.phase() == Phase.ACKNOWLEDGED) acknowledged.add(durable.getKey());
			} else if (durable.getValue().phase() == Phase.TERMINAL && current.phase() == Phase.ACKNOWLEDGED) {
				acknowledged.add(durable.getKey());
			} else {
				put.add(current);
			}
		}
		for (Map.Entry<ActionKey, Entry> current : tickEntries().entrySet()) {
			if (entries.containsKey(current.getKey())) continue;
			Entry stagedAcceptance = stagedAcceptance(current.getKey());
			if (stagedAcceptance != null && current.getValue().phase() != Phase.ACCEPTED) {
				put.add(stagedAcceptance);
				terminal.add(current.getValue().result());
				if (current.getValue().phase() == Phase.ACKNOWLEDGED) acknowledged.add(current.getKey());
			} else {
				put.add(current.getValue());
			}
		}
		return new Mutation(List.copyOf(removed), List.copyOf(put), List.copyOf(terminal), List.copyOf(acknowledged));
	}

	private Entry stagedAcceptance(ActionKey key) {
		for (Mutation mutation : pendingTickMutations) {
			for (Entry entry : mutation.put()) {
				if (ActionKey.from(entry.request()).equals(key) && entry.phase() == Phase.ACCEPTED) return entry;
			}
		}
		return null;
	}

	private void persistTickGroup(Mutation mutation) {
		if (closed) throw new AgentDomainException("ACTION_JOURNAL_CLOSED", "Durable action journal is closed");
		if (path == null || persistedEventCount < compactionEventLimit) {
			persist(mutation);
			return;
		}
		long started = System.nanoTime();
		LinkedHashMap<ActionKey, Entry> compacted = new LinkedHashMap<>(entries);
		applyMutation(compacted, mutation);
		closePersistentChannel();
		committedPrefix = writeSnapshot(path, compacted);
		entries = compacted;
		persistedEventCount = entries.size();
		persistedBytes = fileSize(path);
		openPersistentChannel();
		long elapsed = Math.max(0L, System.nanoTime() - started);
		compactionCount++;
		compactionNanos += elapsed;
		slowestCompactionNanos = Math.max(slowestCompactionNanos, elapsed);
	}

	private void persist(Mutation mutation) {
		if (closed) throw new AgentDomainException("ACTION_JOURNAL_CLOSED", "Durable action journal is closed");
		if (path != null) {
			long started = System.nanoTime();
			if (failedAppendFrame != null) openPersistentChannel();
			if (persistedEventCount >= compactionEventLimit) compact();
			byte[] frame = encodeFrame(mutation);
			appendFrame(frame);
			long elapsed = Math.max(0L, System.nanoTime() - started);
			appendCount++;
			appendNanos += elapsed;
			slowestAppendNanos = Math.max(slowestAppendNanos, elapsed);
			persistedEventCount++;
		}
		applyMutation(entries, mutation);
	}

	private void compact() {
		long started = System.nanoTime();
		closePersistentChannel();
		committedPrefix = writeSnapshot(path, entries);
		persistedEventCount = entries.size();
		persistedBytes = fileSize(path);
		openPersistentChannel();
		long elapsed = Math.max(0L, System.nanoTime() - started);
		compactionCount++;
		compactionNanos += elapsed;
		slowestCompactionNanos = Math.max(slowestCompactionNanos, elapsed);
	}

	private static Loaded read(Path path, int maximumEntries) {
		if (!Files.exists(path)) return new Loaded(new LinkedHashMap<>(), 0, 0L, false, newDigest());
		try {
			byte[] bytes = Files.readAllBytes(path);
			if (bytes.length == 0) return new Loaded(new LinkedHashMap<>(), 0, 0L, false, newDigest());
			if (bytes[0] == '{') {
				return new Loaded(readLegacy(new String(bytes, StandardCharsets.UTF_8), maximumEntries), 0, bytes.length, true, newDigest());
			}
			if (bytes.length < LOG_HEADER.length) throw corrupt("log header is incomplete");
			for (int index = 0; index < LOG_HEADER.length; index++) {
				if (bytes[index] != LOG_HEADER[index]) throw corrupt("unsupported log header");
			}
			LinkedHashMap<ActionKey, Entry> decoded = new LinkedHashMap<>();
			int offset = LOG_HEADER.length;
			int eventCount = 0;
			while (offset < bytes.length) {
				int frameStart = offset;
				if (bytes.length - offset < FRAME_HEADER_BYTES) break;
				ByteBuffer header = ByteBuffer.wrap(bytes, offset, FRAME_HEADER_BYTES);
				int payloadLength = header.getInt();
				int expectedChecksum = header.getInt();
				offset += FRAME_HEADER_BYTES;
				if (payloadLength <= 0 || payloadLength > MAX_FRAME_BYTES) throw corrupt("invalid event frame length");
				if (bytes.length - offset < payloadLength) {
					offset = frameStart;
					break;
				}
				CRC32C checksum = new CRC32C();
				checksum.update(bytes, offset, payloadLength);
				if ((int) checksum.getValue() != expectedChecksum) {
					if (offset + payloadLength == bytes.length) {
						offset = frameStart;
						break;
					}
					throw corrupt("event frame checksum mismatch");
				}
				Mutation mutation = decodeMutation(new String(bytes, offset, payloadLength, StandardCharsets.UTF_8));
				apply(decoded, mutation, maximumEntries);
				offset += payloadLength;
				eventCount++;
			}
			MessageDigest prefix = newDigest();
			prefix.update(bytes, 0, offset);
			return new Loaded(decoded, eventCount, offset, false, prefix);
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (RuntimeException | IOException exception) {
			throw corrupt("could not read durable action journal", exception);
		}
	}

	private static LinkedHashMap<ActionKey, Entry> readLegacy(String serialized, int maximumEntries) {
		try {
			JsonElement parsed = JsonParser.parseString(serialized);
			if (!parsed.isJsonObject()) throw corrupt("root must be an object");
			JsonObject root = parsed.getAsJsonObject();
			if (requiredInt(root, "schemaVersion") != SCHEMA_VERSION) throw corrupt("unsupported schema version");
			JsonArray serializedEntries = requiredArray(root, "entries");
			if (serializedEntries.size() > maximumEntries) throw corrupt("entry limit exceeded");
			LinkedHashMap<ActionKey, Entry> decoded = new LinkedHashMap<>();
			for (JsonElement element : serializedEntries) {
				if (!element.isJsonObject()) throw corrupt("entry must be an object");
				Entry entry = decodeEntry(element.getAsJsonObject());
				if (decoded.put(ActionKey.from(entry.request()), entry) != null) throw corrupt("duplicate action identity");
			}
			return decoded;
		} catch (AgentDomainException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw corrupt("could not read legacy durable action journal", exception);
		}
	}

	private static MessageDigest writeSnapshot(Path path, Map<ActionKey, Entry> entries) {
		MessageDigest prefix = newDigest();
		prefix.update(LOG_HEADER);
		writeLog(path, channel -> {
			for (Entry entry : entries.values()) {
				byte[] frame = encodeFrame(new Mutation(List.of(), List.of(entry), List.of(), List.of()));
				writeFully(channel, ByteBuffer.wrap(frame));
				prefix.update(frame);
			}
		});
		return prefix;
	}

	private void openPersistentChannel() {
		if (path == null || persistentChannel != null) return;
		try {
			FileChannel opened = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
			try {
				if (failedAppendFrame != null) {
					recoverFailedAppend(opened);
				} else if (opened.size() != persistedBytes) {
					throw new IOException("action journal changed after it was read");
				} else {
					verifyCommittedPrefix(opened);
				}
				opened.position(persistedBytes);
				persistentChannel = opened;
			} catch (IOException | RuntimeException | Error failure) {
				try {
					opened.close();
				} catch (IOException closeFailure) {
					failure.addSuppressed(closeFailure);
				}
				throw failure;
			}
		} catch (IOException exception) {
			throw ioFailure("Could not open action journal for durable appends", exception);
		}
	}

	private void closePersistentChannel() {
		FileChannel active = persistentChannel;
		persistentChannel = null;
		if (active == null) return;
		try {
			active.close();
		} catch (IOException exception) {
			throw ioFailure("Could not close action journal", exception);
		}
	}

	private static void writeLog(Path path, FrameWriter frames) {
		Path parent = path.getParent();
		if (parent == null) throw new AgentDomainException("ACTION_JOURNAL_IO", "Action journal path has no parent directory");
		Path temporary = parent.resolve(path.getFileName() + ".tmp-" + UUID.randomUUID());
		try {
			Files.createDirectories(parent);
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
				writeFully(channel, ByteBuffer.wrap(LOG_HEADER));
				frames.write(channel);
				channel.force(true);
			}
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException exception) {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException suppressed) {
				exception.addSuppressed(suppressed);
			}
			throw new AgentDomainException("ACTION_JOURNAL_IO", "Could not durably write action journal: " + exception.getMessage());
		} catch (RuntimeException exception) {
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException suppressed) {
				exception.addSuppressed(suppressed);
			}
			throw exception;
		}
	}

	private void appendFrame(byte[] frame) {
		if (persistentChannel == null) {
			if (persistedBytes == 0L) {
				if (Files.exists(path) && fileSize(path) != 0L) {
					throw ioFailure("Could not create action journal", new IOException("action journal changed after it was read"));
				}
				writeLog(path, channel -> writeFully(channel, ByteBuffer.wrap(frame)));
				committedPrefix.update(LOG_HEADER);
				committedPrefix.update(frame);
				persistedBytes = fileSize(path);
				openPersistentChannel();
				return;
			}
			openPersistentChannel();
		}
		long originalSize = persistedBytes;
		boolean appendStarted = false;
		try {
			if (persistentChannel.size() != originalSize) throw new IOException("action journal changed after it was read");
			persistentChannel.position(originalSize);
			appendStarted = true;
			writeFully(persistentChannel, ByteBuffer.wrap(frame));
			forceHook.beforeForce();
			persistentChannel.force(true);
			forceHook.afterForce();
			persistedBytes += frame.length;
			committedPrefix.update(frame);
		} catch (IOException exception) {
			if (appendStarted) {
				failedAppendFrame = frame;
				try {
					recoverFailedAppend(persistentChannel);
				} catch (IOException suppressed) {
					exception.addSuppressed(suppressed);
				}
			}
			FileChannel failed = persistentChannel;
			persistentChannel = null;
			try {
				failed.close();
			} catch (IOException suppressed) {
				exception.addSuppressed(suppressed);
			}
			throw ioFailure("Could not durably append action journal", exception);
		}
	}

	private void recoverFailedAppend(FileChannel channel) throws IOException {
		long tailBytes = channel.size() - persistedBytes;
		if (tailBytes < 0L || tailBytes > failedAppendFrame.length) {
			throw new IOException("action journal changed after the failed append");
		}
		verifyCommittedPrefix(channel);
		ByteBuffer tail = ByteBuffer.allocate((int) tailBytes);
		channel.position(persistedBytes);
		while (tail.hasRemaining()) {
			if (channel.read(tail) < 0) throw new IOException("incomplete failed append tail");
		}
		for (int index = 0; index < tailBytes; index++) {
			if (tail.array()[index] != failedAppendFrame[index]) {
				throw new IOException("action journal tail does not belong to the failed append");
			}
		}
		channel.truncate(persistedBytes);
		channel.force(true);
		failedAppendFrame = null;
	}

	private void verifyCommittedPrefix(FileChannel channel) throws IOException {
		MessageDigest actual = newDigest();
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		channel.position(0L);
		long remaining = persistedBytes;
		while (remaining > 0L) {
			buffer.clear().limit((int) Math.min(buffer.capacity(), remaining));
			int count = channel.read(buffer);
			if (count < 0) throw new IOException("committed action journal prefix is incomplete");
			remaining -= count;
			buffer.flip();
			actual.update(buffer);
		}
		try {
			if (!MessageDigest.isEqual(actual.digest(), ((MessageDigest) committedPrefix.clone()).digest())) {
				throw new IOException("committed action journal prefix changed after it was read");
			}
		} catch (CloneNotSupportedException exception) {
			throw new IOException("could not verify committed action journal prefix", exception);
		}
	}

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

	private static AgentDomainException ioFailure(String message, IOException cause) {
		AgentDomainException failure = new AgentDomainException("ACTION_JOURNAL_IO", message + ": " + cause.getMessage());
		failure.initCause(cause);
		return failure;
	}

	private static byte[] encodeFrame(Mutation mutation) {
		byte[] payload = encodeMutation(mutation).getBytes(StandardCharsets.UTF_8);
		if (payload.length == 0 || payload.length > MAX_FRAME_BYTES) {
			throw new AgentDomainException("ACTION_JOURNAL_IO", "Action journal event exceeds the durable frame limit");
		}
		CRC32C checksum = new CRC32C();
		checksum.update(payload, 0, payload.length);
		ByteBuffer frame = ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.length);
		frame.putInt(payload.length).putInt((int) checksum.getValue()).put(payload);
		return frame.array();
	}

	private static String encodeMutation(Mutation mutation) {
		JsonObject encoded = new JsonObject();
		JsonArray removed = new JsonArray();
		for (ActionKey key : mutation.removed()) removed.add(encodeKey(key));
		encoded.add("remove", removed);
		JsonArray put = new JsonArray();
		for (Entry entry : mutation.put()) put.add(encodeEntry(entry));
		encoded.add("put", put);
		JsonArray terminal = new JsonArray();
		for (ServerActionResult result : mutation.terminal()) terminal.add(encodeResult(result));
		encoded.add("terminal", terminal);
		JsonArray acknowledged = new JsonArray();
		for (ActionKey key : mutation.acknowledged()) acknowledged.add(encodeKey(key));
		encoded.add("acknowledged", acknowledged);
		return encoded.toString();
	}

	private static Mutation decodeMutation(String serialized) {
		JsonElement parsed = JsonParser.parseString(serialized);
		if (!parsed.isJsonObject()) throw corrupt("event must be an object");
		JsonObject encoded = parsed.getAsJsonObject();
		List<ActionKey> removed = new ArrayList<>();
		for (JsonElement element : requiredArray(encoded, "remove")) {
			if (!element.isJsonObject()) throw corrupt("removed identity must be an object");
			removed.add(decodeKey(element.getAsJsonObject()));
		}
		List<Entry> put = new ArrayList<>();
		for (JsonElement element : requiredArray(encoded, "put")) {
			if (!element.isJsonObject()) throw corrupt("put entry must be an object");
			put.add(decodeEntry(element.getAsJsonObject()));
		}
		List<ServerActionResult> terminal = new ArrayList<>();
		for (JsonElement element : requiredArray(encoded, "terminal")) {
			if (!element.isJsonObject()) throw corrupt("terminal result must be an object");
			terminal.add(decodeResult(element.getAsJsonObject()));
		}
		List<ActionKey> acknowledged = new ArrayList<>();
		for (JsonElement element : requiredArray(encoded, "acknowledged")) {
			if (!element.isJsonObject()) throw corrupt("acknowledged identity must be an object");
			acknowledged.add(decodeKey(element.getAsJsonObject()));
		}
		if (removed.isEmpty() && put.isEmpty() && terminal.isEmpty() && acknowledged.isEmpty()) {
			throw corrupt("event must change the journal");
		}
		return new Mutation(List.copyOf(removed), List.copyOf(put),
				List.copyOf(terminal), List.copyOf(acknowledged));
	}

	private static void apply(LinkedHashMap<ActionKey, Entry> entries, Mutation mutation, int maximumEntries) {
		for (ActionKey key : mutation.removed()) entries.remove(key);
		for (Entry entry : mutation.put()) entries.put(ActionKey.from(entry.request()), entry);
		for (ServerActionResult result : mutation.terminal()) {
			ActionKey key = ActionKey.from(result);
			Entry prior = entries.get(key);
			if (prior == null) throw corrupt("terminal result has no acceptance record");
			verifyResult(prior.request(), result);
			if (prior.phase() != Phase.ACCEPTED) {
				if (!prior.result().equals(result)) throw corrupt("terminal result conflicts with durable result");
				continue;
			}
			entries.put(key, new Entry(prior.request(), prior.logicalGoalId(), Phase.TERMINAL, result));
		}
		for (ActionKey key : mutation.acknowledged()) {
			Entry prior = entries.get(key);
			if (prior == null || prior.phase() == Phase.ACCEPTED) throw corrupt("acknowledgement has no terminal result");
			entries.put(key, new Entry(prior.request(), prior.logicalGoalId(), Phase.ACKNOWLEDGED, prior.result()));
		}
		if (entries.size() > maximumEntries) throw corrupt("entry limit exceeded");
	}

	private static void applyMutation(LinkedHashMap<ActionKey, Entry> entries, Mutation mutation) {
		for (ActionKey key : mutation.removed()) entries.remove(key);
		for (Entry entry : mutation.put()) entries.put(ActionKey.from(entry.request()), entry);
		for (ServerActionResult result : mutation.terminal()) {
			ActionKey key = ActionKey.from(result);
			Entry prior = entries.get(key);
			entries.put(key, new Entry(prior.request(), prior.logicalGoalId(), Phase.TERMINAL, result));
		}
		for (ActionKey key : mutation.acknowledged()) {
			Entry prior = entries.get(key);
			entries.put(key, new Entry(prior.request(), prior.logicalGoalId(), Phase.ACKNOWLEDGED, prior.result()));
		}
	}

	private static JsonObject encodeKey(ActionKey key) {
		JsonObject encoded = new JsonObject();
		encoded.addProperty("agentId", key.agentId().toString());
		encoded.addProperty("goalRevision", key.goalRevision());
		encoded.addProperty("actionId", key.actionId());
		return encoded;
	}

	private static ActionKey decodeKey(JsonObject encoded) {
		return new ActionKey(AgentId.parse(requiredString(encoded, "agentId")),
				requiredLong(encoded, "goalRevision"), requiredString(encoded, "actionId"));
	}

	private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.hasRemaining()) channel.write(buffer);
	}

	private static void truncateTail(Path path, long validBytes) {
		try {
			if (Files.size(path) == validBytes) return;
			try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
				channel.truncate(validBytes);
				channel.force(true);
			}
		} catch (IOException exception) {
			throw new AgentDomainException("ACTION_JOURNAL_IO", "Could not repair incomplete journal tail: " + exception.getMessage());
		}
	}

	private static long fileSize(Path path) {
		try {
			return Files.size(path);
		} catch (IOException exception) {
			throw new AgentDomainException("ACTION_JOURNAL_IO", "Could not inspect action journal: " + exception.getMessage());
		}
	}

	private static JsonObject encodeEntry(Entry entry) {
		JsonObject encoded = new JsonObject();
		if (entry.logicalGoalId() != null) encoded.addProperty("logicalGoalId", entry.logicalGoalId().toString());
		encoded.addProperty("phase", entry.phase().name());
		encoded.add("request", encodeRequest(entry.request()));
		if (entry.result() != null) encoded.add("result", encodeResult(entry.result()));
		return encoded;
	}

	private static Entry decodeEntry(JsonObject encoded) {
		UUID logicalGoalId = encoded.has("logicalGoalId") ? UUID.fromString(requiredString(encoded, "logicalGoalId")) : null;
		Phase phase = Phase.valueOf(requiredString(encoded, "phase"));
		ServerActionRequest request = decodeRequest(requiredObject(encoded, "request"));
		ServerActionResult result = encoded.has("result") ? decodeResult(requiredObject(encoded, "result")) : null;
		if ((phase == Phase.ACCEPTED) != (result == null)) throw corrupt("entry phase and result disagree");
		if (result != null) verifyResult(request, result);
		return new Entry(request, logicalGoalId, phase, result);
	}

	private static JsonObject encodeRequest(ServerActionRequest request) {
		JsonObject encoded = new JsonObject();
		encoded.addProperty("agentId", request.agentId().toString());
		encoded.addProperty("goalRevision", request.goalRevision());
		encoded.addProperty("actionId", request.actionId());
		encoded.addProperty("actionType", request.type().wireName());
		encoded.add("arguments", request.arguments());
		if (request.traceId() != null) encoded.addProperty("traceId", request.traceId());
		encoded.add("provenance", encodeProvenance(request.provenance()));
		return encoded;
	}

	private static ServerActionRequest decodeRequest(JsonObject encoded) {
		ActionType type = ActionType.fromWireName(requiredString(encoded, "actionType"))
				.orElseThrow(() -> corrupt("unknown action type"));
		JsonObject arguments = ProtocolCodec.validateActionArguments(type, requiredObject(encoded, "arguments"));
		ActionProvenance provenance = decodeProvenance(requiredObject(encoded, "provenance"));
		String traceId = encoded.has("traceId") ? requiredString(encoded, "traceId") : null;
		return new ServerActionRequest(
				AgentId.parse(requiredString(encoded, "agentId")), requiredLong(encoded, "goalRevision"),
				requiredString(encoded, "actionId"), type, arguments, provenance, traceId
		);
	}

	private static JsonObject encodeProvenance(ActionProvenance provenance) {
		JsonObject encoded = new JsonObject();
		encoded.addProperty("provider", provenance.provider());
		encoded.addProperty("model", provenance.model());
		encoded.addProperty("reasoningEffort", provenance.reasoningEffort());
		encoded.addProperty("serviceTier", provenance.serviceTier());
		encoded.addProperty("programId", provenance.programId());
		encoded.addProperty("programVersion", provenance.programVersion());
		encoded.addProperty("sourceStepId", provenance.sourceStepId());
		encoded.addProperty("eventSequence", provenance.eventSequence());
		if (provenance.traceId() != null) encoded.addProperty("traceId", provenance.traceId());
		if (provenance.watcherId() != null) encoded.addProperty("watcherId", provenance.watcherId());
		return encoded;
	}

	private static ActionProvenance decodeProvenance(JsonObject encoded) {
		return new ActionProvenance(
				requiredString(encoded, "provider"), requiredString(encoded, "model"),
				requiredString(encoded, "reasoningEffort"), requiredString(encoded, "serviceTier"),
				requiredString(encoded, "programId"), requiredLong(encoded, "programVersion"),
				requiredString(encoded, "sourceStepId"), requiredLong(encoded, "eventSequence"),
				encoded.has("traceId") ? requiredString(encoded, "traceId") : null,
				encoded.has("watcherId") ? requiredString(encoded, "watcherId") : null
		);
	}

	/** One canonical representation for durable replay and retired-result identity. */
	static JsonObject encodeResult(ServerActionResult result) {
		JsonObject encoded = new JsonObject();
		encoded.addProperty("agentId", result.agentId().toString());
		encoded.addProperty("goalRevision", result.goalRevision());
		encoded.addProperty("actionId", result.actionId());
		encoded.addProperty("actionType", result.actionType().wireName());
		if (result.traceId() != null) encoded.addProperty("traceId", result.traceId());
		encoded.addProperty("state", result.state().name());
		encoded.addProperty("reasonCode", result.reasonCode());
		encoded.addProperty("message", result.message());
		encoded.addProperty("elapsedMs", result.elapsedMs());
		encoded.addProperty("observedAtEpochMs", result.observedAtEpochMs());
		encoded.addProperty("executionStarted", result.executionStarted());
		encoded.addProperty("physicalAttempted", result.physicalAttempted());
		if (result.actionObservation() != null) {
			encoded.add("actionObservation", OBSERVATION_CODEC.toJsonTree(result.actionObservation()));
		}
		return encoded;
	}

	private static ServerActionResult decodeResult(JsonObject encoded) {
		ActionType type = ActionType.fromWireName(requiredString(encoded, "actionType"))
				.orElseThrow(() -> corrupt("unknown result action type"));
		return new ServerActionResult(
				AgentId.parse(requiredString(encoded, "agentId")), requiredLong(encoded, "goalRevision"),
				requiredString(encoded, "actionId"), type,
				encoded.has("traceId") ? requiredString(encoded, "traceId") : null,
				ServerActionState.valueOf(requiredString(encoded, "state")), requiredString(encoded, "reasonCode"),
				requiredString(encoded, "message"), requiredLong(encoded, "elapsedMs"),
				requiredLong(encoded, "observedAtEpochMs"), requiredBoolean(encoded, "executionStarted"),
				requiredBoolean(encoded, "physicalAttempted"),
				encoded.has("actionObservation") ? decodeObservation(requiredObject(encoded, "actionObservation")) : null
		);
	}

	private static ServerActionObservation decodeObservation(JsonObject encoded) {
		ServerActionObservation observation = OBSERVATION_CODEC.fromJson(encoded, ServerActionObservation.class);
		// Gson invokes the record constructors for semantic validation. Re-encoding also
		// rejects missing fields, primitive defaults, and type coercion in a damaged snapshot.
		if (!OBSERVATION_CODEC.toJsonTree(observation).equals(encoded)) {
			throw corrupt("actionObservation must contain a complete, correctly typed snapshot");
		}
		return observation;
	}

	private static void verifyResult(ServerActionRequest request, ServerActionResult result) {
		if (!request.agentId().equals(result.agentId()) || request.goalRevision() != result.goalRevision()
				|| !request.actionId().equals(result.actionId()) || request.type() != result.actionType()
				|| !Objects.equals(request.traceId(), result.traceId())) {
			throw new AgentDomainException("ACTION_RESULT_REPLAY_CONFLICT", "Terminal result does not match its durable request");
		}
	}

	private static JsonObject requiredObject(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonObject()) throw corrupt(field + " must be an object");
		return value.getAsJsonObject();
	}

	private static JsonArray requiredArray(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonArray()) throw corrupt(field + " must be an array");
		return value.getAsJsonArray();
	}

	private static String requiredString(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw corrupt(field + " must be a string");
		return value.getAsString();
	}

	private static long requiredLong(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw corrupt(field + " must be an integer");
		return value.getAsLong();
	}

	private static int requiredInt(JsonObject object, String field) {
		return Math.toIntExact(requiredLong(object, field));
	}

	private static boolean requiredBoolean(JsonObject object, String field) {
		JsonElement value = object.get(field);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw corrupt(field + " must be a boolean");
		return value.getAsBoolean();
	}

	private static AgentDomainException corrupt(String message) {
		return new AgentDomainException("ACTION_JOURNAL_CORRUPT", "Durable action journal is corrupt: " + message);
	}

	private static AgentDomainException corrupt(String message, Exception cause) {
		AgentDomainException exception = corrupt(message);
		exception.initCause(cause);
		return exception;
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		closePersistentChannel();
	}

	enum Phase { ACCEPTED, TERMINAL, ACKNOWLEDGED }

	record Entry(ServerActionRequest request, UUID logicalGoalId, Phase phase, ServerActionResult result) {
		Entry {
			Objects.requireNonNull(request, "request must not be null");
			Objects.requireNonNull(phase, "phase must not be null");
		}
	}

	private record Mutation(
			List<ActionKey> removed,
			List<Entry> put,
			List<ServerActionResult> terminal,
			List<ActionKey> acknowledged
	) { }

	private record Loaded(
			LinkedHashMap<ActionKey, Entry> entries,
			int eventCount,
			long persistedBytes,
			boolean legacy,
			MessageDigest committedPrefix
	) { }

	record PerformanceSnapshot(
			int persistedEventCount,
			long persistedBytes,
			long appendCount,
			long compactionCount,
			long appendNanos,
			long slowestAppendNanos,
			long compactionNanos,
			long slowestCompactionNanos
	) { }

	@FunctionalInterface
	private interface FrameWriter {
		void write(FileChannel channel) throws IOException;
	}

	interface ForceHook {
		ForceHook NONE = new ForceHook() {
			@Override public void beforeForce() { }
			@Override public void afterForce() { }
		};

		void beforeForce();
		void afterForce();
	}

	record ActionKey(AgentId agentId, long goalRevision, String actionId) {
		private static ActionKey from(ServerActionRequest request) {
			return new ActionKey(request.agentId(), request.goalRevision(), request.actionId());
		}

		private static ActionKey from(ServerActionResult result) {
			return new ActionKey(result.agentId(), result.goalRevision(), result.actionId());
		}
	}
}
