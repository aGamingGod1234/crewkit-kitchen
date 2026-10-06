package dev.agaminggod.arenaagents.pov;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

/**
 * Proves that every POV mixin applies under Fabric by loading each target class through Knot.
 * A mis-targeted injector fails here (defaultRequire is 1) instead of at first game launch.
 * Minecraft's main is never invoked; classes are loaded without initialization.
 */
public final class PovMixinVerification {
	private static final String TARGETS_RESOURCE = "pov-mixin-targets.txt";

	private PovMixinVerification() {
	}

	public static void main(String[] args) throws Exception {
		List<String> targets = readTargets();
		if (targets.isEmpty()) throw new AssertionError("No POV mixin targets are listed in " + TARGETS_RESOURCE);
		ClassLoader target = new Knot(EnvType.CLIENT).init(args);
		List<String> failures = new ArrayList<>();
		for (String name : targets) {
			try {
				Class<?> loaded = Class.forName(name, false, target);
				// Touching the declared members forces the transformed class to be fully defined.
				loaded.getDeclaredMethods();
			} catch (Throwable error) {
				failures.add(name + ": " + error);
			}
		}
		if (!failures.isEmpty()) {
			throw new AssertionError("POV mixins failed to apply:\n" + String.join("\n", failures));
		}
		System.out.println("POV mixin verification passed (" + targets.size()
				+ " target classes transformed); Minecraft client main was not invoked.");
	}

	private static List<String> readTargets() throws Exception {
		List<String> targets = new ArrayList<>();
		try (InputStream stream = PovMixinVerification.class.getClassLoader().getResourceAsStream(TARGETS_RESOURCE)) {
			if (stream == null) throw new AssertionError("Missing test resource " + TARGETS_RESOURCE);
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					String trimmed = line.trim();
					if (!trimmed.isEmpty() && !trimmed.startsWith("#")) targets.add(trimmed);
				}
			}
		}
		return targets;
	}
}
