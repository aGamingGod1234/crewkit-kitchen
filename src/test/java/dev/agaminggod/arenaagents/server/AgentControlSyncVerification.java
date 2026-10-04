package dev.agaminggod.arenaagents.server;

import dev.agaminggod.arenaagents.agent.AgentEntityLocation;
import dev.agaminggod.arenaagents.agent.AgentRegistry;
import dev.agaminggod.arenaagents.control.AgentControlActions;
import dev.agaminggod.arenaagents.control.AgentControlCatalog;
import dev.agaminggod.arenaagents.control.AgentControlGroup;
import dev.agaminggod.arenaagents.control.AgentControlSnapshotPayload;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioBuildProgress;
import dev.agaminggod.arenaagents.scenario.presentation.ScenarioPresentationExpiry;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerPlayer;

public final class AgentControlSyncVerification {
	private AgentControlSyncVerification() {
	}

	public static void main(String[] args) {
		var out = System.out;
		var err = System.err;
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		System.setOut(out);
		System.setErr(err);
		System.out.println("PASS: " + verify() + " control sync assertions");
		System.out.println("PASS: " + AgentRecoverySpawnPolicyVerification.verify() + " recovery policy assertions");
	}

	public static int verify() {
		AtomicInteger actions = new AtomicInteger();
		assertTrue(!AgentControlSync.executeAuthorizedControlAction(false, actions::incrementAndGet),
				"non-operator control request is rejected");
		assertEquals(0, actions.get(), "non-operator rejection happens before snapshot or action work");
		assertTrue(AgentControlSync.executeAuthorizedControlAction(true, actions::incrementAndGet),
				"operator control request is accepted");
		assertEquals(1, actions.get(), "operator snapshot or action work runs exactly once");

		AtomicInteger denials = new AtomicInteger();
		assertTrue(!AgentControlSync.executeAuthorizedControlAction(
				false, actions::incrementAndGet, denials::incrementAndGet),
				"denied mutation returns an explicit rejection result");
		assertEquals(1, denials.get(), "denied mutation publishes one rejection");
		assertTrue(AgentControlSync.executeAuthorizedControlAction(
				true, actions::incrementAndGet, denials::incrementAndGet),
				"authorized mutation does not use its rejection path");
		assertEquals(1, denials.get(), "authorized mutation leaves the rejection count unchanged");
		assertEquals(2, actions.get(), "authorized mutation still runs exactly once");

		AgentControlSync.SpectatorPublication publication = new AgentControlSync.SpectatorPublication();
		UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
		ScenarioBuildProgress rejection = ScenarioBuildProgress.rejected(
				"rejected-a", "The Last Valley", 0, 70, 0, "Launch rejected");
		publication.rememberDirectBuild(playerId, rejection, 100L);
		assertEquals(rejection, publication.directBuild(playerId, 100L).orElseThrow(),
				"direct launch rejection is retained after publication");
		assertEquals(rejection, publication.directBuild(
				playerId, 100L + ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS - 1L).orElseThrow(),
				"direct launch rejection remains visible until the terminal TTL boundary");
		assertTrue(publication.directBuild(
				playerId, 100L + ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS).isEmpty(),
				"direct launch rejection expires exactly at the terminal TTL boundary");
		publication.rememberDirectBuild(playerId, rejection, 500L);
		assertTrue(publication.directBuild(
				playerId, 500L + ScenarioPresentationExpiry.BUILD_TERMINAL_TTL_TICKS - 1L).isPresent(),
				"a repeated direct rejection starts a fresh terminal TTL");
		publication.forgetBuild(playerId);
		assertTrue(publication.directBuild(playerId, 500L).isEmpty(),
				"build tombstone cleanup removes the direct rejection clock and payload");
		return 14 + verifyObservedPresence();
	}

	private static int verifyObservedPresence() {
		AgentRegistry registry = AgentRegistry.createDefault(() -> { }, ignored -> { });
		var active = registry.create("gpt-6.1-sol", "low", Optional.empty(), 100L);
		var idle = registry.create("gpt-6.1-sol", "low", Optional.empty(), 101L);
		var location = AgentEntityLocation.exact("minecraft:the_nether", 7, -3, 115, 42, -40, 90, 5);
		UUID attachment = UUID.randomUUID();
		registry.attachEntity(active.agentId(), attachment, location, 102L);
		registry.attachEntity(idle.agentId(), UUID.randomUUID(), location, 102L);
		registry.start(active.agentId(), "continue mining", 103L);
		CodexAgentManager.prepareMissingPlayer(registry, registry.require(active.agentId()), true,
				new java.util.LinkedHashSet<>(), 104L);
		var groups = List.of(new AgentControlGroup("Recovery", List.of(active.agentId().toString(), idle.agentId().toString())));
		var catalog = AgentControlCatalog.currentOptions();
		var absent = AgentControlSync.controlSnapshot(true, true, "Automation ready", 105L,
				registry.records(), groups, catalog, ignored -> Optional.empty());
		int assertions = 0;
		for (var control : absent.agents()) {
			assertTrue(!control.entityPresent(), "absent active and idle agents have no live body");
			assertTrue(!AgentControlActions.supports(control, "start"), "absent body disables Start");
			assertTrue(!AgentControlActions.supports(control, "resume"), "absent body disables Resume");
			assertions += 3;
		}
		assertEquals(groups, absent.groups(), "presence projection retains saved groups");
		assertEquals(catalog, absent.catalog(), "presence projection retains the runtime model catalog");
		assertEquals(absent, new AgentControlSnapshotPayload(
				AgentControlSnapshotPayload.fromSnapshot(absent).encodedSnapshot()).snapshot(),
				"missing-body control flag survives the actual payload codec");
		assertEquals(Optional.of(attachment), registry.require(active.agentId()).entityUuid(), "projection retains the exact UUID");
		assertEquals(Optional.of(location), registry.require(active.agentId()).entityLocation(), "projection retains exact recovery coordinates");
		assertions += 5;
		FixturePlayer body = fixturePlayer();
		for (boolean alive : List.of(false, true)) {
			body.alive = alive;
			var snapshot = AgentControlSync.controlSnapshot(true, true, "Automation ready", 106L,
					registry.records(), groups, catalog, ignored -> Optional.of(body));
			for (var control : snapshot.agents()) {
				assertEquals(alive, control.entityPresent(), "only an observed living replacement projects present");
				assertEquals(alive, AgentControlActions.supports(control, "start"), "living body restores Start eligibility");
				assertEquals(alive && control.agentId().equals(active.agentId().toString()),
						AgentControlActions.supports(control, "resume"), "Resume also requires the retained goal");
				assertions += 3;
			}
		}
		// Detached negative control: removing the attachment also loses the location, so it is not the fix.
		registry.detachEntity(active.agentId(), 107L);
		var detached = AgentControlSync.controlSnapshot(true, true, "Automation ready", 108L,
				List.of(registry.require(active.agentId())), groups, catalog, ignored -> Optional.empty()).agents().getFirst();
		assertTrue(!detached.entityPresent(), "detached absent body remains unavailable");
		assertTrue(!AgentControlActions.supports(detached, "start"), "detached negative control disables Start");
		assertTrue(!AgentControlActions.supports(detached, "resume"), "detached negative control disables Resume");
		assertEquals(Optional.empty(), registry.require(active.agentId()).entityLocation(), "detachment loses durable recovery coordinates");
		assertions += 4;
		return assertions;
	}

	private static FixturePlayer fixturePlayer() {
		try {
			Class<?> type = Class.forName("sun.misc.Unsafe");
			var field = type.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return (FixturePlayer) type.getMethod("allocateInstance", Class.class).invoke(field.get(null), FixturePlayer.class);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Could not allocate headless presence fixture", exception);
		}
	}

	private static final class FixturePlayer extends ServerPlayer {
		private boolean alive;
		private FixturePlayer() { super(null, null, null, null); }
		@Override public boolean isAlive() { return alive; }
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertEquals(int expected, int actual, String label) {
		if (expected != actual) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) {
			throw new AssertionError(label + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}
}
