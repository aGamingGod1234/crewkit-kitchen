package dev.agaminggod.arenaagents.server.voice;

/** Standalone contract check: no game, provider, or logging backend is required. */
public final class VoiceProfileVerification {
	public static void main(String[] args) {
		for (String tone : new String[] { "neutral", "warm", "excited", "serious", "dramatic", "whisper", "robotic", "angry" }) {
			if (!VoiceProfile.requireSupportedTone(tone).equals(tone)) throw new AssertionError(tone);
		}
		if (!VoiceProfile.requireSupportedTone(" WaRm ").equals("warm")) throw new AssertionError("normalization");
		try {
			VoiceProfile.requireSupportedTone("happy");
			throw new AssertionError("new unsupported tone accepted");
		} catch (IllegalArgumentException expected) { }
		// The persistence codec invokes this constructor, so previously valid saved tokens stay readable.
		if (!new VoiceProfile("voice.laura.v1", "HaPpY", 1, 48).tone().equals("happy")) throw new AssertionError("legacy record");
		if (!VoiceProfile.defaults().tone().equals("neutral")) throw new AssertionError("default");
		System.out.println("Voice profile authoring vocabulary, normalization, and legacy loading: passed");
	}
}
