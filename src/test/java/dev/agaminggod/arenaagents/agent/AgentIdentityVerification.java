package dev.agaminggod.arenaagents.agent;

import java.util.Optional;
import java.util.UUID;

public final class AgentIdentityVerification {
	private AgentIdentityVerification() {
	}

	public static int verify() {
		AgentId id = new AgentId(UUID.fromString("193a9add-1234-5678-9abc-123456789abc"));
		AgentProfile sol = new AgentProfile("codex", "gpt-5.6-sol", "high", "fast", Optional.empty(), 2,
				AgentGameMode.SURVIVAL);
		assertEquals("Sol High | Emerald", AgentIdentity.displayName(sol),
				"default identity names the model, effort, and visual variant");
		assertEquals("SolEmer_193A9ADD", AgentIdentity.playerName(id, sol),
				"fake-player username is readable, unique, and within Minecraft's limit");
		assertEquals(new AgentIdentity.SkinIdentity("codex", 2),
				AgentIdentity.skinForPlayerName("SolEmer_193A9ADD").orElseThrow(),
				"offline player names expose the intended skin before the first client snapshot");
		assertTrue(AgentIdentity.skinForPlayerName("ordinary_player").isEmpty(),
				"ordinary player names are not mistaken for arena identities");
		AgentProfile kimiCoding = new AgentProfile(
				"kimi", "kimi-for-coding", "high", "priority", Optional.empty(), 0, AgentGameMode.SURVIVAL);
		String kimiPlayerName = AgentIdentity.playerName(id, kimiCoding);
		assertEquals(new AgentIdentity.SkinIdentity("kimi", 0),
				AgentIdentity.skinForPlayerName(kimiPlayerName).orElseThrow(),
				"truncated Kimi Coding player identities still resolve their custom skin");
		AgentProfile named = new AgentProfile("kimi", "kimi-code/k3", "low", "priority", Optional.of("Dune Scout"), 0,
				AgentGameMode.SURVIVAL);
		assertEquals("Dune Scout", AgentIdentity.displayName(named), "explicit names remain authoritative");
		assertEquals("Kimi K3 Low | Moon", named.nameTag(), "profile fallback name is provider and skin aware");
		return 7;
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label + ": expected true");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}
}
