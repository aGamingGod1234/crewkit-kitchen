package dev.agaminggod.arenaagents.verification;

public final class DirectorVerificationMain {
	public static void main(String[] args) throws Exception {
		var output = System.out;
		try {
			net.minecraft.SharedConstants.tryDetectVersion();
			net.minecraft.server.Bootstrap.bootStrap();
			int assertions = dev.agaminggod.arenaagents.server.SkitModeVerification.verify();
			assertions += dev.agaminggod.arenaagents.server.voice.VoiceDirectorVerification.verify();
			assertions += dev.agaminggod.arenaagents.client.camera.CameraPathVerification.verify();
			assertions += dev.agaminggod.arenaagents.client.gui.SkitDirectorLayoutVerification.verify();
			assertions += dev.agaminggod.arenaagents.server.DirectorCommandsVerification.verify();
			output.println("PASS: " + assertions + " Director assertions");
		} catch (Throwable failure) {
			failure.printStackTrace(output);
			throw failure;
		}
	}
}
