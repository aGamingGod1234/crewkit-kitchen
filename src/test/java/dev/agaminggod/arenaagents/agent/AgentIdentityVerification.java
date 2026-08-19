package dev.agaminggod.arenaagents.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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

		assertEquals("GPT 5.6 Sol WM", AgentModelNames.displayName("codex", "gpt-5.6-sol-wm"),
				"Codex display name is canonical");
		assertEquals("Sol WM", AgentModelNames.shortLabel("codex", "gpt-5.6-sol-wm"),
				"Codex short label is canonical");
		assertEquals("Gemini 3.1 Pro", AgentModelNames.displayName("gemini", "gemini-3.1-pro"),
				"Gemini display name is canonical");
		assertEquals("K2.7 Coding Highspeed",
				AgentModelNames.displayName("kimi", "kimi-code/kimi-for-coding-highspeed"),
				"Kimi display name is canonical");
		assertEquals("Composer 2.5", AgentModelNames.displayName("cursor", "composer-2.5"),
				"Cursor display name is canonical");

		List<ModelCase> models = List.of(
				new ModelCase("codex", "gpt-5.6-sol", "codex", "sol"),
				new ModelCase("codex", "gpt-5.6-terra", "codex", "terra"),
				new ModelCase("codex", "gpt-5.6-luna", "codex", "luna"),
				new ModelCase("codex", "gpt-5.3-codex-spark", "codex", "spark"),
				new ModelCase("gemini", "gemini-3.1-pro", "gemini", "pro"),
				new ModelCase("gemini", "gemini-3.6-flash", "gemini", "flash"),
				new ModelCase("gemini", "claude-sonnet-4-6", "gemini", "claude"),
				new ModelCase("gemini", "gpt-oss-120b", "gemini", "oss"),
				new ModelCase("kimi", "kimi-code/k3", "kimi", "k3"),
				new ModelCase("kimi", "kimi-code/k3-256k", "kimi", "k3_long"),
				new ModelCase("kimi", "kimi-code/kimi-for-coding", "kimi", "coding"),
				new ModelCase("kimi", "kimi-code/kimi-for-coding-highspeed", "kimi", "coding_fast"),
				new ModelCase("cursor", "composer-2.5", "cursor", "composer"),
				new ModelCase("cursor", "grok-4.5", "cursor", "grok_45"),
				new ModelCase("cursor", "grok-4.6", "cursor", "grok_46"),
				new ModelCase("cursor", "cursor-next", "cursor", "cursor_next")
		);
		Set<String> transportCodes = new HashSet<>();
		for (ModelCase model : models) {
			for (int variant = 0; variant < AgentVisualIdentity.INDIVIDUAL_VARIANT_COUNT; variant++) {
				AgentVisualIdentity.Resolved resolved = AgentVisualIdentity.resolve(model.provider(), model.slug(), variant);
				assertEquals(model.provider(), resolved.providerKey(), "provider identity remains distinct");
				assertEquals(model.chassis(), resolved.providerChassis(), "provider chassis remains distinct");
				assertEquals(model.family(), resolved.modelFamilyKey(), "model resolves to its named family slot");
				assertEquals(variant, resolved.individualVariant(), "all four individual variants resolve");
				assertTrue(resolved.texturePath().startsWith(
						"arenaagents:textures/entity/" + model.provider() + "_"),
						"texture stays in the provider's project namespace");
				assertTrue(transportCodes.add(resolved.transportCode()), "transport codes are globally unique");
				assertEquals(resolved,
						AgentVisualIdentity.resolveTransportCode(resolved.transportCode()).orElseThrow(),
						"transport identity round-trips");
			}
		}

		AgentVisualIdentity.Resolved kimiK3 = AgentVisualIdentity.resolve("kimi", "kimi-code/k3", 0);
		AgentVisualIdentity.Resolved kimiK3256 = AgentVisualIdentity.resolve("kimi", "kimi-code/k3-256k", 0);
		assertEquals("K3", kimiK3.shortModelLabel(), "Kimi digit-bearing K3 label is preserved");
		assertEquals("K3 256K", kimiK3256.shortModelLabel(), "Kimi digit-bearing K3 256K label is preserved");
		assertTrue(!kimiK3.transportCode().equals(kimiK3256.transportCode()),
				"Kimi digit-bearing families keep distinct transport identities");

		AgentVisualIdentity.Resolved cursor = AgentVisualIdentity.resolve("cursor", "composer-1.5", 2);
		assertEquals("cursor", cursor.providerKey(), "Cursor keeps its provider identity");
		assertEquals("cursor", cursor.providerChassis(), "Cursor keeps its distinct chassis");
		assertTrue(cursor.texturePath().contains("cursor_"), "Cursor never uses Codex art");
		assertEquals(cursor, AgentVisualIdentity.resolveTransportCode(cursor.transportCode()).orElseThrow(),
				"Cursor transport identity round-trips");

		AgentVisualIdentity.Resolved unknown = AgentVisualIdentity.resolve("codex", "future-research-model-9", 3);
		assertEquals("spark", unknown.modelFamilyKey(), "unknown model uses the declared provider fallback family");
		assertEquals(unknown, AgentVisualIdentity.resolve("codex", "future-research-model-9", 3),
				"unknown model fallback is deterministic");
		assertTrue(AgentVisualIdentity.resolveTransportCode("not-a-transport-code").isEmpty(),
				"unknown transport identity is rejected");

		expectInvalidManifest(manifestWith(root -> root.addProperty("schemaVersion", 1.5D)),
				"schemaVersion must be an integer", "fractional manifest schema rejected");
		expectInvalidManifest(manifestWith(root -> root.addProperty("schemaVersion", 2_147_483_648L)),
				"schemaVersion must be an integer", "out-of-range manifest schema rejected");
		expectInvalidManifest(manifestWith(root -> root.addProperty("schemaVersion", 2)),
				"schemaVersion must be 1", "wrong manifest schema rejected");
		expectInvalidManifest(manifestWith(root -> root.addProperty("unexpected", true)),
				"unknown keys [unexpected]", "unknown manifest key rejected");
		expectInvalidManifest(manifestWith(root -> {
			JsonObject cursorProvider = root.getAsJsonArray("providers").get(3).getAsJsonObject();
			cursorProvider.addProperty("key", "codex");
			cursorProvider.getAsJsonArray("families").forEach(family ->
					family.getAsJsonObject().getAsJsonArray("variants").forEach(variant -> {
						JsonObject value = variant.getAsJsonObject();
						value.addProperty("texturePath", value.get("texturePath").getAsString()
								.replace("cursor_", "codex_"));
					}));
		}), "duplicate provider: codex", "duplicate manifest provider rejected");
		expectInvalidManifest(manifestWith(root -> root.getAsJsonArray("providers").get(0).getAsJsonObject()
				.getAsJsonArray("families").get(1).getAsJsonObject().addProperty("key", "sol")),
				"duplicate family sol", "duplicate manifest family rejected");
		expectInvalidManifest(manifestWith(root -> {
			var variants = root.getAsJsonArray("providers").get(0).getAsJsonObject()
					.getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("variants");
			variants.get(1).getAsJsonObject().addProperty("transportCode",
					variants.get(0).getAsJsonObject().get("transportCode").getAsString());
		}), "duplicate transport code: c00", "duplicate manifest transport code rejected");
		expectInvalidManifest(manifestWith(root -> root.getAsJsonArray("providers").get(0).getAsJsonObject()
				.getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("variants").remove(3)),
				"must declare exactly four variants", "invalid manifest variant count rejected");
		expectInvalidManifest(manifestWith(root -> root.getAsJsonArray("providers").get(0).getAsJsonObject()
				.getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("variants").get(0)
				.getAsJsonObject().addProperty("texturePath", "minecraft:textures/entity/stolen.png")),
				"project-owned codex entity texture", "non-project manifest texture rejected");
		return 488;
	}

	private static String manifestWith(Consumer<JsonObject> mutation) {
		try (var stream = AgentIdentityVerification.class.getClassLoader().getResourceAsStream(
				"assets/arenaagents/identity/agent_visual_manifest.json")) {
			if (stream == null) throw new AssertionError("agent visual manifest fixture is missing");
			JsonObject root = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
					.getAsJsonObject();
			mutation.accept(root);
			return root.toString();
		} catch (IOException exception) {
			throw new AssertionError("agent visual manifest fixture could not be read", exception);
		}
	}

	private static void expectInvalidManifest(String manifest, String expectedMessagePart, String label) {
		String firstMessage = invalidManifestMessage(manifest, label);
		String secondMessage = invalidManifestMessage(manifest, label);
		assertTrue(firstMessage.contains(expectedMessagePart), label + " reports its cause");
		assertEquals(firstMessage, secondMessage, label + " is stable");
	}

	private static String invalidManifestMessage(String manifest, String label) {
		try {
			AgentVisualIdentity.validateManifest(manifest);
		} catch (IllegalStateException exception) {
			return exception.getMessage();
		} catch (RuntimeException exception) {
			throw new AssertionError(label + " threw " + exception.getClass().getSimpleName(), exception);
		}
		throw new AssertionError(label + ": expected invalid manifest rejection");
	}

	private record ModelCase(String provider, String slug, String chassis, String family) {
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label + ": expected true");
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
	}
}
