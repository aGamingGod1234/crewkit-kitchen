package dev.agaminggod.arenaagents.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, human-readable identity shared by fake players, UI, chat, and skins. */
public final class AgentIdentity {
	private static final int PLAYER_NAME_LIMIT = 16;
	private static final int OPERATOR_ID_LENGTH = 4;
	private static final Pattern PLAYER_NAME = Pattern.compile("^([A-Za-z][A-Za-z0-9]{1,6})_[0-9A-Fa-f]{8}$");
	private static final String[][] SKIN_TOKENS = {
			{"codex", "cyan", "viol", "emer", "ambe"},
			{"gemini", "azur", "crim", "sola", "verd"},
			{"kimi", "moon", "ice", "orch", "sunr"}
	};

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
				+ " " + id.shortValue().substring(0, OPERATOR_ID_LENGTH));
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
		if (identity.startsWith("kimi")) {
			String token = identity.substring(4);
			String[] compactTokens = {"moo", "ice", "orc", "sun"};
			for (int variant = 0; variant < compactTokens.length; variant++) {
				if (token.equals(compactTokens[variant])) {
					return Optional.of(new SkinIdentity("kimi", variant));
				}
			}
		}
		for (String[] family : SKIN_TOKENS) {
			for (int variant = 0; variant < family.length - 1; variant++) {
				if (identity.endsWith(family[variant + 1])) {
					return Optional.of(new SkinIdentity(family[0], variant));
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
