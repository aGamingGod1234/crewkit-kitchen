package dev.agaminggod.arenaagents.server;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;

/** Focused, dependency-free startup discovery checks with an isolated PATH. */
public final class NodeRuntimeLocatorVerification {
	private NodeRuntimeLocatorVerification() {
	}

	public static int verify() throws Exception {
		Class<?> locator = Class.forName("dev.agaminggod.arenaagents.server.NodeRuntimeLocator");
		Class<?> probeType = Class.forName(locator.getName() + "$Probe");
		Method locate = locator.getDeclaredMethod("locate", Path.class, String.class, String.class, probeType);
		locate.setAccessible(true);

		Path root = Files.createTempDirectory("arena-node-locator");
		try {
			Path bundled = bundledExecutable(root);
			Object probe = Proxy.newProxyInstance(
					probeType.getClassLoader(),
					new Class<?>[]{probeType},
					(proxy, method, args) -> "v22.14.0"
			);
			Object result = locate.invoke(null, root, "", "", probe);
			assertEquals(bundled.toAbsolutePath().normalize(), property(result, "executable"),
					"bundled executable is selected with an empty PATH");
			assertEquals("BUNDLED_PROFILE", property(result, "source").toString(),
					"bundled source is reported");

			Path explicit = root.resolve("explicit-node.exe");
			Files.writeString(explicit, "fixture");
			result = locate.invoke(null, root, explicit.toString(), "", probe);
			assertEquals(explicit.toAbsolutePath().normalize(), property(result, "executable"),
					"explicit executable wins over bundled runtime");

			try {
				locate.invoke(null, root, root.resolve("missing-node.exe").toString(), "", probe);
				throw new AssertionError("invalid explicit executable must fail closed");
			} catch (InvocationTargetException expected) {
				assertEquals("NODE_RUNTIME_EXPLICIT_INVALID", failureCode(expected.getCause()),
						"invalid explicit executable has an actionable code");
			}

			Path pathRoot = Files.createTempDirectory(root, "path-fallback-");
			Path pathNode = pathRoot.resolve("node.exe");
			Files.writeString(pathNode, "fixture");
			result = locate.invoke(null, root.resolve("path-package"), "", pathRoot.toString(), probe);
			assertEquals(pathNode.toAbsolutePath().normalize(), property(result, "executable"),
					"PATH executable is selected only after bundled runtime is absent");
			assertEquals("PATH", property(result, "source").toString(), "PATH source is reported");

			Path invalidBundledRoot = Files.createTempDirectory(root, "invalid-bundled-");
			Path invalidBundled = bundledExecutable(invalidBundledRoot);
			Path fallbackDirectory = Files.createTempDirectory(root, "invalid-bundled-path-");
			Path fallbackNode = fallbackDirectory.resolve("node.exe");
			Files.writeString(fallbackNode, "fixture");
			Object failingBundledProbe = Proxy.newProxyInstance(
					probeType.getClassLoader(),
					new Class<?>[]{probeType},
					(proxy, method, args) -> {
						if (Path.of(args[0].toString()).equals(invalidBundled)) throw new IOException("probe failed");
						return "v22.14.0";
					}
			);
			try {
				locate.invoke(null, invalidBundledRoot, "", fallbackDirectory.toString(), failingBundledProbe);
				throw new AssertionError("invalid bundled runtime must not fall through to PATH");
			} catch (InvocationTargetException expected) {
				assertEquals("NODE_RUNTIME_BUNDLED_INVALID", failureCode(expected.getCause()),
						"invalid bundled runtime has an actionable code");
			}

			Object oldVersionProbe = Proxy.newProxyInstance(
					probeType.getClassLoader(),
					new Class<?>[]{probeType},
					(proxy, method, args) -> "v20.11.1"
			);
			try {
				locate.invoke(null, root, explicit.toString(), "", oldVersionProbe);
				throw new AssertionError("unsupported Node version must fail");
			} catch (InvocationTargetException expected) {
				assertEquals("NODE_RUNTIME_VERSION_UNSUPPORTED", failureCode(expected.getCause()),
						"unsupported Node version has a stable code");
			}
			return 7;
		} finally {
			deleteTree(root);
		}
	}

	private static Path bundledExecutable(Path root) throws Exception {
		Path path = root.resolve("runtime/toolchains/node/node.exe");
		Files.createDirectories(path.getParent());
		Files.writeString(path, "fixture");
		return path;
	}

	private static Object property(Object result, String name) throws Exception {
		return result.getClass().getMethod(name).invoke(result);
	}

	private static String failureCode(Throwable error) throws Exception {
		return (String) error.getClass().getMethod("code").invoke(error);
	}

	private static void deleteTree(Path root) throws Exception {
		if (!Files.exists(root)) return;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
