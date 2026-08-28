package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Owns one lazily constructed speech capture for each configured server. */
final class ServerSpeechCaptureRegistry<S, O> {
	private final Map<S, Entry> entries = new ConcurrentHashMap<>();
	private final CaptureFactory factory;

	ServerSpeechCaptureRegistry(CaptureFactory factory) {
		this.factory = Objects.requireNonNull(factory, "capture factory must not be null");
	}

	void configure(S server, O owner, VoiceSubsystemConfiguration configuration) {
		Objects.requireNonNull(server, "server must not be null");
		Entry previous = entries.put(server, new Entry(owner, configuration));
		if (previous != null) previous.close();
	}

	void accept(S server, O owner, MicrophonePacketEvent event) {
		Entry entry = entries.get(server);
		if (entry != null) entry.accept(owner, event);
	}

	void clear(S server) {
		Entry removed = entries.remove(server);
		if (removed != null) removed.close();
	}

	void clearOwner(O owner) {
		for (Map.Entry<S, Entry> candidate : entries.entrySet()) {
			Entry entry = candidate.getValue();
			if (entry.ownedBy(owner) && entries.remove(candidate.getKey(), entry)) entry.close();
		}
	}

	interface Capture {
		void accept(MicrophonePacketEvent event);

		void close();
	}

	@FunctionalInterface
	interface CaptureFactory {
		Capture create(VoiceSubsystemConfiguration configuration);
	}

	private final class Entry {
		private O owner;
		private final VoiceSubsystemConfiguration configuration;
		private Capture capture;
		private boolean closed;

		private Entry(O owner, VoiceSubsystemConfiguration configuration) {
			this.owner = owner;
			this.configuration = Objects.requireNonNull(configuration, "voice configuration must not be null");
		}

		private synchronized void accept(O currentOwner, MicrophonePacketEvent event) {
			if (closed) return;
			if (owner == null) owner = currentOwner;
			if (currentOwner != null && owner != currentOwner) return;
			if (capture == null) {
				capture = Objects.requireNonNull(factory.create(configuration), "capture factory returned null");
			}
			capture.accept(event);
		}

		private synchronized boolean ownedBy(O candidate) {
			return owner == candidate;
		}

		private synchronized void close() {
			if (closed) return;
			closed = true;
			if (capture != null) capture.close();
		}
	}
}
