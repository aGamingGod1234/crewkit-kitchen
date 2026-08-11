package dev.agaminggod.arenaagents.scenario;

import dev.agaminggod.arenaagents.agent.AgentGameMode;
import java.util.List;
import java.util.Optional;

public final class ScenarioLaunchRuntimeVerification {
	private ScenarioLaunchRuntimeVerification() {
	}

	public static void main(String[] args) {
		int assertions = verify();
		System.out.printf("PASS: %d scenario launch runtime assertions%n", assertions);
	}

	public static int verify() {
		ScenarioLaunchRequest request = new ScenarioLaunchRequest(
				"citadel-collapse",
				ScenarioPresets.require("citadel-collapse").mapVersion(),
				true,
				List.of(
						new ScenarioAgentSpec(1, "Codex", "codex", "gpt-5.6-sol", "high",
								Optional.of("amber"), AgentGameMode.SURVIVAL),
						new ScenarioAgentSpec(2, "Gemini", "gemini", "gemini-3.1-pro", "high",
								Optional.of("azure"), AgentGameMode.SURVIVAL)
				)
		);
		assertEquals(request, ScenarioLaunchCodec.decode(ScenarioLaunchCodec.encode(request)),
				"scenario launch codec round trip");
		expectIllegalArgument(
				() -> new ScenarioLaunchRequest(
						"thinking-tower",
						ScenarioPresets.require("thinking-tower").mapVersion(),
						true,
						List.of(new ScenarioAgentSpec(
								1,
								"Codex",
								"codex",
								"gpt-5.6-sol",
								"high",
								Optional.empty(),
								AgentGameMode.SURVIVAL
						))
				),
				"locked parkour mode rejects survival"
		);

		return 2;
	}

	private static void expectIllegalArgument(Runnable action, String message) {
		try {
			action.run();
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError(message + " (expected IllegalArgumentException)");
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

}
