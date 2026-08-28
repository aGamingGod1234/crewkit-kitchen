package dev.agaminggod.arenaagents.voiceaddon;

import dev.agaminggod.arenaagents.agent.AgentId;
import dev.agaminggod.arenaagents.server.voice.VoiceReceipt;
import dev.agaminggod.arenaagents.server.voice.VoiceRequest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

final class VoicePlaybackCoordinator implements AutoCloseable {
	private final Synthesizer synthesizer;
	private final Executor playbackExecutor;
	private final Transport transport;
	private final Map<AgentId, UUID> entities = new LinkedHashMap<>();
	private final Map<AgentId, Playback> players = new LinkedHashMap<>();
	private final Map<AgentId, CompletableFuture<VoiceReceipt>> pending = new LinkedHashMap<>();
	private boolean closed;

	VoicePlaybackCoordinator(Synthesizer synthesizer, Executor playbackExecutor, Transport transport) {
		this.synthesizer = Objects.requireNonNull(synthesizer, "synthesizer must not be null");
		this.playbackExecutor = Objects.requireNonNull(playbackExecutor, "playbackExecutor must not be null");
		this.transport = Objects.requireNonNull(transport, "transport must not be null");
	}

	synchronized boolean available() {
		return !closed && transport.available();
	}

	void registerAgent(AgentId agentId, UUID entityId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(entityId, "entityId must not be null");
		boolean changed;
		synchronized (this) {
			changed = !entityId.equals(entities.get(agentId));
		}
		if (changed) stop(agentId);
		synchronized (this) {
			if (!closed) entities.put(agentId, entityId);
		}
	}

	void unregisterAgent(AgentId agentId) {
		stop(agentId);
		synchronized (this) {
			entities.remove(agentId);
		}
	}

	CompletionStage<VoiceReceipt> speak(VoiceRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		synchronized (this) {
			if (!available() || !entities.containsKey(request.agentId())) {
				return CompletableFuture.completedFuture(VoiceReceipt.degraded("Voice channel is unavailable"));
			}
		}
		stop(request.agentId());
		CompletableFuture<VoiceReceipt> result = new CompletableFuture<>();
		synchronized (this) {
			if (!available() || !entities.containsKey(request.agentId())) {
				return CompletableFuture.completedFuture(VoiceReceipt.degraded("Voice channel is unavailable"));
			}
			pending.put(request.agentId(), result);
		}
		CompletionStage<short[]> synthesis;
		try {
			synthesis = Objects.requireNonNull(synthesizer.synthesize(request), "synthesizer returned null");
		} catch (RuntimeException exception) {
			complete(request.agentId(), result, failed(exception));
			return result;
		}
		synthesis.whenComplete((samples, failure) -> {
			if (failure != null) {
				complete(request.agentId(), result, failed(failure));
				return;
			}
			try {
				playbackExecutor.execute(() -> startPlayback(request, samples, result));
			} catch (RuntimeException exception) {
				complete(request.agentId(), result, failed(exception));
			}
		});
		return result;
	}

	private void startPlayback(
			VoiceRequest request,
			short[] samples,
			CompletableFuture<VoiceReceipt> result
	) {
		UUID entityId;
		synchronized (this) {
			if (closed || pending.get(request.agentId()) != result || !transport.available()) {
				complete(request.agentId(), result, VoiceReceipt.degraded("Voice channel closed before playback"));
				return;
			}
			entityId = entities.get(request.agentId());
			if (entityId == null) {
				complete(request.agentId(), result, VoiceReceipt.degraded("Agent entity is unavailable"));
				return;
			}
		}
		Playback playback;
		try {
			playback = Objects.requireNonNull(transport.create(
					request.agentId(), entityId, request.radius(), samples,
					() -> complete(request.agentId(), result, new VoiceReceipt(
							VoiceReceipt.Status.PLAYED, "Speech playback finished"
					))
			), "transport returned null playback");
		} catch (UnavailableException exception) {
			complete(request.agentId(), result, VoiceReceipt.degraded(exception.getMessage()));
			return;
		} catch (RuntimeException exception) {
			complete(request.agentId(), result, failed(exception));
			return;
		}
		synchronized (this) {
			if (closed || pending.get(request.agentId()) != result) {
				playback.stop();
				return;
			}
			players.put(request.agentId(), playback);
		}
		try {
			playback.start();
		} catch (RuntimeException exception) {
			complete(request.agentId(), result, failed(exception));
			try {
				playback.stop();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private void complete(AgentId agentId, CompletableFuture<VoiceReceipt> expected, VoiceReceipt receipt) {
		synchronized (this) {
			if (pending.get(agentId) != expected) return;
			pending.remove(agentId);
			players.remove(agentId);
		}
		expected.complete(receipt);
	}

	void stop(AgentId agentId) {
		Playback player;
		CompletableFuture<VoiceReceipt> future;
		synchronized (this) {
			player = players.remove(agentId);
			future = pending.remove(agentId);
		}
		if (future != null) future.complete(new VoiceReceipt(VoiceReceipt.Status.FAILED, "Speech stopped"));
		if (player != null) player.stop();
	}

	@Override
	public void close() {
		Set<AgentId> active;
		synchronized (this) {
			if (closed) return;
			closed = true;
			active = new LinkedHashSet<>(pending.keySet());
			active.addAll(players.keySet());
		}
		for (AgentId agentId : active) stop(agentId);
		synchronized (this) {
			entities.clear();
		}
	}

	private static VoiceReceipt failed(Throwable throwable) {
		return new VoiceReceipt(VoiceReceipt.Status.FAILED, safeMessage(throwable));
	}

	private static String safeMessage(Throwable throwable) {
		Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	@FunctionalInterface
	interface Synthesizer {
		CompletionStage<short[]> synthesize(VoiceRequest request);
	}

	interface Transport {
		boolean available();

		Playback create(AgentId agentId, UUID entityId, int radius, short[] samples, Runnable onStopped);
	}

	interface Playback {
		void start();

		void stop();
	}

	static final class UnavailableException extends RuntimeException {
		UnavailableException(String message) {
			super(message);
		}
	}
}
