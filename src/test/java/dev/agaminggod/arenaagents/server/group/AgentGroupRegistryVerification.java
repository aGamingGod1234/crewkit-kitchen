package dev.agaminggod.arenaagents.server.group;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import dev.agaminggod.arenaagents.agent.AgentId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class AgentGroupRegistryVerification {
	private static final AgentId FLINT = new AgentId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final AgentId MOSS = new AgentId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

	private AgentGroupRegistryVerification() {
	}

	public static int verify() {
		AtomicInteger changes = new AtomicInteger();
		AgentGroupRegistry registry = new AgentGroupRegistry(changes::incrementAndGet);
		AgentGroup builders = registry.save("  Builders  ", List.of(FLINT, MOSS));

		assertEquals("Builders", builders.name(), "group name is normalized");
		assertEquals(List.of(FLINT, MOSS), builders.memberIds(), "group preserves persistent identity order");
		assertEquals(builders, registry.require("builders"), "group lookup is case-insensitive");
		assertEquals(1, changes.get(), "saving a group marks persistent state dirty once");

		String encoded = AgentGroupSnapshotCodec.encode(registry.snapshot());
		AgentGroupRegistry restored = AgentGroupRegistry.restore(
				AgentGroupSnapshotCodec.decode(encoded),
				changes::incrementAndGet
		);
		assertEquals(List.of(builders), restored.groups(), "saved group survives snapshot round trip");
		expectDomain(
				() -> AgentGroupSnapshotCodec.decode("🙂".repeat(9_000)),
				"GROUP_SNAPSHOT_TOO_LARGE",
				"saved-group storage limit counts UTF-8 bytes"
		);

		AgentGroup revised = restored.save("BUILDERS", List.of(MOSS));
		assertEquals(List.of(MOSS), revised.memberIds(), "saving an existing name updates the same group");
		assertEquals(1, restored.groups().size(), "case variants cannot create duplicate groups");
		expectDomain(
				() -> restored.save("Duplicate", List.of(FLINT, FLINT)),
				"DUPLICATE_GROUP_MEMBER",
				"group rejects duplicate identities"
		);
		assertEquals(revised, restored.delete("builders"), "group deletion is case-insensitive");
		assertEquals(List.of(), restored.groups(), "deleted group leaves no saved roster");

		AgentGroupSpawnCoordinator.Result spawn = AgentGroupSpawnCoordinator.spawn(builders, agentId -> {
			if (agentId.equals(FLINT)) return AgentGroupSpawnCoordinator.MemberStatus.PRESENT;
			return AgentGroupSpawnCoordinator.MemberStatus.RESTORING;
		});
		assertEquals(2, spawn.total(), "group spawn visits every saved identity once");
		assertEquals(1, spawn.present(), "group spawn leaves an active member untouched");
		assertEquals(1, spawn.restoring(), "group spawn restores an absent member");
		assertEquals(List.of(), spawn.missingIds(), "group spawn reports no missing identities");

		AgentGroupSpawnCoordinator.Result missing = AgentGroupSpawnCoordinator.spawn(
				builders,
				agentId -> agentId.equals(FLINT)
						? AgentGroupSpawnCoordinator.MemberStatus.PRESENT
						: AgentGroupSpawnCoordinator.MemberStatus.MISSING
		);
		assertEquals(List.of(MOSS), missing.missingIds(), "deleted identities remain visibly missing");

		AgentGroupSavedData savedData = new AgentGroupSavedData();
		savedData.registry().save("Party", List.of(FLINT, MOSS));
		assertEquals("Party", savedData.registry().require("party").name(),
				"world saved data owns the persistent group registry");
		return 17;
	}

	private static void expectDomain(Runnable action, String code, String label) {
		try {
			action.run();
			throw new AssertionError(label + " should fail");
		} catch (AgentDomainException exception) {
			assertEquals(code, exception.code(), label + " code");
		}
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
		}
	}
}
