package dev.agaminggod.arenaagents.control;

import dev.agaminggod.arenaagents.client.control.DesktopAwtInitialization;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.util.List;

/** Run in a fresh JVM for each launcher setting; never creates a window or toolkit. */
public final class ClientAwtInitializationVerification {
	public static void main(String[] args) throws Exception {
		String mode = args[0];
		if (List.of(args).contains("--minecraft-main")) {
			Class<?> main = Class.forName("net.minecraft.client.main.Main");
			System.out.println("Minecraft Main origin: " + main.getProtectionDomain().getCodeSource().getLocation());
		} else {
			// Matches Minecraft 26.1.2 Main.<clinit>, confirmed from the packaged bytecode.
			System.setProperty("java.awt.headless", "true");
		}
		if (!"true".equals(System.getProperty("java.awt.headless"))) throw new AssertionError("Minecraft forcing scenario was not exercised");
		var source = DesktopAwtInitialization.class.getProtectionDomain().getCodeSource().getLocation().toURI();
		String expected = System.getProperty("arenaagents.expectedLiveWindowsJar");
		if (expected != null && !Path.of(source).toAbsolutePath().normalize().equals(Path.of(expected).toAbsolutePath().normalize())) throw new AssertionError("Unexpected AWT initialization class origin: " + source);
		System.out.println("Desktop AWT initialization origin: " + source);
		DesktopAwtInitialization.initialize();
		String restored = System.getProperty("java.awt.headless");
		if (mode.equals("auto") ? restored != null : !mode.equals(restored)) throw new AssertionError("Launcher setting was not restored: " + restored);
		boolean headless = GraphicsEnvironment.isHeadless();
		if (!mode.equals("auto") && headless != Boolean.parseBoolean(mode)) throw new AssertionError("AWT cached the forced Minecraft flag before client initialization");
		System.out.println("PASS: forced Minecraft flag restored to " + mode + "; GraphicsEnvironment.isHeadless=" + headless + "; no window created");
	}
}
