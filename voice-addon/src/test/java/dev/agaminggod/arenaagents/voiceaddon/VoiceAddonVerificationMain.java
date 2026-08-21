package dev.agaminggod.arenaagents.voiceaddon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class VoiceAddonVerificationMain {
	private VoiceAddonVerificationMain() {
	}

	public static void main(String[] arguments) throws Exception {
		int assertions = 0;
		assertions += verifyFabricMetadataLoadsOnIntegratedAndDedicatedServers();
		assertions += VoicePlaybackCoordinatorVerification.verify();
		assertions += SpeechCaptureEngineVerification.verify();
		assertions += VoiceWorkerClientsVerification.verify();
		assertions += NodeVoiceWorkerIntegrationVerification.verify();
		System.out.println("PASS: " + assertions + " voice-addon assertions");
	}

	private static int verifyFabricMetadataLoadsOnIntegratedAndDedicatedServers() {
		try (InputStream stream = VoiceAddonVerificationMain.class.getClassLoader()
				.getResourceAsStream("fabric.mod.json")) {
			if (stream == null) throw new AssertionError("Voice addon metadata is missing");
			JsonObject metadata = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
					.getAsJsonObject();
			if (!"*".equals(metadata.get("environment").getAsString())) {
				throw new AssertionError("Voice addon must load in an integrated server client JVM");
			}
			return 1;
		} catch (java.io.IOException exception) {
			throw new AssertionError("Could not read voice addon metadata", exception);
		}
	}
}
