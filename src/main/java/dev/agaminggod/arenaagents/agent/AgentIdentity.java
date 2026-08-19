package dev.agaminggod.arenaagents.agent;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, human-readable identity shared by fake players, UI, chat, and skins. */
public final class AgentIdentity {
	private static final int PLAYER_NAME_LIMIT = 16;
	private static final Base64.Encoder OPERATOR_ID_ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Pattern PLAYER_NAME = Pattern.compile("^([A-Za-z][A-Za-z0-9]{1,6})_[0-9A-Fa-f]{8}$");
	private static final String[] CODEX_LEGACY_MODELS = {"sol", "ter", "lun", "gpt"};
	private static final String[] CODEX_LEGACY_VARIANTS = {"cyan", "viol", "emer", "ambe"};
	private static final String[] GEMINI_LEGACY_MODELS = {"gem"};
	private static final String[] GEMINI_LEGACY_VARIANTS = {"azur", "crim", "sola", "verd"};
	private static final String[] KIMI_K3_LEGACY_MODELS = {"k3"};
	private static final String[] KIMI_K3_LEGACY_VARIANTS = {"moon", "ice", "orch", "sunr"};
	private static final String[] KIMI_LEGACY_MODELS = {"kimi"};
	private static final String[] KIMI_LEGACY_VARIANTS = {"moo", "ice", "orc", "sun"};

	private AgentIdentity() {
	}

	/** Compatibility formatter for call sites that do not yet carry the stable agent ID. */
	public static String displayName(AgentProfile profile) {
		Objects.requireNonNull(profile, "profile must not be null");
		return profile.userName().orElseGet(() -> defaultDisplayName(profile));
	}

	public static String displayName(AgentId id, AgentProfile profile) {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(profile, "profile must not be null");
		return profile.userName().orElseGet(() -> AgentModelNames.shortLabel(profile.provider(), profile.model())
				+ " " + encodedOperatorId(id));
	}

	static String canonicalIdentityKey(String value) {
		return Objects.requireNonNull(value, "identity value must not be null").toLowerCase(Locale.ROOT);
	}

	public static boolean sameIdentity(String first, String second) {
		return canonicalIdentityKey(first).equals(canonicalIdentityKey(second));
	}

	private static String encodedOperatorId(AgentId id) {
		ByteBuffer bytes = ByteBuffer.allocate(Long.BYTES * 2);
		bytes.putLong(id.value().getMostSignificantBits());
		bytes.putLong(id.value().getLeastSignificantBits());
		return OPERATOR_ID_ENCODER.encodeToString(bytes.array());
	}

	public static Optional<String> worldTag(AgentProfile profile) {
		Objects.requireNonNull(profile, "profile must not be null");
		return profile.userName().map(name -> {
			AgentVisualIdentity.Resolved identity = profile.visualIdentity();
			return identity.providerGlyph() + " " + name + " · "
					+ AgentModelNames.shortLabel(profile.provider(), profile.model());
		});
	}

	public static String defaultDisplayName(AgentProfile profile) {
		Objects.requireNonNull(profile, "profile must not be null");
		return AgentModelNames.shortLabel(profile.provider(), profile.model()) + " " + title(profile.reasoning());
	}

	public static String playerName(AgentId id, AgentProfile profile) {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(profile, "profile must not be null");
		String identity = profile.visualIdentity().transportCode();
		String suffix = "_" + id.shortValue().toUpperCase(Locale.ROOT);
		String playerName = identity + suffix;
		if (playerName.length() > PLAYER_NAME_LIMIT || !PLAYER_NAME.matcher(playerName).matches()) {
			throw new IllegalStateException("Manifest transport code cannot form a valid Minecraft player name: " + identity);
		}
		return playerName;
	}

	/** Returns Minecraft's deterministic offline UUID for the exact technical player name. */
	public static UUID offlinePlayerUuid(String technicalName) {
		String checkedName = Objects.requireNonNull(technicalName, "technicalName must not be null");
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + checkedName).getBytes(StandardCharsets.UTF_8));
	}

	public static Optional<SkinIdentity> skinForPlayerName(String playerName) {
		Matcher matcher = PLAYER_NAME.matcher(Objects.requireNonNullElse(playerName, ""));
		if (!matcher.matches()) return Optional.empty();
		String identity = matcher.group(1).toLowerCase(Locale.ROOT);
		Optional<AgentVisualIdentity.Resolved> resolved = AgentVisualIdentity.resolveTransportCode(identity);
		if (resolved.isPresent()) {
			AgentVisualIdentity.Resolved value = resolved.orElseThrow();
			return Optional.of(new SkinIdentity(
					value.providerKey(), value.modelFamilyKey(), value.individualVariant()));
		}
		return legacySkin(identity, "codex", CODEX_LEGACY_MODELS, CODEX_LEGACY_VARIANTS)
				.or(() -> legacySkin(identity, "gemini", GEMINI_LEGACY_MODELS, GEMINI_LEGACY_VARIANTS))
				.or(() -> legacySkin(identity, "kimi", KIMI_K3_LEGACY_MODELS, KIMI_K3_LEGACY_VARIANTS))
				.or(() -> legacySkin(identity, "kimi", KIMI_LEGACY_MODELS, KIMI_LEGACY_VARIANTS));
	}

	private static Optional<SkinIdentity> legacySkin(
			String identity,
			String provider,
			String[] modelTokens,
			String[] variantTokens
	) {
		for (String modelToken : modelTokens) {
			if (!identity.startsWith(modelToken)) continue;
			String variantToken = identity.substring(modelToken.length());
			for (int variant = 0; variant < variantTokens.length; variant++) {
				if (variantToken.equals(variantTokens[variant])) {
					return Optional.of(new SkinIdentity(provider, variant));
				}
			}
		}
		return Optional.empty();
	}

	public record SkinIdentity(String provider, String modelFamily, int variant) {
		public SkinIdentity(String provider, int variant) {
			this(provider, "", variant);
		}

		public SkinIdentity {
			provider = normalizedProvider(provider);
			modelFamily = Objects.requireNonNull(modelFamily, "modelFamily must not be null")
					.toLowerCase(Locale.ROOT);
			if (variant < 0 || variant >= AgentVisualIdentity.INDIVIDUAL_VARIANT_COUNT) {
				throw new IllegalArgumentException("skin variant is out of range");
			}
		}
	}

	private static String normalizedProvider(String provider) {
		return Objects.requireNonNull(provider, "provider must not be null").toLowerCase(Locale.ROOT);
	}

	private static String title(String value) {
		if (value == null || value.isBlank()) return "";
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
	}
}
