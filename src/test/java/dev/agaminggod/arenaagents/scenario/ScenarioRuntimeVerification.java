package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.*;
import dev.agaminggod.arenaagents.agent.goal.GoalEvidence;
import dev.agaminggod.arenaagents.scenario.result.*;
import dev.agaminggod.arenaagents.scenario.runtime.*;
import dev.agaminggod.arenaagents.server.AgentSavedData;
import dev.agaminggod.arenaagents.server.CodexAgentManager;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Isolated runtime regressions. Fixtures never start a server, world, transport or provider. */
public final class ScenarioRuntimeVerification {
	private static final Class<?> SERVICE = ScenarioRuntimeService.class;
	private static final Class<?> STATE = nested("RuntimeState");
	private static int assertions;

	public static int verify() throws Exception {
		int before = assertions;
		java.io.PrintStream consoleOut = System.out;
		java.io.PrintStream consoleErr = System.err;
		Path directory = Files.createTempDirectory("arena-scenario-runtime-");
		try {
			net.minecraft.SharedConstants.tryDetectVersion();
			net.minecraft.server.Bootstrap.bootStrap();
			recovery();
			launch();
			journal(directory);
			rollback(directory);
			hash();
			return assertions - before;
		} finally {
			System.setOut(consoleOut);
			System.setErr(consoleErr);
			Files.delete(directory);
		}
	}

	public static void main(String[] args) throws Exception {
		java.io.PrintStream consoleOut = System.out;
		java.io.PrintStream consoleErr = System.err;
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.setOut(consoleOut);
		System.setErr(consoleErr);
		switch (args[0]) {
			case "recovery" -> recovery();
			case "launch" -> launch();
			case "journal" -> journal(Path.of(args[1]));
			case "rollback" -> rollback(Path.of(args[1]));
			case "hash" -> hash();
			default -> throw new IllegalArgumentException("unknown regression");
		}
		System.err.println("PASS " + args[0] + " assertions=" + assertions);
	}

	private static void recovery() {
		AgentRegistry original = new AgentRegistry(2, 1, () -> {}, ignored -> {});
		AgentRecord a = original.create("gpt-6.1-sol", "low", Optional.of("Completed"), 10);
		AgentRecord b = original.create("gpt-6.1-sol", "low", Optional.of("Working"), 11);
		original.start(a.agentId(), "Collect stone", 20);
		original.start(b.agentId(), "Collect stone", 21);
		original.satisfyGoal(a.agentId(), original.require(a.agentId()).goalRevision(),
				new GoalEvidence(1, "FIXTURE", List.of(new GoalEvidence.Fact("inventory_contains", true, "stone", "stone"))), 30);
		AgentRecord completed = original.require(a.agentId());
		AgentRegistrySnapshotCodec codec = new AgentRegistrySnapshotCodec();
		AgentRegistry restored = AgentRegistry.restore(codec.decode(codec.encode(original.snapshot())), () -> {}, ignored -> {}, 40);
		for (ScenarioCategory category : List.of(ScenarioCategory.SURVIVAL, ScenarioCategory.BUILDING)) {
			check(ScenarioCompletionPolicy.finishReason(category, restored.records().stream().map(record ->
					new ScenarioCompletionPolicy.ParticipantState(record.agentId().toString(), Optional.empty(), record.state(), true))
					.toList(), false).isEmpty(), "mixed roster is legitimately unfinished");
		}
		check(ScenarioRecoveryGate.evaluate(true, statuses(restored)).ready(), "mixed completed/starting roster recovers");
		restored.stop(b.agentId(), 50);
		check(ScenarioRecoveryGate.evaluate(true, statuses(restored)).resumeAgentIds().equals(List.of(b.agentId().toString())),
				"only unfinished paused contestant resumes");
		restored.resume(b.agentId(), 60);
		check(ScenarioRecoveryGate.evaluate(true, statuses(restored)).ready(), "resumed mixed roster recovers");
		check(restored.require(a.agentId()).state() == AgentLifecycleState.COMPLETED
				&& restored.require(a.agentId()).currentGoal().equals(completed.currentGoal())
				&& restored.require(a.agentId()).goalRevision() == completed.goalRevision(), "completed goal and evidence never restarted");
		check(!ScenarioRecoveryGate.evaluate(false, statuses(restored)).ready(), "coordinator still required");
		for (AgentLifecycleState rejected : List.of(AgentLifecycleState.DEAD, AgentLifecycleState.ERROR)) {
			check(!ScenarioRecoveryGate.evaluate(true, List.of(new ScenarioRecoveryGate.AgentStatus("a", rejected, true))).ready(),
					"other terminal states remain rejected");
		}
		check(!ScenarioRecoveryGate.evaluate(true, List.of(new ScenarioRecoveryGate.AgentStatus("a", AgentLifecycleState.COMPLETED, false))).ready(),
				"completed contestant still requires player readiness");
		check(ScenarioRecoveryGate.evaluate(true, List.of(new ScenarioRecoveryGate.AgentStatus("a", AgentLifecycleState.COMPLETED, true))).ready(),
				"all-completed roster can reach normal scenario completion");
	}

	private static List<ScenarioRecoveryGate.AgentStatus> statuses(AgentRegistry registry) {
		return registry.records().stream().map(record -> new ScenarioRecoveryGate.AgentStatus(record.agentId().toString(), record.state(), true)).toList();
	}

	private static void launch() throws Exception {
		Object state = state();
		Object owner = construct(nested("RecoveryJob"), null, null, null, null, 0L);
		set(state, "recovery", owner);
		Map<Object, Object> states = map(SERVICE, "STATES");
		FakePlayer player = allocate(FakePlayer.class);
		player.fixtureLevel = allocate(FakeLevel.class);
		check(dev.agaminggod.arenaagents.server.GoalControl.mayControl(player.createCommandSourceStack()), "fixture has launch authority");
		states.put(null, state);
		try {
			for (String token : List.of("", "matching-confirmation")) {
				try {
					ScenarioRuntimeService.launch(player, request(token));
					throw new AssertionError("launch was admitted during recovery");
				} catch (IllegalStateException expected) {
					check(expected.getMessage().equals("A scenario is already running"), "preview and confirmation reject before manager/site work");
				}
				check(field(STATE, "recovery").get(state) == owner, "recovery owner unchanged");
				check(field(STATE, "preparationSnapshot").get(state) == null
						&& field(STATE, "pendingConfirmation").get(state) == null, "no new durable or confirmation owner");
			}
			set(state, "recovery", null);
			set(state, "cancelCleanupIds", List.of("still-owned"));
			try { ScenarioRuntimeService.launch(player, request("")); throw new AssertionError("cleanup admitted launch"); }
			catch (IllegalStateException expected) { check(expected.getMessage().equals("A scenario is already running"), "cleanup also retains ownership"); }
		} finally { states.remove(null); }
	}

	private static void journal(Path directory) throws Exception {
		Path fixture = Files.createTempDirectory(directory, "journal-").toAbsolutePath().normalize();
		Path target = fixture.resolve("runtime/scenario-preparation.json");
		Path blocker = target.resolve("blocker");
		Object state = state();
		ScenarioPreparationJournal journal = new ScenarioPreparationJournal(fixture);
		var owner = owner(List.of());
		set(state, "preparationJournal", journal);
		set(state, "preparationSnapshot", owner);
		set(state, "restoreAttempted", true);
		Map<Object, Object> states = map(SERVICE, "STATES");
		states.put(null, state);
		try {
			Files.createDirectories(target);
			Files.writeString(blocker, "owned obstruction");
			invoke("clearPreparationJournal", new Class<?>[]{STATE}, state);
			for (int i = 0; i < 3; i++) ScenarioRuntimeService.tick(null);
			check(field(STATE, "preparationSnapshot").get(state) == owner, "failed deletion retains exact owner");
			Files.delete(blocker); Files.delete(target);
			journal.write(owner);
			check(ScenarioRuntimeService.restorePersistedState(null), "already restored path remains available");
			ScenarioRuntimeService.tick(null);
			check(!Files.exists(target) && field(STATE, "preparationSnapshot").get(state) == null,
					"ordinary tick clears journal after transient storage failure");
			check(field(STATE, "preparationJournal").get(state) == null, "successful retry releases both owners");
			// No clear was requested for this operation: ticks must leave its durable owner alone.
			set(state, "preparationJournal", journal); set(state, "preparationSnapshot", owner); journal.write(owner);
			ScenarioRuntimeService.tick(null);
			check(journal.load().orElseThrow().equals(owner), "live preparation without pending cleanup is preserved");
			Files.delete(target); Files.createDirectory(target); Files.writeString(blocker, "second obstruction");
			invoke("clearPreparationJournal", new Class<?>[]{STATE}, state);
			Files.delete(blocker); Files.delete(target);
			var newer = owner(List.of("new-agent"));
			set(state, "preparationSnapshot", newer); journal.write(newer);
			ScenarioRuntimeService.tick(null);
			check(journal.load().orElseThrow().equals(newer) && field(STATE, "preparationSnapshot").get(state) == newer,
					"stale clear cannot release a changed owner");
		} finally {
			states.remove(null); Files.deleteIfExists(blocker); Files.deleteIfExists(target);
			Files.deleteIfExists(fixture.resolve("runtime")); Files.delete(fixture);
		}
	}

	private static void rollback(Path directory) throws Exception {
		Path fixture = Files.createTempDirectory(directory, "rollback-");
		Path target = fixture.resolve("runtime/scenario-preparation.json");
		ScenarioPreparationJournal journal = new ScenarioPreparationJournal(fixture);
		Object state = state();
		var owner = owner(List.of());
		set(state, "preparationJournal", journal); set(state, "preparationSnapshot", owner);
		try {
			journal.write(owner);
			FileTime sentinel = FileTime.fromMillis(1_000L); Files.setLastModifiedTime(target, sentinel);
			for (int i = 0; i < 40; i++) invoke("resetPreparationAgents", new Class<?>[]{STATE}, state);
			check(Files.getLastModifiedTime(target).equals(sentinel), "empty rollback does not rewrite durable journal");
			var populated = owner(List.of("owned-agent")); journal.write(populated); set(state, "preparationSnapshot", populated);
			invoke("resetPreparationAgents", new Class<?>[]{STATE}, state);
			check(journal.load().orElseThrow().agentIds().isEmpty(), "real partial roster rollback persists empty ledger");
			check(journal.load().orElseThrow().sessionId().equals(populated.sessionId()), "rollback preserves session owner");
			set(state, "preparationSnapshot", populated);
			Files.delete(target); Files.createDirectory(target); Files.writeString(target.resolve("blocker"), "owned");
			try { invoke("resetPreparationAgents", new Class<?>[]{STATE}, state); throw new AssertionError("write must fail"); }
			catch (InvocationTargetException expected) { check(expected.getCause() instanceof java.io.IOException, "storage failure remains visible"); }
			check(field(STATE, "preparationSnapshot").get(state) == populated, "failed durable rollback retains memory ledger");
		} finally {
			Files.deleteIfExists(target.resolve("blocker")); Files.deleteIfExists(target);
			Files.deleteIfExists(fixture.resolve("runtime")); Files.delete(fixture);
		}
	}

	private static void hash() throws Exception {
		MinecraftServer server = allocate(DedicatedServer.class);
		CodexAgentManager manager = allocate(CodexAgentManager.class);
		AgentSavedData saved = new AgentSavedData();
		field(CodexAgentManager.class, "savedData").set(manager, saved);
		AgentRecord agent = saved.registry().create("gpt-6.1-sol", "low", Optional.of("Public"), 10);
		Map<Object, Object> managers = map(CodexAgentManager.class, "INSTANCES");
		Map<Object, Object> states = map(SERVICE, "STATES");
		Object state = state();
		var snapshot = snapshot(agent.agentId().toString());
		Provider provider = new Provider("ScenarioHashCounter", "1", "Counts fixture hashes") {};
		provider.put("MessageDigest.SHA-256", CountingSha.class.getName());
		Security.insertProviderAt(provider, 1);
		managers.put(server, manager); states.put(server, state);
		try {
			MatchResultV1 result = (MatchResultV1) invoke("resultFromSnapshot", new Class<?>[]{ScenarioRunSnapshot.class}, snapshot);
			ScenarioResultPersistence persistence = new ScenarioResultPersistence(result, ignored -> new CompletableFuture<>());
			Class<?> pendingType = nested("PendingResult");
			Constructor<?> ctor = pendingType.getDeclaredConstructors()[0]; ctor.setAccessible(true);
			Object pending = ctor.getParameterCount() == 2 ? ctor.newInstance(snapshot, persistence) : ctor.newInstance(snapshot, result, persistence);
			set(state, "pendingResult", pending);
			int before = CountingSha.count(result.canonicalSha256());
			var first = ScenarioRuntimeService.spectatorView(server).orElseThrow();
			check(CountingSha.count(result.canonicalSha256()) == before, "first terminal projection reuses existing hash");
			saved.registry().start(agent.agentId(), "new visible state", 20);
			before = CountingSha.count(result.canonicalSha256());
			var second = ScenarioRuntimeService.spectatorView(server).orElseThrow();
			check(CountingSha.count(result.canonicalSha256()) == before, "real spectator entrypoint never reconstructs pending canonical hash");
			check(first.resultHash().equals(result.canonicalSha256()) && second.resultHash().equals(first.resultHash()), "full result hash remains identical");
			check(!first.participants().getFirst().status().equals(second.participants().getFirst().status()), "participant status remains live across projections");
			check(snapshot.publicEvents().size() == 4096, "full canonical event history retained");
		} finally { Security.removeProvider(provider.getName()); states.remove(server); managers.remove(server); }
	}

	public static final class CountingSha extends MessageDigestSpi {
		private static final Map<String, Integer> digests = new HashMap<>();
		static int count(String hash) { return digests.getOrDefault(hash, 0); }
		private final MessageDigest delegate;
		public CountingSha() { try { delegate = MessageDigest.getInstance("SHA-256", "SUN"); } catch (GeneralSecurityException e) { throw new AssertionError(e); } }
		protected void engineUpdate(byte value) { delegate.update(value); }
		protected void engineUpdate(byte[] value, int offset, int length) { delegate.update(value, offset, length); }
		protected byte[] engineDigest() { byte[] hash = delegate.digest(); digests.merge(HexFormat.of().formatHex(hash), 1, Integer::sum); return hash; }
		protected void engineReset() { delegate.reset(); }
	}

	private static ScenarioRunSnapshot snapshot(String agentId) {
		var events = new ArrayList<ScenarioPublicEvent>();
		for (int i = 0; i < 4096; i++) events.add(new ScenarioPublicEvent(i, "slot-1", "Public", "action_completed", "movement", 0, "done"));
		return new ScenarioRunSnapshot(2, UUID.randomUUID(), "last-valley", ScenarioPresets.require("last-valley").mapVersion(),
				1, 2, 10000, true, List.of(new ScenarioRunSnapshot.Participant("slot-1", "Public", Optional.empty())),
				1, "minecraft:overworld", UUID.randomUUID(), List.of(agentId), Map.of(), new ScenarioRunSnapshot.Origin(0, 64, 0),
				ScenarioSessionState.FINISHED, Optional.of("done"), 4096, Map.of("slot-1", 1.0),
				ScenarioResetReceipt.verified("same", "same", 1, 1, 1),
				new ScenarioRuntimeClock.Snapshot(1, 4096, 0, Optional.empty(), true), events);
	}

	private static ScenarioLaunchRequest request(String token) {
		return new ScenarioLaunchRequest("last-valley", ScenarioPresets.require("last-valley").mapVersion(), true,
				ScenarioPlacementMode.IN_FRONT_OF_PLAYER, List.of(new ScenarioAgentSpec(1, "One", "codex", "gpt-6.1-sol", "low", "priority", Optional.empty(), AgentGameMode.SURVIVAL)), token);
	}
	private static ScenarioPreparationJournal.Snapshot owner(List<String> ids) {
		return new ScenarioPreparationJournal.Snapshot(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", new BlockPos(0, 64, 0), request(""), 1, 2, 3, ids, false);
	}
	private static Class<?> nested(String name) { try { return Class.forName(SERVICE.getName() + "$" + name); } catch (Exception e) { throw new AssertionError(e); } }
	private static Object state() throws Exception { return construct(STATE); }
	private static Object construct(Class<?> type, Object... values) throws Exception { Constructor<?> ctor = type.getDeclaredConstructors()[0]; ctor.setAccessible(true); return ctor.newInstance(values); }
	private static Field field(Class<?> type, String name) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
	private static void set(Object object, String name, Object value) throws Exception { field(object.getClass(), name).set(object, value); }
	private static Object invoke(String name, Class<?>[] types, Object... values) throws Exception { Method method = SERVICE.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(null, values); }
	@SuppressWarnings("unchecked") private static Map<Object, Object> map(Class<?> type, String name) throws Exception { return (Map<Object, Object>) field(type, name).get(null); }
	private static <T> T allocate(Class<T> type) throws Exception {
		Class<?> unsafeType = Class.forName("sun.misc.Unsafe"); Object unsafe = field(unsafeType, "theUnsafe").get(null);
		return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); assertions++; }

	static final class FakeLevel extends ServerLevel {
		private FakeLevel() { super(null, null, null, null, null, null, false, 0, List.of(), false); }
		@Override public MinecraftServer getServer() { return null; }
	}
	static final class FakePlayer extends ServerPlayer {
		FakeLevel fixtureLevel;
		private FakePlayer() { super(null, null, null, null); }
		@Override public ServerLevel level() { return fixtureLevel; }
		@Override public CommandSourceStack createCommandSourceStack() {
			return new CommandSourceStack(null, Vec3.ZERO, Vec2.ZERO, fixtureLevel, LevelBasedPermissionSet.OWNER, "fixture", Component.literal("fixture"), null, null);
		}
	}
}
