package dev.agaminggod.arenaagents.server.voice;

/** Focused checks for bounded voice profiles and deterministic cue timelines. */
public final class VoiceDirectorVerification {
	private VoiceDirectorVerification() {
	}

	public static int verify() {
		VoiceProfile profile = new VoiceProfile("voice.claude.v1", "dramatic", 1.25D, 64);
		assertEquals("voice.claude.v1", profile.profileId(), "profile id is retained");
		assertEquals("dramatic", profile.tone(), "tone is retained");
		VoiceCue inherited = new VoiceCue(20, "I have a plan.");
		VoiceCue override = new VoiceCue(40, "Now!", "voice.claude.v1", "excited", 1.5D, 32);
		VoiceScript script = new VoiceScript("intro", "Claude", java.util.List.of())
				.append(inherited).append(override);
		assertEquals(2, script.cues().size(), "cues append in order");
		assertThrows(() -> new VoiceProfile("voice", "neutral", 2.1D, 48), "profile speed is bounded");
		assertThrows(() -> new VoiceCue(0, "line", "", "", 0.2D, 0), "cue speed is bounded");
		assertThrows(() -> new VoiceCue(0, "line", "", "", -1D, 129), "cue radius is bounded");
		assertEquals("voice.laura.v1", VoiceCatalog.defaultFor("GPT 6-Astra", "codex"), "Astra character voice");
		assertEquals("voice.adrian.v1", VoiceCatalog.defaultFor("Fable 5.1", "claude"), "Fable character voice");
		assertEquals("voice.jordan.v1", VoiceCatalog.defaultFor("Grok 4.6", "cursor"), "Grok character voice");
		assertEquals("voice.ethan.v1", VoiceCatalog.defaultFor("Gemini 3.1-Pro", "gemini"), "Gemini character voice");
		assertEquals("voice.sarah.v1", VoiceCatalog.defaultFor("Kimi K3", "kimi"), "Kimi character voice");
		assertEquals(true, VoiceCatalog.accepts("voice.moss.v1"), "legacy profiles remain usable");
		assertEquals(false, VoiceCatalog.selectableIds().contains("voice.moss.v1"), "new selection excludes legacy aliases");
		VoiceCue preview = VoiceDirector.lineCue("Hello, exactly as typed!", new VoiceProfile("voice.selene.v1", "excited", 1.25, 64), "GPT 6-Astra", "codex");
		assertEquals("voice.selene.v1", preview.profileId(), "preview uses selected dropdown voice");
		assertEquals("excited", preview.tone(), "preview captures delivery tone");
		assertEquals(1.25D, preview.speed(), "preview captures speed");
		assertEquals(64, preview.radius(), "preview captures radius");
		assertEquals("Hello, exactly as typed!", preview.text(), "preview preserves line");
		assertEquals("voice.laura.v1", VoiceDirector.lineCue("Hi", VoiceProfile.defaults(), "GPT 6-Astra", "codex").profileId(), "preview auto resolves character voice");
		return 20 + DirectorSpeechVerification.verify();
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
			throw new AssertionError(label + ": expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}
