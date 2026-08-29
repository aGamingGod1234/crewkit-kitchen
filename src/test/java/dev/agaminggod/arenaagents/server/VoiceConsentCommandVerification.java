package dev.agaminggod.arenaagents.server;

public final class VoiceConsentCommandVerification {
	private VoiceConsentCommandVerification() {
	}

	public static int verify() {
		assertEquals(
				"Agent voice transcription enabled for this session.",
				CodexAgentCommands.voiceConsentConfirmation(true),
				"voice consent confirmation states its connection-scoped lifetime"
		);
		assertEquals(
				"Agent voice transcription disabled.",
				CodexAgentCommands.voiceConsentConfirmation(false),
				"voice consent revocation remains explicit"
		);
		return 2;
	}

	private static void assertEquals(String expected, String actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}
}
