package dev.agaminggod.arenaagents.client.control;

import java.lang.management.ManagementFactory;

/** Restore desktop detection before this graphical client first touches AWT or Swing. */
public final class DesktopAwtInitialization {
	private DesktopAwtInitialization() { }

	public static void initialize() {
		// Minecraft Main forces this property to true, even when its launcher requested false.
		// Keep an explicit JVM setting for headless tests; otherwise let the JDK detect DISPLAY/platform.
		String requested = null;
		String prefix = "-Djava.awt.headless=";
		for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
			if (argument.startsWith(prefix)) requested = argument.substring(prefix.length());
		}
		if (requested == null) System.clearProperty("java.awt.headless");
		else System.setProperty("java.awt.headless", requested);
	}
}
