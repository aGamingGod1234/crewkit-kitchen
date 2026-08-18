package dev.agaminggod.arenaagents.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class LocalizationVerification {
	private LocalizationVerification() {
	}

	public static int verify() {
		String source;
		try (InputStream stream = LocalizationVerification.class.getResourceAsStream(
				"/assets/arenaagents/lang/en_us.json")) {
			if (stream == null) throw new AssertionError("English language resource is missing");
			source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException exception) {
			throw new AssertionError("English language resource could not be read", exception);
		}
		JsonObject language = JsonParser.parseString(source).getAsJsonObject();
		List<String> required = List.of(
				"screen.arenaagents.controls.title",
				"screen.arenaagents.setup.title",
				"screen.arenaagents.results.title",
				"screen.arenaagents.navigation.agents",
				"screen.arenaagents.navigation.group",
				"screen.arenaagents.navigation.live",
				"screen.arenaagents.navigation.build",
				"screen.arenaagents.build.location",
				"screen.arenaagents.build.processing",
				"screen.arenaagents.roster.current",
				"screen.arenaagents.live.health",
				"screen.arenaagents.results.return"
		);
		for (String key : required) {
			if (!language.has(key) || language.get(key).getAsString().isBlank()) {
				throw new AssertionError("Missing readable English copy for " + key);
			}
		}
		if (source.indexOf('\uFFFD') >= 0 || source.indexOf('\u00C2') >= 0 || source.indexOf('\u00E2') >= 0) {
			throw new AssertionError("English copy contains mojibake markers");
		}
		if (source.indexOf('·') >= 0 || source.indexOf('—') >= 0 || source.indexOf('…') >= 0) {
			throw new AssertionError("English copy uses ambiguous decorative separators");
		}
		assertResource("/assets/arenaagents/font/console.json", "custom console font definition");
		assertResource("/assets/arenaagents/font/roboto_regular.ttf", "custom console font asset");
		verifyFontProviderAssets();
		return required.size() + 5;
	}

	private static void verifyFontProviderAssets() {
		JsonObject definition;
		try (InputStream stream = LocalizationVerification.class.getResourceAsStream(
				"/assets/arenaagents/font/console.json")) {
			if (stream == null) throw new AssertionError("custom console font definition is missing");
			definition = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
					.getAsJsonObject();
		} catch (IOException exception) {
			throw new AssertionError("custom console font definition could not be read", exception);
		}

		int ttfProviders = 0;
		for (var provider : definition.getAsJsonArray("providers")) {
			JsonObject value = provider.getAsJsonObject();
			if (!"ttf".equals(value.get("type").getAsString())) continue;
			ttfProviders++;
			if (!value.has("oversample") || value.get("oversample").getAsDouble() < 4.0D) {
				throw new AssertionError("custom console TTF must use at least 4x oversampling for sharp GUI-scale text");
			}
			String identifier = value.get("file").getAsString();
			int separator = identifier.indexOf(':');
			if (separator <= 0 || separator == identifier.length() - 1) {
				throw new AssertionError("TTF provider file must use a namespaced identifier: " + identifier);
			}
			String resolved = "/assets/" + identifier.substring(0, separator) + "/font/"
					+ identifier.substring(separator + 1);
			assertResource(resolved, "TTF provider target " + identifier);
		}
		if (ttfProviders == 0) throw new AssertionError("custom console font has no TTF provider");
	}

	private static void assertResource(String path, String label) {
		try (InputStream stream = LocalizationVerification.class.getResourceAsStream(path)) {
			if (stream == null || stream.read() < 0) throw new AssertionError(label + " is missing or empty");
		} catch (IOException exception) {
			throw new AssertionError(label + " could not be read", exception);
		}
	}
}
