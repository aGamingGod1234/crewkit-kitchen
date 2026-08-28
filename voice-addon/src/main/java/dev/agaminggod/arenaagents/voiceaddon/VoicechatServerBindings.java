package dev.agaminggod.arenaagents.voiceaddon;

import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import dev.agaminggod.arenaagents.server.voice.VoiceSubsystemConfiguration;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/** Pairs each configured Minecraft server with one voice-chat lifecycle registration. */
final class VoicechatServerBindings<S, O> {
	private final Map<S, Association> associations = new WeakHashMap<>();
	private final ServerSpeechCaptureRegistry<S, O> captures;
	private Registration pending;
	private long generation;

	VoicechatServerBindings(ServerSpeechCaptureRegistry.CaptureFactory captureFactory) {
		captures = new ServerSpeechCaptureRegistry<>(captureFactory);
	}

	synchronized void started(O owner) {
		Objects.requireNonNull(owner, "voice-chat API must not be null");
		if (pending != null && pending.live && pending.owner == owner) return;
		for (Association association : associations.values()) {
			if (association.registration.live && association.registration.owner == owner) return;
		}
		if (pending != null) pending.live = false;
		pending = new Registration(owner, ++generation);
	}

	synchronized Binding<O> configure(S server, VoiceSubsystemConfiguration configuration) {
		Objects.requireNonNull(server, "server must not be null");
		Objects.requireNonNull(configuration, "voice configuration must not be null");
		Association association = associations.get(server);
		if (association == null) {
			if (pending == null || !pending.live) {
				throw new IllegalStateException("Simple Voice Chat has no active server registration");
			}
			association = new Association(pending);
			associations.put(server, association);
			pending = null;
		} else if (!association.registration.live) {
			if (pending == null || !pending.live) {
				throw new IllegalStateException("Simple Voice Chat has no active server registration");
			}
			if (pending.generation <= association.stoppedAtGeneration) {
				throw new IllegalStateException("Simple Voice Chat registration predates this server stop");
			}
			association = new Association(pending);
			associations.put(server, association);
			pending = null;
		}

		captures.configure(server, association.registration.owner, configuration);
		association.revision++;
		association.configured = true;
		return new ConfiguredBinding(server, association.registration, association.revision);
	}

	void accept(S server, O owner, MicrophonePacketEvent event) {
		synchronized (this) {
			Association association = associations.get(server);
			if (association == null || !association.configured || !association.registration.live
					|| association.registration.owner != owner) return;
		}
		captures.accept(server, owner, event);
	}

	synchronized void stopped(O owner) {
		if (pending != null && pending.owner == owner) {
			pending.live = false;
			pending = null;
		}
		for (Association association : associations.values()) {
			if (association.registration.owner == owner) {
				association.registration.live = false;
				association.stoppedAtGeneration = generation;
			}
		}
		captures.clearOwner(owner);
	}

	private synchronized boolean active(S server, Registration registration, long revision) {
		Association association = associations.get(server);
		return association != null
				&& association.registration == registration
				&& association.configured
				&& association.revision == revision
				&& registration.live;
	}

	private synchronized void clear(S server, Registration registration, long revision) {
		Association association = associations.get(server);
		if (association == null || association.registration != registration
				|| !association.configured || association.revision != revision) return;
		captures.clear(server);
		association.configured = false;
	}

	interface Binding<O> extends AutoCloseable {
		O owner();

		boolean active();

		@Override
		void close();
	}

	private final class ConfiguredBinding implements Binding<O> {
		private final S server;
		private final Registration registration;
		private final long revision;

		private ConfiguredBinding(S server, Registration registration, long revision) {
			this.server = server;
			this.registration = registration;
			this.revision = revision;
		}

		@Override
		public O owner() {
			return registration.owner;
		}

		@Override
		public boolean active() {
			return VoicechatServerBindings.this.active(server, registration, revision);
		}

		@Override
		public void close() {
			VoicechatServerBindings.this.clear(server, registration, revision);
		}
	}

	private final class Association {
		private final Registration registration;
		private long revision;
		private long stoppedAtGeneration;
		private boolean configured;

		private Association(Registration registration) {
			this.registration = registration;
		}
	}

	private final class Registration {
		private final O owner;
		private final long generation;
		private boolean live = true;

		private Registration(O owner, long generation) {
			this.owner = owner;
			this.generation = generation;
		}
	}
}
