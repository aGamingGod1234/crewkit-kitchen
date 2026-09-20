package dev.agaminggod.arenaagents.server.voice;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Advances on the server thread; speech workers only complete their receipt future. */
final class DirectorSpeechPlayback {
	private static final int TIMEOUT_TICKS = 20 * 180;
	private final List<VoiceCue> cues;
	private int index;
	private long nextTick;
	private long startedTick;
	private CompletableFuture<VoiceReceipt> pending;
	private VoiceReceipt result;

	DirectorSpeechPlayback(List<VoiceCue> cues, long now) {
		this.cues = List.copyOf(cues);
		if (cues.isEmpty()) throw new IllegalArgumentException("Voice script has no cues");
		nextTick = now + cues.getFirst().delayTicks();
	}

	boolean tick(long now, Function<VoiceCue, CompletionStage<VoiceReceipt>> speak) {
		if (result != null) return true;
		if (pending != null) {
			if (!pending.isDone() && now - startedTick < TIMEOUT_TICKS) return false;
			VoiceReceipt receipt;
			try {
				receipt = pending.isDone() ? pending.join() : failed("Speech timed out. Check the voice provider and try again");
			} catch (RuntimeException exception) {
				receipt = failed("Speech failed. Check the voice provider and try again");
			}
			pending = null;
			if (receipt == null || receipt.status() != VoiceReceipt.Status.PLAYED) {
				result = receipt == null || receipt.status() == VoiceReceipt.Status.ACCEPTED ? failed("Speech returned no playback completion. Check the voice add-on") : receipt;
				return true;
			}
			if (++index == cues.size()) { result = receipt; return true; }
			nextTick = now + cues.get(index).delayTicks();
		}
		if (now < nextTick) return false;
		try {
			startedTick = now;
			pending = speak.apply(cues.get(index)).toCompletableFuture();
		} catch (RuntimeException exception) {
			result = failed("Speech could not start. Check the voice provider and try again");
			return true;
		}
		return false;
	}

	VoiceReceipt result() { return result; }
	String status() { return "Line " + (index + 1) + "/" + cues.size() + (pending == null ? " · Waiting" : " · Speaking"); }
	private static VoiceReceipt failed(String message) { return new VoiceReceipt(VoiceReceipt.Status.FAILED, message); }
}
