package dev.agaminggod.arenaagents.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict project-owned mapping from provider/model identity to visual and transport identity. */
public final class AgentVisualIdentity {
	public static final int INDIVIDUAL_VARIANT_COUNT = 4;

	private static final String MANIFEST_RESOURCE =
			"assets/arenaagents/identity/agent_visual_manifest.json";
	private static final Set<String> REQUIRED_PROVIDERS = Set.of("codex", "gemini", "kimi", "cursor");
	private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
	private static final Pattern TRANSPORT_CODE = Pattern.compile("[a-z][a-z0-9]{1,6}");
	private static final Pattern TEXTURE_PATH = Pattern.compile(
			"arenaagents:textures/entity/[a-z0-9_./-]+\\.png");

	private static final Manifest MANIFEST = loadManifest();

	private AgentVisualIdentity() {
	}

	public static Resolved resolve(String provider, String exactModelSlug, int variant) {
		String providerKey = requireText(provider, "provider").toLowerCase(Locale.ROOT);
		String modelSlug = requireText(exactModelSlug, "model slug").toLowerCase(Locale.ROOT);
		if (variant < 0 || variant >= INDIVIDUAL_VARIANT_COUNT) {
			throw new IllegalArgumentException("individual variant must be between 0 and 3");
		}
		ProviderIdentity providerIdentity = MANIFEST.providers().get(providerKey);
		if (providerIdentity == null) throw new IllegalArgumentException("Unsupported provider: " + providerKey);
		FamilyIdentity family = providerIdentity.models().getOrDefault(modelSlug, providerIdentity.fallback());
		VariantIdentity visualVariant = family.variants().get(variant);
		return new Resolved(
				providerIdentity.key(),
				providerIdentity.chassis(),
				family.key(),
				variant,
				visualVariant.texturePath(),
				family.shortLabel(),
				providerIdentity.glyph(),
				visualVariant.transportCode()
		);
	}

	public static Optional<Resolved> resolveTransportCode(String code) {
		if (code == null || code.isBlank()) return Optional.empty();
		TransportIdentity identity = MANIFEST.transportCodes().get(code.toLowerCase(Locale.ROOT));
		if (identity == null) return Optional.empty();
		ProviderIdentity provider = identity.provider();
		FamilyIdentity family = identity.family();
		VariantIdentity variant = family.variants().get(identity.variant());
		return Optional.of(new Resolved(
				provider.key(),
				provider.chassis(),
				family.key(),
				identity.variant(),
				variant.texturePath(),
				family.shortLabel(),
				provider.glyph(),
				variant.transportCode()
		));
	}

	private static Manifest loadManifest() {
		try (InputStream stream = AgentVisualIdentity.class.getClassLoader().getResourceAsStream(MANIFEST_RESOURCE)) {
			if (stream == null) throw invalid("missing classpath resource " + MANIFEST_RESOURCE);
			JsonElement parsed = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
			return parseManifest(requireObject(parsed, "manifest"));
		} catch (IOException exception) {
			throw new ExceptionInInitializerError(exception);
		} catch (RuntimeException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static Manifest parseManifest(JsonObject root) {
		requireKeys(root, Set.of("schemaVersion", "providers"), "manifest");
		if (requiredInt(root, "schemaVersion", "manifest") != 1) {
			throw invalid("manifest schemaVersion must be 1");
		}
		JsonArray providers = requiredArray(root, "providers", "manifest");
		if (providers.size() != REQUIRED_PROVIDERS.size()) {
			throw invalid("manifest must declare exactly four providers");
		}

		Map<String, ProviderIdentity> byProvider = new LinkedHashMap<>();
		Map<String, TransportIdentity> byTransportCode = new HashMap<>();
		Set<String> texturePaths = new HashSet<>();
		for (JsonElement providerElement : providers) {
			ProviderIdentity provider = parseProvider(
					requireObject(providerElement, "provider"), byTransportCode, texturePaths);
			if (byProvider.putIfAbsent(provider.key(), provider) != null) {
				throw invalid("duplicate provider: " + provider.key());
			}
		}
		if (!byProvider.keySet().equals(REQUIRED_PROVIDERS)) {
			throw invalid("manifest providers must be exactly " + REQUIRED_PROVIDERS);
		}
		return new Manifest(Map.copyOf(byProvider), Map.copyOf(byTransportCode));
	}

	private static ProviderIdentity parseProvider(
			JsonObject object,
			Map<String, TransportIdentity> byTransportCode,
			Set<String> texturePaths
	) {
		requireKeys(object, Set.of("key", "chassis", "glyph", "fallbackFamily", "families"), "provider");
		String key = requiredKey(object, "key", "provider");
		String chassis = requiredKey(object, "chassis", "provider " + key);
		String glyph = requiredString(object, "glyph", "provider " + key);
		if (glyph.codePointCount(0, glyph.length()) != 1) {
			throw invalid("provider " + key + " glyph must be one code point");
		}
		String fallbackFamily = requiredKey(object, "fallbackFamily", "provider " + key);
		JsonArray families = requiredArray(object, "families", "provider " + key);
		if (families.size() != 4) throw invalid("provider " + key + " must declare exactly four families");

		Map<String, FamilyIdentity> byFamily = new LinkedHashMap<>();
		Map<String, FamilyIdentity> byModel = new HashMap<>();
		List<PendingTransportIdentity> transportIdentities = new ArrayList<>();
		for (JsonElement familyElement : families) {
			FamilyIdentity family = parseFamily(key, requireObject(familyElement, "family"),
					transportIdentities, texturePaths);
			if (byFamily.putIfAbsent(family.key(), family) != null) {
				throw invalid("duplicate family " + family.key() + " for provider " + key);
			}
			for (String model : family.models()) {
				if (byModel.putIfAbsent(model, family) != null) {
					throw invalid("duplicate model " + model + " for provider " + key);
				}
			}
		}
		FamilyIdentity fallback = byFamily.get(fallbackFamily);
		if (fallback == null) throw invalid("provider " + key + " has an unknown fallback family");
		ProviderIdentity provider = new ProviderIdentity(
				key, chassis, glyph, Map.copyOf(byModel), fallback);
		for (PendingTransportIdentity pending : transportIdentities) {
			TransportIdentity identity = new TransportIdentity(provider, pending.family(), pending.variant());
			if (byTransportCode.putIfAbsent(pending.code(), identity) != null) {
				throw invalid("duplicate transport code: " + pending.code());
			}
		}
		return provider;
	}

	private static FamilyIdentity parseFamily(
			String provider,
			JsonObject object,
			List<PendingTransportIdentity> transportIdentities,
			Set<String> texturePaths
	) {
		requireKeys(object, Set.of("key", "models", "shortLabel", "variants"), "family");
		String key = requiredKey(object, "key", "family");
		String shortLabel = requiredString(object, "shortLabel", "family " + key);
		JsonArray models = requiredArray(object, "models", "family " + key);
		Set<String> modelSlugs = new HashSet<>();
		for (JsonElement modelElement : models) {
			if (!modelElement.isJsonPrimitive() || !modelElement.getAsJsonPrimitive().isString()) {
				throw invalid("family " + key + " model must be a string");
			}
			String model = requireText(modelElement.getAsString(), "model slug").toLowerCase(Locale.ROOT);
			if (!modelSlugs.add(model)) throw invalid("duplicate model " + model + " in family " + key);
		}

		JsonArray variants = requiredArray(object, "variants", "family " + key);
		if (variants.size() != INDIVIDUAL_VARIANT_COUNT) {
			throw invalid("family " + key + " must declare exactly four variants");
		}
		List<VariantIdentity> variantIdentities = new ArrayList<>(INDIVIDUAL_VARIANT_COUNT);
		for (JsonElement variantElement : variants) {
			JsonObject variantObject = requireObject(variantElement, "variant");
			requireKeys(variantObject, Set.of("texturePath", "transportCode"), "variant");
			String texturePath = requiredString(variantObject, "texturePath", "variant");
			if (!TEXTURE_PATH.matcher(texturePath).matches()
					|| !texturePath.startsWith("arenaagents:textures/entity/" + provider + "_")) {
				throw invalid("texture path must be a project-owned " + provider + " entity texture: " + texturePath);
			}
			if (!texturePaths.add(texturePath)) throw invalid("duplicate texture path: " + texturePath);
			String transportCode = requiredString(variantObject, "transportCode", "variant")
					.toLowerCase(Locale.ROOT);
			if (!TRANSPORT_CODE.matcher(transportCode).matches()) {
				throw invalid("invalid transport code: " + transportCode);
			}
			variantIdentities.add(new VariantIdentity(texturePath, transportCode));
		}
		FamilyIdentity family = new FamilyIdentity(
				key, Set.copyOf(modelSlugs), shortLabel, List.copyOf(variantIdentities));
		for (int variant = 0; variant < variantIdentities.size(); variant++) {
			transportIdentities.add(new PendingTransportIdentity(
					variantIdentities.get(variant).transportCode(), family, variant));
		}
		return family;
	}

	private static JsonObject requireObject(JsonElement value, String label) {
		if (value == null || !value.isJsonObject()) throw invalid(label + " must be an object");
		return value.getAsJsonObject();
	}

	private static JsonArray requiredArray(JsonObject object, String key, String label) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonArray()) throw invalid(label + "." + key + " must be an array");
		return value.getAsJsonArray();
	}

	private static int requiredInt(JsonObject object, String key, String label) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw invalid(label + "." + key + " must be an integer");
		}
		try {
			return value.getAsInt();
		} catch (NumberFormatException exception) {
			throw invalid(label + "." + key + " must be an integer");
		}
	}

	private static String requiredKey(JsonObject object, String key, String label) {
		String value = requiredString(object, key, label);
		if (!KEY.matcher(value).matches()) throw invalid(label + "." + key + " is invalid: " + value);
		return value;
	}

	private static String requiredString(JsonObject object, String key, String label) {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw invalid(label + "." + key + " must be a string");
		}
		return requireText(value.getAsString(), label + "." + key);
	}

	private static void requireKeys(JsonObject object, Set<String> allowed, String label) {
		if (!object.keySet().equals(allowed)) {
			Set<String> unknown = new HashSet<>(object.keySet());
			unknown.removeAll(allowed);
			Set<String> missing = new HashSet<>(allowed);
			missing.removeAll(object.keySet());
			throw invalid(label + " has unknown keys " + unknown + " and missing keys " + missing);
		}
	}

	private static String requireText(String value, String label) {
		String checked = Objects.requireNonNull(value, label + " must not be null").trim();
		if (checked.isEmpty()) throw invalid(label + " must not be blank");
		return checked;
	}

	private static IllegalStateException invalid(String message) {
		return new IllegalStateException("Invalid agent visual manifest: " + message);
	}

	public record Resolved(
			String providerKey,
			String providerChassis,
			String modelFamilyKey,
			int individualVariant,
			String texturePath,
			String shortModelLabel,
			String providerGlyph,
			String transportCode
	) {
	}

	private record Manifest(
			Map<String, ProviderIdentity> providers,
			Map<String, TransportIdentity> transportCodes
	) {
	}

	private record ProviderIdentity(
			String key,
			String chassis,
			String glyph,
			Map<String, FamilyIdentity> models,
			FamilyIdentity fallback
	) {
	}

	private record FamilyIdentity(
			String key,
			Set<String> models,
			String shortLabel,
			List<VariantIdentity> variants
	) {
	}

	private record VariantIdentity(String texturePath, String transportCode) {
	}

	private record PendingTransportIdentity(String code, FamilyIdentity family, int variant) {
	}

	private record TransportIdentity(ProviderIdentity provider, FamilyIdentity family, int variant) {
	}
}
