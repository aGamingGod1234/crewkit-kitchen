package dev.agaminggod.arenaagents.client.pov;

import java.util.Objects;
import java.util.Optional;

/** Dependency-free POV session state machine. The server owns entry and exit; this orders what arrives. */
public final class PovSessionTracker {
	public enum Phase { NONE, ACTIVE, SIGNAL_LOST }

	public enum StateResult { STARTED, SWITCHED, UPDATED, STALE }

	public enum PoseResult { IGNORED, APPLIED, LOOK_RESET }

	private Phase phase = Phase.NONE;
	private PovClientSession session;
	private int revision;
	private int serverLookResetSeq;
	private int appliedLookResetSeq;
	private boolean lookResetPending;
	private boolean hasStoppedSession;
	private long stoppedSessionId;

	public StateResult acceptState(PovClientSession next, int nextRevision, int lookResetSeq) {
		Objects.requireNonNull(next, "next must not be null");
		// A state that was already in flight when its stop arrived must not reopen the view.
		if (hasStoppedSession && next.sessionId() == stoppedSessionId) return StateResult.STALE;
		boolean sameSession = session != null && session.sessionId() == next.sessionId();
		if (sameSession && nextRevision - revision < 0) return StateResult.STALE;
		StateResult result;
		if (session == null) result = StateResult.STARTED;
		else if (!session.equals(next)) result = StateResult.SWITCHED;
		else result = StateResult.UPDATED;
		if (result != StateResult.UPDATED) {
			phase = Phase.ACTIVE;
			lookResetPending = true;
			// Nothing is acknowledged for a new binding until a pose seeds the local look.
			appliedLookResetSeq = lookResetSeq - 1;
		} else if (lookResetSeq != serverLookResetSeq) {
			lookResetPending = true;
		}
		session = next;
		revision = nextRevision;
		serverLookResetSeq = lookResetSeq;
		return result;
	}

	/** Records the stop and reports whether it ends the current session. */
	public boolean acceptStop(long sessionId) {
		hasStoppedSession = true;
		stoppedSessionId = sessionId;
		return matches(sessionId);
	}

	public PoseResult acceptPose(long sessionId) {
		if (!matches(sessionId)) return PoseResult.IGNORED;
		if (!lookResetPending) return PoseResult.APPLIED;
		lookResetPending = false;
		appliedLookResetSeq = serverLookResetSeq;
		return PoseResult.LOOK_RESET;
	}

	public boolean matches(long sessionId) {
		return session != null && session.sessionId() == sessionId;
	}

	public boolean entityMissing() {
		if (phase != Phase.ACTIVE) return false;
		phase = Phase.SIGNAL_LOST;
		return true;
	}

	public boolean entityFound() {
		if (phase != Phase.SIGNAL_LOST) return false;
		phase = Phase.ACTIVE;
		return true;
	}

	public boolean clear() {
		boolean active = session != null;
		phase = Phase.NONE;
		session = null;
		revision = 0;
		serverLookResetSeq = 0;
		appliedLookResetSeq = 0;
		lookResetPending = false;
		return active;
	}

	public Phase phase() {
		return phase;
	}

	public Optional<PovClientSession> session() {
		return Optional.ofNullable(session);
	}

	public int revision() {
		return revision;
	}

	/** The last server look reset that the local predicted look has adopted. */
	public int lookResetSeq() {
		return appliedLookResetSeq;
	}

	public boolean lookResetPending() {
		return lookResetPending;
	}
}
