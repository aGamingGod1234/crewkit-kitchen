package dev.agaminggod.arenaagents.server;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.agaminggod.arenaagents.client.gui.SkitDirectorScreen;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Headless checks for director commands, timeline execution and placement. */
public final class SkitModeVerification {
	private SkitModeVerification() {
	}

	public static int verify() {
		SkitPlacement pose = new SkitPlacement("minecraft:overworld", 1.5D, 70.0D, -2.0D, 180.0F, -20.0F);
		SkitScript script = new SkitScript("takeoff", "ChatGPT", java.util.List.of())
				.append(new SkitStep(0, pose))
				.append(new SkitStep(20, new SkitPlacement("minecraft:overworld", 4.5D, 75.0D, -2.0D, 180.0F, -10.0F)));
		assertEquals(2, script.steps().size(), "timeline append preserves order");
		assertThrows(() -> new SkitPlacement("minecraft:overworld", 0, 0, 0, 0, 91), "pitch is bounded");
		assertThrows(() -> new SkitStep(-1, pose), "negative delays are rejected");
		assertThrows(() -> new SkitScript("bad name", "agent", java.util.List.of()), "script names are command-safe");
		assertEquals(2, new SkitStep(0, pose, java.util.List.of(SkitAction.move(20), SkitAction.swing())).actions().size(),
				"action steps preserve order");
		assertThrows(() -> SkitAction.move(0), "move requires a duration");
		assertThrows(() -> SkitAction.equip("diamond_sword"), "equip requires a namespaced item id");
		return 7 + SkitPresentationVerification.verify() + verifyExactActorLabels() + DirectorEditorVerification.verify() + DirectorTakeVerification.verify() + verifyNamesAndPlacement() + verifyTimeline() + verifyPreflight() + verifyCleanup() + verifyGuiCommands() + verifyDirectorParsing() + verifyNameReservations() + verifyCastPersistence() + verifyPendingConversion();
	}

	private static int verifyExactActorLabels() {
		var id = dev.agaminggod.arenaagents.agent.AgentId.random();
		for (String name : List.of("GPT 6-Astra", "Fable 5.1", "Grok_4.6", "Gemini 3.1-Pro", "Kimi K3")) {
			var actor = new SkitActor(id, name, "codex", false);
			assertEquals(name, dev.agaminggod.arenaagents.agent.AgentIdentity.displayNameTag(actor.profile()), "actor display preserves requested punctuation");
			assertEquals(dev.agaminggod.arenaagents.agent.AgentIdentity.canonicalPublicName(name), dev.agaminggod.arenaagents.agent.AgentIdentity.playerName(id, actor.profile()), "technical identity remains stable");
		}
		return 10;
	}

	private static int verifyPendingConversion() {
		try {
			var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
			var manager = (CodexAgentManager) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(CodexAgentManager.class);
			var pending = new java.util.HashMap<dev.agaminggod.arenaagents.agent.AgentId, Long>();
			var respawns = new java.util.HashMap<dev.agaminggod.arenaagents.agent.AgentId, Object>();
			var cancelled = new PendingSpawnCancellationLedger(30_000);
			for (var entry : java.util.Map.of("pendingPlayerSpawns", pending, "pendingVerifiedRespawns", respawns, "cancelledPlayerSpawns", cancelled).entrySet()) {
				var field = CodexAgentManager.class.getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(manager, entry.getValue());
			}
			var id = dev.agaminggod.arenaagents.agent.AgentId.random();
			manager.requireStableDirectorTransfer(id);
			pending.put(id, 1L);
			assertThrows(() -> manager.requireStableDirectorTransfer(id), "in-flight normal spawn cannot transfer ownership");
			pending.clear(); respawns.put(id, null);
			assertThrows(() -> manager.requireStableDirectorTransfer(id), "in-flight normal respawn cannot transfer ownership");
			respawns.clear();
			cancelled.record(id, new SkitActor(id, "TransferProof", "codex", false).profile(), System.currentTimeMillis());
			assertThrows(() -> manager.requireStableDirectorTransfer(id), "old cancellation cannot kill an adopted cast body");
			return 4;
		} catch (Exception exception) { throw new AssertionError("Pending actor conversion verification failed", exception); }
	}

	@SuppressWarnings("unchecked")
	private static int verifyCastPersistence() {
		try {
			var field = SkitModeSavedData.class.getDeclaredField("CODEC"); field.setAccessible(true);
			var codec = (com.mojang.serialization.Codec<SkitModeSavedData>) field.get(null);
			var legacy = codec.parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString("{\"enabled\":true}")).getOrThrow();
			assertTrue(legacy.actors().isEmpty(), "old saves do not silently convert ordinary agents");
			var id = dev.agaminggod.arenaagents.agent.AgentId.random();
			var actor = new SkitActor(id, "Stage Actor", "claude", true);
			legacy.putActor(actor);
			var placement = new SkitPlacement("minecraft:overworld", 12, 70, 19, 40, 10);
			legacy.putPlacement(id.toString(), placement);
			var restored = codec.parse(com.mojang.serialization.JsonOps.INSTANCE, codec.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, legacy).getOrThrow()).getOrThrow();
			assertEquals(actor, restored.actors().getFirst(), "dead cast identity persists without an AgentRecord");
			assertEquals(placement, restored.placement(id.toString()), "manual respawn retains saved stage position");
			assertEquals(dev.agaminggod.arenaagents.agent.AgentIdentity.playerName(id, actor.profile()), dev.agaminggod.arenaagents.agent.AgentIdentity.playerName(id, actor.withDead(false).profile()), "death and respawn preserve physical identity");
			for (String appearance : List.of("codex", "claude", "gemini")) new SkitActor(id, "Stage Actor", appearance, false).profile();
			assertThrows(() -> restored.putActor(new SkitActor(dev.agaminggod.arenaagents.agent.AgentId.random(), "stage actor", "codex", false)), "dead actors reserve cast names case-insensitively");
			var packet = new dev.agaminggod.arenaagents.control.DirectorSnapshotPayload(true, true, List.of(new dev.agaminggod.arenaagents.control.DirectorSnapshotPayload.Actor(id.toString(), actor.name(), "Stage_Actor", "claude", true, false)));
			var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
			try {
				dev.agaminggod.arenaagents.control.DirectorSnapshotPayload.CODEC.encode(buf, packet);
				assertEquals(packet, dev.agaminggod.arenaagents.control.DirectorSnapshotPayload.CODEC.decode(buf), "Director packet retains authoritative toggle and dead cast status");
			} finally { buf.release(); }
			return 11;
		} catch (Exception exception) { throw new AssertionError("Cast persistence verification failed", exception); }
	}

	@SuppressWarnings("unchecked")
	private static int verifyNameReservations() {
		try {
			var world = java.nio.file.Files.createTempDirectory("arena-name-reservation");
			var calls = new java.util.concurrent.atomic.AtomicInteger();
			var repository = (com.mojang.authlib.GameProfileRepository) java.lang.reflect.Proxy.newProxyInstance(
					com.mojang.authlib.GameProfileRepository.class.getClassLoader(), new Class<?>[]{com.mojang.authlib.GameProfileRepository.class},
					(proxy, method, args) -> { calls.incrementAndGet(); return java.util.Optional.empty(); });
			var cache = new net.minecraft.server.players.CachedUserNameToIdResolver(repository, world.resolve("usercache.json").toFile());
			var field = cache.getClass().getDeclaredField("profilesByName");
			field.setAccessible(true);
			var names = (java.util.Map<String, ?>) field.get(cache);
			assertTrue(!AgentPlayerNameReservations.isReserved(world, (java.util.UUID) null, "Fresh_Agent"), "unused name is available without lookup");
			assertEquals(0, calls.get(), "reservation performs no repository calls");
			assertTrue(cache.get("Fresh_Agent").isPresent(), "vanilla resolving getter manufactures offline identity, reproducing original bug");
			assertEquals(1, calls.get(), "vanilla resolving getter performs remote lookup");
			var humanId = java.util.UUID.randomUUID();
			cache.add(new net.minecraft.server.players.NameAndId(humanId, "Human_Name"));
			assertTrue(!AgentPlayerNameReservations.isReserved(world, humanId, "HUMAN_NAME"), "a cached player from another world does not reserve this world");
			var humanData = world.resolve(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR.id()).resolve(humanId + ".dat");
			java.nio.file.Files.createDirectories(humanData.getParent());
			java.nio.file.Files.writeString(humanData, "owned-online-player");
			assertTrue(AgentPlayerNameReservations.isReserved(world, humanId, "HUMAN_NAME"), "online player data in this world reserves the cached name");
			var worldNames = new WorldPlayerNames();
			worldNames.remember("Human_Name", humanId);
			var restoredNames = WorldPlayerNames.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
					WorldPlayerNames.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, worldNames).getOrThrow()).getOrThrow();
			assertTrue(restoredNames.contains("HUMAN_NAME"), "world name cache persists case-insensitive ownership");
			assertTrue(!new WorldPlayerNames().contains("Human_Name"), "fresh world has an independent name cache");
			cache.add(new net.minecraft.server.players.NameAndId(java.util.UUID.randomUUID(), "human_name"));
			assertTrue(restoredNames.contains("Human_Name"), "another world's shared lookup cannot erase recorded ownership");
			var otherWorld = java.nio.file.Files.createTempDirectory("arena-other-world");
			assertTrue(!AgentPlayerNameReservations.isReserved(otherWorld, humanId, "HUMAN_NAME"), "one world cannot reserve another world's actor names");
			assertTrue(!AgentPlayerNameReservations.isReserved(world, dev.agaminggod.arenaagents.agent.AgentIdentity.offlinePlayerUuid("Fresh_Agent"), "Fresh_Agent"), "manufactured cache entries alone never reserve actor names");
			assertTrue(!AgentPlayerNameReservations.isReserved(world, (java.util.UUID) null, "Fresh_Agent2"), "next unused candidate remains available after old cache pollution");
			for (String relative : List.of(
					net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR.id() + "/%s.dat",
					net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR.id() + "/%s.dat_old",
					net.minecraft.world.level.storage.LevelResource.PLAYER_OLD_DATA_DIR.id() + "/%s.dat",
					net.minecraft.world.level.storage.LevelResource.PLAYER_OLD_DATA_DIR.id() + "/%s.dat_old",
					net.minecraft.world.level.storage.LevelResource.PLAYER_STATS_DIR.id() + "/%s.json",
					net.minecraft.world.level.storage.LevelResource.PLAYER_ADVANCEMENTS_DIR.id() + "/%s.json",
					"playerdata/%s.dat", "playerdata/%s.dat_old", "stats/%s.json", "advancements/%s.json")) {
				String name = "Saved" + relative.hashCode();
				var path = world.resolve(relative.formatted(dev.agaminggod.arenaagents.agent.AgentIdentity.offlinePlayerUuid(name)));
				java.nio.file.Files.createDirectories(path.getParent());
				java.nio.file.Files.writeString(path, "corrupt-but-owned");
				assertTrue(AgentPlayerNameReservations.isReserved(world, (java.util.UUID) null, name), "existing saved artifacts remain reserved even when corrupt: " + relative);
			}
			assertEquals(1, calls.get(), "all production reservation checks add zero remote lookups");
			return 23;
		} catch (Exception exception) {
			throw new AssertionError("Local player reservation verification failed", exception);
		}
	}

	@SuppressWarnings("unchecked")
	private static int verifyDirectorParsing() {
		try {
			Method method = CodexAgentCommands.class.getDeclaredMethod("skitCommands");
			method.setAccessible(true);
			var tree = (com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack>) method.invoke(null);
			// Only bypass authority evaluation; use every production argument and executable node.
			tree.requires(source -> true);
			var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
			dispatcher.register(net.minecraft.commands.Commands.literal("codex").then(tree));
			int checks = 0;
			for (String command : List.of("codex skit", "codex skit on", "codex skit off", "codex skit status")) {
				assertTrue(gui("commandError", new Class<?>[]{com.mojang.brigadier.ParseResults.class}, dispatcher.parse(command, null)) == null,
						"mode command has an executable handler: " + command);
				checks++;
			}
			for (String command : List.of("codex skit summon codex model gpt-5.6-luna ",
					"codex skit summon codex model gpt-5.6-luna", "codex skit place ",
					"codex skit script stop ", "codex skit voice say Alex ")) {
				assertTrue(gui("commandError", new Class<?>[]{com.mojang.brigadier.ParseResults.class}, dispatcher.parse(command, null)) != null,
						"incomplete Director command is rejected before sending: " + command);
				checks++;
			}
			for (String model : List.of("gpt-6-luna", "gemini-3.1-pro", "claude-sonnet-5-5")) {
				for (String name : List.of("Alex", "GPT 5.6-Sol", "演员 Lucas", "Alex \"The Builder\"")) {
					String command = "codex skit summon codex model " + StringArgumentType.escapeIfRequired(model) + " " + StringArgumentType.escapeIfRequired(name);
					var parsed = dispatcher.parse(command, null);
					assertTrue(gui("commandError", new Class<?>[]{com.mojang.brigadier.ParseResults.class}, parsed) == null,
							"complete model/name command passes the real parser");
					assertEquals(name, parsed.getContext().build(command).getArgument("name", String.class), "actor name survives transport quoting");
					checks += 2;
				}
			}
			return checks;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Production Director command tree verification failed", exception);
		}
	}

	private static int verifyNamesAndPlacement() {
		for (String selector : List.of("GPT 5.6-Sol", "演员 Lucas", "Alex \"The Builder\"", "@e")) {
			assertEquals(selector, new SkitScript("intro", selector, List.of()).agentSelector(), "actor selectors preserve display names");
		}
		assertThrows(() -> new SkitScript("intro", "Alex\nstop", List.of()), "selectors reject control characters");
		assertThrows(() -> new SkitScript("intro", "Alex", Arrays.asList((SkitStep) null)), "null steps are validation errors");
		SkitPlacement origin = new SkitPlacement("minecraft:overworld", 0, 64, 0, 0, 0);
		assertThrows(() -> new SkitStep(0, origin, Arrays.asList((SkitAction) null)), "null actions are validation errors");
		assertThrows(() -> new SkitPlacement("bad dimension", 0, 64, 0, 0, 0), "dimension identifiers are validated");
		SkitPlacement south = SkitPlacement.relativeTo(origin, 2, 3, 4);
		assertEquals(-2, south.x(), "right when facing south is west");
		assertEquals(67, south.y(), "relative up retains world height");
		assertEquals(4, south.z(), "forward when facing south is south");
		SkitPlacement west = SkitPlacement.relativeTo(new SkitPlacement("minecraft:overworld", 0, 64, 0, 90, 0), 2, 0, 4);
		assertEquals(-4, west.x(), "forward when facing west is west");
		assertEquals(-2, west.z(), "right when facing west is north");
		return 13;
	}

	private static int verifyTimeline() {
		SkitPlacement origin = new SkitPlacement("minecraft:overworld", 0, 64, 0, 350, 0);
		SkitPlacement endpoint = new SkitPlacement("minecraft:overworld", 8, 66, 4, 10, 20);
		FakePerformer actor = new FakePerformer(origin);
		List<SkitStep> steps = List.of(new SkitStep(2, origin, List.of(SkitAction.waitTicks(4))),
				new SkitStep(0, endpoint, List.of(SkitAction.waitTicks(4), SkitAction.move(4), SkitAction.swing())));
		// Keep the second step's initial WAIT at the origin, then aim MOVE at an explicit endpoint.
		List<SkitStep> chained = List.of(new SkitStep(2, endpoint, List.of(SkitAction.move(4), SkitAction.move(4), SkitAction.swing())));
		SkitModeRuntime.Playback run = SkitModeRuntime.Playback.waiting(chained, 10);
		run = SkitModeRuntime.advancePlayback(run, 11, actor);
		assertEquals(0, actor.starts.size(), "delayed scripts do not start early");
		run = SkitModeRuntime.advancePlayback(run, 15, actor);
		assertEquals(15, run.actionStartTick(), "late dispatch starts its clock when the action actually runs");
		assertEquals(origin, actor.position(), "movement begins at the actor's actual pose");
		for (long tick = 16; tick <= 18; tick++) run = SkitModeRuntime.advancePlayback(run, tick, actor);
		assertEquals(6, actor.position().x(), "movement has not reached the endpoint before its duration");
		run = SkitModeRuntime.advancePlayback(run, 19, actor);
		assertEquals(endpoint, actor.position(), "the first move reaches its exact endpoint");
		assertEquals(List.of("MOVE@0", "MOVE@0"), actor.starts, "the next action starts exactly once on the boundary");
		assertEquals(19, run.actionStartTick(), "chained actions do not overlap or repeat their initial tick");
		assertEquals(1, actor.stops.size(), "only the completed move has released controls");
		// Simulate a new physical origin before another chained MOVE to expose truncated interpolation.
		FakePerformer second = new FakePerformer(origin);
		SkitModeRuntime.Playback moveAfterWait = SkitModeRuntime.Playback.waiting(
				List.of(new SkitStep(0, endpoint, List.of(SkitAction.waitTicks(2), SkitAction.move(4)))), 0);
		moveAfterWait = SkitModeRuntime.advancePlayback(moveAfterWait, 0, second);
		second.pose = origin;
		for (long tick = 1; tick <= 5; tick++) moveAfterWait = SkitModeRuntime.advancePlayback(moveAfterWait, tick, second);
		assertEquals(6, second.position().x(), "chained move has 75 percent progress one tick before completion");
		assertTrue(moveAfterWait != null, "chained move is still reserved before its endpoint");
		moveAfterWait = SkitModeRuntime.advancePlayback(moveAfterWait, 6, second);
		assertEquals(endpoint, second.position(), "chained move reaches 100 percent before releasing the actor");
		assertTrue(moveAfterWait == null, "finished script releases its timeline");
		for (long tick = 20; tick <= 23; tick++) run = SkitModeRuntime.advancePlayback(run, tick, actor);
		assertEquals(List.of("MOVE@0", "MOVE@0", "SWING@0"), actor.starts, "one-shot effects fire only once");
		assertTrue(run != null, "one-tick effects remain active until the next server tick");
		run = SkitModeRuntime.advancePlayback(run, 24, actor);
		assertTrue(run == null, "one-tick effect finishes without an extra idle tick");
		assertEquals(3, actor.stops.size(), "each action releases exactly once");
		FakePerformer jumper = new FakePerformer(origin);
		SkitModeRuntime.Playback jump = SkitModeRuntime.Playback.waiting(List.of(new SkitStep(0, origin, List.of(SkitAction.jump()))), 30);
		jump = SkitModeRuntime.advancePlayback(jump, 30, jumper);
		assertEquals(0, jumper.stops.size(), "jump is held until a player tick can consume it");
		jump = SkitModeRuntime.advancePlayback(jump, 31, jumper);
		assertTrue(jump == null && jumper.stops.size() == 1, "jump releases after one full server tick");
		FakePerformer delayed = new FakePerformer(origin);
		SkitModeRuntime.Playback delays = SkitModeRuntime.Playback.waiting(steps, 0);
		delays = SkitModeRuntime.advancePlayback(delays, 2, delayed);
		delays = SkitModeRuntime.advancePlayback(delays, 6, delayed);
		assertEquals(1, delays.index(), "zero-delay steps advance on the completion boundary");
		assertEquals(6, delays.actionStartTick(), "next step does not lose a server tick");
		assertThrows(() -> SkitModeRuntime.interpolate(origin, new SkitPlacement("minecraft:the_nether", 0, 64, 0, 0, 0), .5F),
				"cross-dimension movement cannot blend unrelated coordinates");
		assertThrows(() -> SkitModeRuntime.interpolate(origin, endpoint, Float.NaN), "invalid movement progress is rejected");
		assertEquals(360, SkitModeRuntime.interpolate(origin, endpoint, .5F).yaw(), "actor rotation takes the shortest turn");
		FakePerformer waiting = new FakePerformer(origin);
		SkitModeRuntime.advancePlayback(SkitModeRuntime.Playback.waiting(List.of(new SkitStep(0, endpoint, List.of(SkitAction.waitTicks(20)), false)), 0), 0, waiting);
		assertEquals(origin, waiting.position(), "new wait actions hold the current pose instead of snapping to their captured endpoint");
		return 24;
	}

	private static int verifyPreflight() {
		SkitPlacement overworld = new SkitPlacement("minecraft:overworld", 0, 64, 0, 0, 0);
		SkitPlacement nether = new SkitPlacement("minecraft:the_nether", 0, 64, 0, 0, 0);
		assertThrows(() -> SkitModeRuntime.validateTimeline(new SkitScript("intro", "Alex", List.of(new SkitStep(0, nether))),
				"minecraft:overworld", dimension -> dimension.equals("minecraft:overworld"), item -> true), "missing dimensions fail before reservation");
		assertThrows(() -> SkitModeRuntime.validateTimeline(new SkitScript("intro", "Alex", List.of(new SkitStep(0, overworld, List.of(SkitAction.equip("minecraft:not_an_item"))))),
				"minecraft:overworld", dimension -> true, item -> false), "unknown items fail before any timeline effect");
		assertThrows(() -> SkitModeRuntime.validateTimeline(new SkitScript("intro", "Alex", List.of(new SkitStep(0, nether, List.of(SkitAction.move(20))))),
				"minecraft:overworld", dimension -> true, item -> true), "movement across dimensions requires an explicit placement");
		SkitModeRuntime.validateTimeline(new SkitScript("intro", "Alex", List.of(new SkitStep(0, nether), new SkitStep(0, nether, List.of(SkitAction.move(20))))),
				"minecraft:overworld", dimension -> true, item -> true);
		return 4;
	}

	private static int verifyCleanup() {
		List<String> cleanup = new ArrayList<>();
		SkitModeRuntime.cleanup(() -> cleanup.add("input"), () -> cleanup.add("use"));
		assertEquals(List.of("input", "use"), cleanup, "cancellation clears input and held item use");
		cleanup.clear();
		SkitModeRuntime.cleanup(() -> { throw new IllegalStateException("expected test cleanup failure"); }, () -> cleanup.add("use"));
		assertEquals(List.of("use"), cleanup, "item use is released even when input cleanup fails");
		return 2;
	}

	private static int verifyGuiCommands() {
		assertEquals("codex skit script stop Alex", gui("scriptCommand", new Class<?>[]{String.class, String.class, String.class}, "stop", "intro", "Alex"),
				"Stop actor sends only the selector");
		assertEquals("codex skit script play intro Alex", gui("scriptCommand", new Class<?>[]{String.class, String.class, String.class}, "play", "intro", "Alex"),
				"Play script honors the GUI actor override");
		assertEquals("codex skit script play intro", gui("scriptCommand", new Class<?>[]{String.class, String.class, String.class}, "play", "intro", ""),
				"empty playback override preserves the script actor");
		String text = "He said \"Go!\" C:\\casts, café.";
		for (String prefix : List.of("codex skit voice say Alex", "codex skit voice script add dialogue 0")) {
			String command = (String) gui("lineCommand", new Class<?>[]{String.class, String.class}, prefix, text);
			try {
				StringReader reader = new StringReader(command);
				reader.setCursor(prefix.length() + 1);
				assertEquals(text, StringArgumentType.greedyString().parse(reader), "raw speech reaches Brigadier without added quoting or escaping");
			} catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
				throw new AssertionError("GUI speech must parse", exception);
			}
		}
		assertEquals(5, gui("maxScrollRows", new Class<?>[]{int.class, int.class}, 220, 7), "all voice rows remain reachable on a 240-pixel-high GUI");
		assertEquals(0, gui("maxScrollRows", new Class<?>[]{int.class, int.class}, 390, 7), "full-height director needs no scrolling");

		return 8;
	}

	private static Object gui(String name, Class<?>[] parameters, Object... arguments) {
		try {
			Method method = SkitDirectorScreen.class.getDeclaredMethod(name, parameters);
			method.setAccessible(true);
			return method.invoke(null, arguments);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Director command verification failed", exception);
		}
	}

	private static final class FakePerformer implements SkitModeRuntime.Performer {
		private SkitPlacement pose;
		private final List<String> starts = new ArrayList<>();
		private final List<SkitAction.Type> stops = new ArrayList<>();
		private FakePerformer(SkitPlacement pose) { this.pose = pose; }
		@Override public SkitPlacement position() { return pose; }
		@Override public void place(SkitPlacement placement) { pose = placement; }
		@Override public void perform(SkitStep step, SkitAction action, SkitPlacement origin, long elapsed, boolean firstTick) {
			if (firstTick) starts.add(action.type() + "@" + elapsed);
			if (action.type() == SkitAction.Type.MOVE) {
				pose = SkitModeRuntime.interpolate(origin, step.placement(), Math.min(1.0F, (float) elapsed / action.durationTicks()));
			}
		}
		@Override public void stop(SkitAction action) { stops.add(action.type()); }
	}

	private static void assertThrows(Runnable action, String label) {
		try {
			action.run();
			throw new AssertionError(label + ": expected IllegalArgumentException");
		} catch (IllegalArgumentException expected) {
		}
	}

	private static void assertEquals(double expected, double actual, String label) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.000001D) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}

	private static void assertTrue(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
