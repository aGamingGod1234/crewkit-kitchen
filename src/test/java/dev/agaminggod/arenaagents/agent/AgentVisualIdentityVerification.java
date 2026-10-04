package dev.agaminggod.arenaagents.agent;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Catalog additions must preserve brand presentation and known historical transport codes. */
public final class AgentVisualIdentityVerification {
	private AgentVisualIdentityVerification() {}

	public static int verify() {
		int checks = 0;
		var id = new AgentId(UUID.fromString("193a9add-1234-5678-9abc-123456789abc"));
		for (int variant = 0; variant < 4; variant++) {
			var profile = new AgentProfile("gemini", "gemini-3.7-flash", "high", Optional.empty(), variant);
			var actual = profile.visualIdentity();
			equal("flash", actual.modelFamilyKey(), "configured Flash belongs to its family");
			equal("g1" + variant, actual.transportCode(), "Flash transport follows selected variant");
			equal(AgentVisualIdentity.resolve("gemini", "gemini-3.6-flash", variant), actual, "existing Flash model is unchanged");
			equal(actual, AgentVisualIdentity.resolve(" GEMINI ", " GEMINI-3.7-FLASH ", variant), "slug normalization retains family");
			equal("oss", AgentVisualIdentity.resolve("gemini", "unknown-regression-model", variant).modelFamilyKey(), "unknown slugs retain fallback");
			var historical = AgentVisualIdentity.resolveTransportCode("g3" + variant).orElseThrow();
			equal("oss", historical.modelFamilyKey(), "historical transport code remains recognized");
			equal(AgentVisualIdentity.renderTexturePath(historical), AgentVisualIdentity.renderTexturePath(actual), "company brand rendering remains identical");
			equal("Gemini 3.7 Flash", AgentIdentity.displayNameTag(profile), "model label remains exact");
			equal("Gemini_3_7_Flash", AgentIdentity.playerName(id, profile), "technical name remains stable");
			equal("gemini-3.7-flash", profile.model(), "provider model slug remains unchanged");
			checks += 10;
		}
		return checks;
	}

	private static void equal(Object expected, Object actual, String label) {
		if (!Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}
}
