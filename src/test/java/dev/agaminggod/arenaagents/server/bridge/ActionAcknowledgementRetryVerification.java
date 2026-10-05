package dev.agaminggod.arenaagents.server.bridge;

import com.google.gson.JsonObject;
import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.protocol.ActionType;
import dev.agaminggod.arenaagents.server.runtime.ActionProvenance;
import dev.agaminggod.arenaagents.server.runtime.ServerActionRequest;
import dev.agaminggod.arenaagents.server.runtime.ServerActionResult;
import dev.agaminggod.arenaagents.server.runtime.ServerActionState;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Exercises real disk commits, failure retention, same-session retry and crash replay. */
public final class ActionAcknowledgementRetryVerification {
	public static void main(String[] args) throws Exception {
		System.out.println("ActionAcknowledgementRetryVerification assertions=" + verify());
	}

	public static int verify() throws Exception {
		var path = Files.createTempDirectory("arena-ack-retry-").resolve("actions.journal");
		var agent = AgentId.random();
		var goal = UUID.randomUUID();
		var ledger = new TerminalResultLedger();
		var session = new Object();
		try (var journal = DurableActionJournal.open(path)) {
			for (int i = 0; i < 3; i++) {
				var request = request(agent, "action-" + i);
				journal.accept(request, goal);
				var result = result(request);
				journal.terminal(result);
				ledger.retain(result);
				require(ledger.claim(result, session), "initial result owns exactly one delivery claim");
				require(journal.queueAcknowledgement(agent, 1L, request.actionId()), "terminal ACK staged");
			}
			require(journal.queueAcknowledgement(agent, 1L, "action-0"), "duplicate ACK coalesces");
			require(!journal.queueAcknowledgement(agent, 2L, "action-0"), "wrong revision is not acknowledged");
			require(!journal.queueAcknowledgement(AgentId.random(), 1L, "action-0"), "wrong agent is not acknowledged");
			var running = request(agent, "running");
			journal.accept(running, goal);
			require(!journal.queueAcknowledgement(agent, 1L, "running"), "acceptance alone cannot be acknowledged");
			int before = journal.persistedEventCountForVerification();
			try (var beforeCommit = DurableActionJournal.open(path)) {
				require(beforeCommit.snapshot().stream().filter(e -> e.phase() == DurableActionJournal.Phase.TERMINAL).count() == 3,
						"crash before ACK group commit retains all terminal receipts");
			}
			var field = DurableActionJournal.class.getDeclaredField("persistentChannel");
			field.setAccessible(true);
			((FileChannel) field.get(journal)).close();
			try {
				journal.flushAcknowledgements();
				throw new AssertionError("closed append channel should fail the ACK group");
			} catch (AgentDomainException expected) {
				require("ACTION_JOURNAL_IO".equals(expected.code()), "injected failure is a journal I/O error");
			}
			require(journal.persistedEventCountForVerification() == before, "failed group does not count as a commit");
			require(ledger.pendingCount() == 3, "failed ACK commit does not retire terminal delivery");
			for (var result : ledger.pending()) require(!ledger.claim(result, session), "retry does not duplicate session replay");
			var committed = journal.flushAcknowledgements();
			require(committed.size() == 3, "same journal retries all failed identities without reconnect");
			require(journal.persistedEventCountForVerification() == before + 1, "three ACKs use one durable frame");
			for (var key : committed) ledger.acknowledge(key.agentId(), key.goalRevision(), key.actionId());
			require(ledger.pendingCount() == 0, "ledger retires only after durable ACK completion");
			require(journal.flushAcknowledgements().isEmpty(), "completed group cannot be replayed");
			journal.queueAcknowledgement(agent, 1L, "action-0");
			journal.flushAcknowledgements();
			require(journal.persistedEventCountForVerification() == before + 1, "already durable ACK does not append again");
		}
		try (var reload = DurableActionJournal.open(path)) {
			require(reload.snapshot().stream().filter(e -> e.phase() == DurableActionJournal.Phase.ACKNOWLEDGED).count() == 3,
					"all grouped ACKs survive reopen");
			require(reload.snapshot().stream().filter(e -> e.phase() == DurableActionJournal.Phase.ACCEPTED).count() == 1,
					"group does not acknowledge an active action");
		}
		var tornPath = path.resolveSibling("torn-ack-group.journal");
		Files.copy(path, tornPath);
		try (var channel = FileChannel.open(tornPath, StandardOpenOption.WRITE)) {
			channel.truncate(channel.size() - 1);
		}
		try (var torn = DurableActionJournal.open(tornPath)) {
			require(torn.snapshot().stream().filter(e -> e.phase() == DurableActionJournal.Phase.TERMINAL).count() == 3,
					"torn ACK frame retains every terminal identity for replay");
			require(torn.snapshot().stream().noneMatch(e -> e.phase() == DurableActionJournal.Phase.ACKNOWLEDGED),
					"torn ACK frame cannot acknowledge only part of the group");
		}
		return 26 + verifyClosedPartialAppend(false) + verifyClosedPartialAppend(true)
				+ verifyChangedPrefixOrTail(true) + verifyChangedPrefixOrTail(false)
				+ verifyUnownedTail();
	}

	private static int verifyClosedPartialAppend(boolean compact) throws Exception {
		Path path = Files.createTempDirectory("arena-ack-partial-").resolve("actions.journal");
		var request = request(AgentId.random(), "partial-ack");
		var goal = UUID.randomUUID();
		try (var initial = DurableActionJournal.open(path)) {
			initial.accept(request, goal);
			initial.terminal(result(request));
		}
		// Load the committed prefix from disk, including the original request and goal identity.
		try (var journal = DurableActionJournal.open(path, 4, compact ? 2 : 128)) {
			var committedEntry = journal.snapshot().getFirst();
			journal.queueAcknowledgement(request.agentId(), 1L, request.actionId());
			if (compact) {
				// Acquire the channel through compaction before injecting the failed ACK append.
				var method = DurableActionJournal.class.getDeclaredMethod("compact");
				method.setAccessible(true);
				method.invoke(journal);
			} else {
				var method = DurableActionJournal.class.getDeclaredMethod("openPersistentChannel");
				method.setAccessible(true);
				method.invoke(journal);
			}
			int events = journal.persistedEventCountForVerification();
			long committedBytes = Files.size(path);
			injectPartialAppend(journal);
			expectIo(journal::flushAcknowledgements);
			require(Files.size(path) == committedBytes + 3, "closed channel leaves three actual frame bytes");
			require(journal.snapshot().equals(List.of(committedEntry)), "failed append retains terminal receipt and identity");
			require(journal.persistedEventCountForVerification() == events, "partial append is not counted as committed");
			var acknowledged = journal.flushAcknowledgements();
			require(acknowledged.equals(List.of(new DurableActionJournal.ActionKey(request.agentId(), 1L, request.actionId()))),
					"same instance retries the exact pending identity after its channel closes");
			require(journal.flushAcknowledgements().isEmpty(), "recovered ACK is returned exactly once");
			require(journal.persistedEventCountForVerification() == events + 1, "retry commits exactly one ACK frame");
		}
		try (var reloaded = DurableActionJournal.open(path)) {
			var entry = reloaded.snapshot().getFirst();
			require(reloaded.snapshot().size() == 1 && entry.phase() == DurableActionJournal.Phase.ACKNOWLEDGED,
					"recovered ACK survives reopen");
			require(entry.request().equals(request) && entry.logicalGoalId().equals(goal) && entry.result().equals(result(request)),
					"recovery preserves the persisted request, goal and terminal result");
		}
		return 9;
	}

	private static int verifyChangedPrefixOrTail(boolean prefix) throws Exception {
		Path path = Files.createTempDirectory("arena-ack-changed-").resolve("actions.journal");
		var request = request(AgentId.random(), "changed-ack");
		try (var journal = DurableActionJournal.open(path)) {
			journal.accept(request, UUID.randomUUID());
			journal.terminal(result(request));
			journal.queueAcknowledgement(request.agentId(), 1L, request.actionId());
			var terminal = journal.snapshot();
			long committedBytes = Files.size(path);
			injectPartialAppend(journal);
			expectIo(journal::flushAcknowledgements);
			byte[] changed = Files.readAllBytes(path);
			changed[prefix ? 0 : (int) committedBytes] ^= 1;
			Files.write(path, changed);
			for (int retry = 0; retry < 3; retry++) {
				expectIo(journal::flushAcknowledgements);
				require(Arrays.equals(Files.readAllBytes(path), changed), "foreign changes must not be truncated or overwritten");
				require(journal.snapshot().equals(terminal), "failed validation cannot acknowledge or forget terminal identities");
			}
		}
		return 10;
	}

	private static int verifyUnownedTail() throws Exception {
		Path path = Files.createTempDirectory("arena-ack-unowned-").resolve("actions.journal");
		var request = request(AgentId.random(), "unowned-tail");
		try (var journal = DurableActionJournal.open(path)) {
			journal.accept(request, UUID.randomUUID());
			journal.terminal(result(request));
		}
		try (var journal = DurableActionJournal.open(path)) {
			journal.queueAcknowledgement(request.agentId(), 1L, request.actionId());
			Files.write(path, new byte[] {0, 0, 0}, StandardOpenOption.APPEND);
			byte[] changed = Files.readAllBytes(path);
			for (int retry = 0; retry < 2; retry++) {
				expectIo(journal::flushAcknowledgements);
				require(Arrays.equals(Files.readAllBytes(path), changed), "a tail without an owned failed append is rejected");
			}
		}
		return 4;
	}

	private static void expectIo(Runnable operation) {
		try {
			operation.run();
			throw new AssertionError("expected durable append failure");
		} catch (AgentDomainException expected) {
			require("ACTION_JOURNAL_IO".equals(expected.code()), "append failure is a journal I/O error");
		}
	}

	private static void injectPartialAppend(DurableActionJournal journal) throws Exception {
		var field = DurableActionJournal.class.getDeclaredField("persistentChannel");
		field.setAccessible(true);
		field.set(journal, new ClosingPartialWriteChannel((FileChannel) field.get(journal)));
	}

	/** Fail inside the real append, after writing its first three bytes to the real journal. */
	private static final class ClosingPartialWriteChannel extends FileChannel {
		private final FileChannel delegate;

		private ClosingPartialWriteChannel(FileChannel delegate) { this.delegate = delegate; }

		@Override public int write(ByteBuffer source) throws IOException {
			ByteBuffer partial = source.slice();
			partial.limit(Math.min(3, partial.remaining()));
			while (partial.hasRemaining()) delegate.write(partial);
			close();
			throw new IOException("injected partial append followed by channel closure");
		}
		@Override public long position() throws IOException { return delegate.position(); }
		@Override public FileChannel position(long position) throws IOException { delegate.position(position); return this; }
		@Override public long size() throws IOException { return delegate.size(); }
		@Override public FileChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
		@Override public void force(boolean metadata) throws IOException { delegate.force(metadata); }
		@Override public int read(ByteBuffer target) throws IOException { return delegate.read(target); }
		@Override public int read(ByteBuffer target, long position) throws IOException { return delegate.read(target, position); }
		@Override public long read(ByteBuffer[] targets, int offset, int length) throws IOException { return delegate.read(targets, offset, length); }
		@Override public int write(ByteBuffer source, long position) throws IOException { return delegate.write(source, position); }
		@Override public long write(ByteBuffer[] sources, int offset, int length) throws IOException { return delegate.write(sources, offset, length); }
		@Override public long transferTo(long position, long count, WritableByteChannel target) throws IOException { return delegate.transferTo(position, count, target); }
		@Override public long transferFrom(ReadableByteChannel source, long position, long count) throws IOException { return delegate.transferFrom(source, position, count); }
		@Override public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException { return delegate.map(mode, position, size); }
		@Override public FileLock lock(long position, long size, boolean shared) throws IOException { return delegate.lock(position, size, shared); }
		@Override public FileLock tryLock(long position, long size, boolean shared) throws IOException { return delegate.tryLock(position, size, shared); }
		@Override protected void implCloseChannel() throws IOException { delegate.close(); }
	}

	static ServerActionRequest request(AgentId agent, String action) {
		var arguments = new JsonObject();
		arguments.addProperty("durationMs", 50L);
		return new ServerActionRequest(agent, 1L, action, ActionType.WAIT, arguments,
				new ActionProvenance("codex", "test", "high", "fast", "program", 1L, action, 1L), null);
	}

	static ServerActionResult result(ServerActionRequest request) {
		return new ServerActionResult(request.agentId(), request.goalRevision(), request.actionId(), request.type(), null,
				ServerActionState.SUCCEEDED, "DONE", "Finished", 50L, 100L, true, true, null);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
