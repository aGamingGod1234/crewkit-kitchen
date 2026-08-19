package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaAnchor;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaModule;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaModuleCodec;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioArenaModuleLoader;
import dev.agaminggod.arenaagents.scenario.runtime.map.ScenarioModuleHasher;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

public final class ScenarioArenaModuleVerification {
	private static final String RESOURCE_PATH = "fixtures/tiny-owned-room-v1.json";
	private static final String RESOURCE_PREFIX = "data/arenaagents/arena_modules/";
	private static final String GEOMETRY_HASH = "22f97084ac1f7e5eda56cd47a405e29d058996d44450d8da02d55c7310c97cc3";
	private static final String FIXTURE = "{\"allowedTransforms\":[\"identity\",\"rotate_90\",\"rotate_180\",\"rotate_270\"],\"anchors\":[{\"id\":\"spawn-1\",\"position\":[0,1,0],\"type\":\"spawn\"},{\"id\":\"goal-1\",\"position\":[1,1,1],\"type\":\"goal\"}],\"bounds\":{\"max\":[1,1,1],\"min\":[0,0,0]},\"containerPolicy\":\"none\",\"difficulty\":1,\"geometrySha256\":\"" + GEOMETRY_HASH + "\",\"id\":\"tiny-owned-room\",\"palette\":[{\"id\":\"minecraft:oak_planks\",\"properties\":{}},{\"id\":\"minecraft:stone\",\"properties\":{}}],\"placements\":[{\"state\":1,\"x\":0,\"y\":0,\"z\":0},{\"state\":1,\"x\":0,\"y\":0,\"z\":1},{\"state\":1,\"x\":1,\"y\":0,\"z\":0},{\"state\":1,\"x\":1,\"y\":0,\"z\":1},{\"state\":0,\"x\":1,\"y\":1,\"z\":1}],\"schemaVersion\":1,\"sourceKey\":\"project-owned-fixtures\",\"spectatorPolicy\":\"separated\",\"version\":1}";
	private static final String STAIRS_FIXTURE = "{\"allowedTransforms\":[\"identity\"],\"anchors\":[],\"bounds\":{\"max\":[0,0,0],\"min\":[0,0,0]},\"containerPolicy\":\"none\",\"difficulty\":1,\"geometrySha256\":\"76c884eda3b74a03ff1451841f034c2e6a815d626558ff4264ab9a82d7f51cab\",\"id\":\"stairs\",\"palette\":[{\"id\":\"minecraft:oak_stairs\",\"properties\":{\"facing\":\"north\",\"half\":\"bottom\",\"shape\":\"straight\",\"waterlogged\":\"false\"}}],\"placements\":[{\"state\":0,\"x\":0,\"y\":0,\"z\":0}],\"schemaVersion\":1,\"sourceKey\":\"project-owned-fixtures\",\"spectatorPolicy\":\"none\",\"version\":1}";

	private ScenarioArenaModuleVerification() {
	}

	public static int verify() throws IOException {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		verifyFixtureAndHash();
		verifyStrictRootAndNumbers();
		verifyRegistryStateReconstruction();
		verifyPaletteAndPlacements();
		verifyAnchorsTransformsAndPolicies();
		verifyContainedLoader();
		verifyImmutability();
		return 49;
	}

	private static void verifyFixtureAndHash() throws IOException {
		ScenarioArenaModule module = ScenarioArenaModuleLoader.load(RESOURCE_PATH);
		assertEquals("tiny-owned-room", module.id(), "fixture id");
		assertEquals(1, module.version(), "fixture version");
		assertEquals("project-owned-fixtures", module.sourceKey(), "fixture source");
		assertEquals(1, module.difficulty(), "fixture difficulty");
		assertEquals(new BlockPos(0, 0, 0), module.bounds().minimum(), "fixture minimum");
		assertEquals(new BlockPos(1, 1, 1), module.bounds().maximum(), "fixture maximum");
		assertEquals(5, module.placements().size(), "fixture placement count");
		assertEquals(Blocks.STONE, module.placements().getFirst().state().getBlock(), "first decoded block");
		assertEquals(Blocks.OAK_PLANKS, module.placements().getLast().state().getBlock(), "last decoded block");
		assertEquals(List.of("spawn-1", "goal-1"), module.anchors().stream().map(ScenarioArenaAnchor::id).toList(),
				"fixture anchor order");
		assertEquals(GEOMETRY_HASH, module.geometrySha256(), "fixture geometry hash");
		assertEquals(GEOMETRY_HASH, ScenarioModuleHasher.sha256(module.placements()), "Java hash matches Python");
		List<ScenarioArenaModule.Placement> shuffled = new ArrayList<>(module.placements());
		Collections.reverse(shuffled);
		assertEquals(GEOMETRY_HASH, ScenarioModuleHasher.sha256(shuffled), "hash sorts placements numerically");
		assertEquals("minecraft:stone", ScenarioModuleHasher.canonicalState(Blocks.STONE.defaultBlockState()),
				"canonical state without properties");
		try (InputStream stream = ScenarioArenaModuleVerification.class.getClassLoader()
				.getResourceAsStream(RESOURCE_PREFIX + RESOURCE_PATH)) {
			assertTrue(stream != null, "fixture resource exists");
			assertEquals(FIXTURE + "\n", new String(stream.readAllBytes(), StandardCharsets.UTF_8),
					"resource bytes exactly match Task 2 output");
		}
	}

	private static void verifyStrictRootAndNumbers() {
		reject(FIXTURE.replaceFirst("\\}$", ",\"unexpected\":true}"), "unknown root field");
		reject(FIXTURE.replace("\"difficulty\":1,", ""), "missing root field");
		reject(FIXTURE.replace("\"version\":1", "\"version\":1,\"version\":1"), "duplicate JSON field");
		reject(FIXTURE.replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"), "decimal schema version");
		reject(FIXTURE.replace("\"version\":1", "\"version\":true"), "boolean version");
		reject(FIXTURE.replace("\"difficulty\":1", "\"difficulty\":6"), "difficulty range");
		reject(FIXTURE.replace("\"max\":[1,1,1]", "\"max\":[192,1,1]"), "bounds cap");
		reject(FIXTURE.replace("\"min\":[0,0,0]", "\"min\":[1,0,0]"), "bounds must start at zero");
		reject(FIXTURE.replace("\"max\":[1,1,1]", "\"max\":[-1,1,1]"), "inverted bounds");
		reject(FIXTURE.replace("\"id\":\"tiny-owned-room\"", "\"id\":\"Bad ID\""), "module id format");
		reject(FIXTURE.replace("\"sourceKey\":\"project-owned-fixtures\"", "\"sourceKey\":\"../source\""),
				"source key format");
	}

	private static void verifyRegistryStateReconstruction() {
		ScenarioArenaModule stairs = ScenarioArenaModuleCodec.decode(STAIRS_FIXTURE);
		assertEquals(
				"minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
				ScenarioModuleHasher.canonicalState(stairs.placements().getFirst().state()),
				"complete authored properties reconstruct the exact runtime state");
		reject(FIXTURE.replace("minecraft:oak_planks", "minecraft:not_real"), "unknown block id");
		reject(FIXTURE.replace("minecraft:oak_planks", "arenaagents:stone"), "non-Minecraft block id");
		reject(FIXTURE.replace("{\"id\":\"minecraft:oak_planks\",\"properties\":{}}",
				"{\"id\":\"minecraft:oak_stairs\",\"properties\":{\"facing\":\"north\"}}"),
				"missing state properties");
		reject(FIXTURE.replace("{\"id\":\"minecraft:stone\",\"properties\":{}}",
				"{\"id\":\"minecraft:stone\",\"properties\":{\"extra\":\"true\"}}"),
				"extra state property");
		reject(FIXTURE.replace("{\"id\":\"minecraft:oak_planks\",\"properties\":{}}",
				"{\"id\":\"minecraft:oak_stairs\",\"properties\":{\"facing\":\"up\",\"half\":\"bottom\",\"shape\":\"straight\",\"waterlogged\":\"false\"}}"),
				"invalid state property value");
		for (String forbidden : List.of("command_block", "repeating_command_block", "chain_command_block",
				"structure_block", "jigsaw")) {
			reject(FIXTURE.replace("minecraft:oak_planks", "minecraft:" + forbidden), "forbidden control block " + forbidden);
		}
	}

	private static void verifyPaletteAndPlacements() {
		String first = "{\"id\":\"minecraft:oak_planks\",\"properties\":{}}";
		String second = "{\"id\":\"minecraft:stone\",\"properties\":{}}";
		reject(FIXTURE.replace(first + "," + second, second + "," + first), "unsorted palette");
		reject(FIXTURE.replace(first, second), "duplicate palette");
		reject(FIXTURE.replace("{\"state\":1,\"x\":0,\"y\":0,\"z\":0},{\"state\":1,\"x\":0,\"y\":0,\"z\":1}",
				"{\"state\":1,\"x\":0,\"y\":0,\"z\":1},{\"state\":1,\"x\":0,\"y\":0,\"z\":0}"),
				"unsorted placements");
		reject(FIXTURE.replaceFirst("\"state\":1", "\"state\":2"), "bad palette index");
		reject(FIXTURE.replace("{\"state\":1,\"x\":0,\"y\":0,\"z\":1}",
				"{\"state\":1,\"x\":0,\"y\":0,\"z\":0}"), "duplicate placement");
		reject(FIXTURE.replaceFirst("\"x\":0,\"y\":0,\"z\":0", "\"x\":2,\"y\":0,\"z\":0"),
				"out-of-bounds placement");
		reject(FIXTURE.replaceFirst("\"state\":1", "\"state\":1,\"command\":\"say no\""),
				"unrepresentable placement content");
		reject(FIXTURE.replace(GEOMETRY_HASH, "bad"), "malformed hash");
		reject(FIXTURE.replace(GEOMETRY_HASH, "0".repeat(64)), "mismatched hash");
	}

	private static void verifyAnchorsTransformsAndPolicies() {
		reject(FIXTURE.replace("\"id\":\"goal-1\"", "\"id\":\"spawn-1\""), "duplicate anchor id");
		reject(FIXTURE.replace("\"position\":[1,1,1],\"type\":\"goal\"",
				"\"position\":[0,1,0],\"type\":\"goal\""), "duplicate anchor position");
		reject(FIXTURE.replace("\"position\":[1,1,1],\"type\":\"goal\"",
				"\"position\":[2,1,1],\"type\":\"goal\""), "out-of-bounds anchor");
		reject(FIXTURE.replace("\"type\":\"goal\"", "\"type\":\"unknown\""), "unknown anchor type");
		reject(FIXTURE.replace("\"rotate_90\"", "\"warp\""), "unknown transform");
		reject(FIXTURE.replace("\"identity\",\"rotate_90\"", "\"identity\",\"identity\",\"rotate_90\""),
				"duplicate transform");
		reject(FIXTURE.replace("\"identity\",\"rotate_90\"", "\"rotate_90\",\"identity\""),
				"noncanonical transform order");
		reject(FIXTURE.replace("\"containerPolicy\":\"none\"", "\"containerPolicy\":\"all\""),
				"invalid container policy");
		reject(FIXTURE.replace("\"spectatorPolicy\":\"separated\"", "\"spectatorPolicy\":\"nearby\""),
				"invalid spectator policy");
	}

	private static void verifyContainedLoader() {
		for (String path : List.of("../tiny-owned-room-v1.json", "fixtures\\tiny-owned-room-v1.json",
				"/fixtures/tiny-owned-room-v1.json", "fixtures/missing-v1.json")) {
			assertThrows(IllegalArgumentException.class, () -> ScenarioArenaModuleLoader.load(path),
					"loader rejects uncontained or missing path " + path);
		}
		ClassLoader original = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(resourceLoader(
					RESOURCE_PREFIX + "fixtures/mismatch-v2.json", FIXTURE.getBytes(StandardCharsets.UTF_8)));
			assertThrows(IllegalArgumentException.class,
					() -> ScenarioArenaModuleLoader.load("fixtures/mismatch-v2.json"),
					"filename version must match module version");
			Thread.currentThread().setContextClassLoader(resourceLoader(
					RESOURCE_PREFIX + "fixtures/oversized-v1.json", new byte[8 * 1024 * 1024 + 1]));
			assertThrows(IllegalArgumentException.class,
					() -> ScenarioArenaModuleLoader.load("fixtures/oversized-v1.json"),
					"loader rejects oversized resource");
		} finally {
			Thread.currentThread().setContextClassLoader(original);
		}
	}

	private static void verifyImmutability() {
		ScenarioArenaModule module = ScenarioArenaModuleCodec.decode(FIXTURE);
		assertThrows(UnsupportedOperationException.class,
				() -> module.placements().add(module.placements().getFirst()), "placements are immutable");
		assertThrows(UnsupportedOperationException.class,
				() -> module.anchors().add(module.anchors().getFirst()), "anchors are immutable");
		assertThrows(UnsupportedOperationException.class,
				() -> module.allowedTransforms().add(ScenarioArenaModule.AllowedTransform.MIRROR_X),
				"transforms are immutable");
		assertThrows(IllegalArgumentException.class,
				() -> ScenarioModuleHasher.sha256(List.of(
						module.placements().getFirst(), module.placements().getFirst())),
				"hasher rejects duplicate coordinates");
	}

	private static ClassLoader resourceLoader(String resourceName, byte[] content) {
		return new ClassLoader(ScenarioArenaModuleVerification.class.getClassLoader()) {
			@Override
			public InputStream getResourceAsStream(String name) {
				if (resourceName.equals(name)) return new ByteArrayInputStream(content);
				return super.getResourceAsStream(name);
			}
		};
	}

	private static void reject(String encoded, String label) {
		assertThrows(IllegalArgumentException.class, () -> ScenarioArenaModuleCodec.decode(encoded), label);
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}

	private static <T extends Throwable> void assertThrows(Class<T> type, ThrowingRunnable action, String label) {
		try {
			action.run();
		} catch (Throwable error) {
			if (type.isInstance(error)) return;
			throw new AssertionError(label + ": expected " + type.getSimpleName() + ", got " + error, error);
		}
		throw new AssertionError(label + ": expected " + type.getSimpleName());
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
