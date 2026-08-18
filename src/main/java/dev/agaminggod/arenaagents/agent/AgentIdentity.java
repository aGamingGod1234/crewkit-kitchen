package dev.agaminggod.arenaagents.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic, human-readable identity shared by fake players, UI, chat, and skins. */
public final class AgentIdentity {
	private static final int PLAYER_NAME_LIMIT = 16;
	private static final Pattern PLAYER_NAME = Pattern.compile("^([A-Za-z]{3,7})_[0-9A-Fa-f]{8}$");
	private static final String[][] SKIN_TOKENS = {
			{"codex", "cyan", "viol", "emer", "ambe"},
			{"gemini", "azur", "crim", "sola", "verd"},
			{"kimi", "moon", "ice", "orch", "sunr"}
	};

	private AgentIdentity() {
	}

	public static String displayName(AgentProfile profile) {
		Objects.requireNonNull(profile, "profile must not be null");
		return profile.userName().orElseGet(() -> defaultDisplayName(profile));
	}

	public static String defaultDisplayName(AgentProfile profile) {
		Objects.requireNonNull(profile, "profile must not be null");
		return modelName(profile.provider(), profile.model()) + " " + title(profile.reasoning())
				+ " | " + skinName(profile.provider(), profile.skinVariant());
	}

	public static String playerName(AgentId id, AgentProfile profile) {
		Objects.requireNonNull(id, "id must not be null");
		Objects.requireNonNull(profile, "profile must not be null");
		String identity = compactModel(profile.provider(), profile.model())
				+ compactSkin(profile.provider(), profile.skinVariant());
		String suffix = "_" + id.shortValue().toUpperCase(Locale.ROOT);
		int maximumIdentityLength = PLAYER_NAME_LIMIT - suffix.length();
		if (identity.length() > maximumIdentityLength) identity = identity.substring(0, maximumIdentityLength);
		return identity + suffix;
	}

	public static Optional<SkinIdentity> skinForPlayerName(String playerName) {
		Matcher matcher = PLAYER_NAME.matcher(Objects.requireNonNullElse(playerName, ""));
		if (!matcher.matches()) return Optional.empty();
		String identity = matcher.group(1).toLowerCase(Locale.ROOT);
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

	public record SkinIdentity(String provider, int variant) {
		public SkinIdentity {
			provider = normalizedProvider(provider);
			if (variant < 0 || variant >= 4) throw new IllegalArgumentException("skin variant is out of range");
		}
	}

	public static String skinName(String provider, int variant) {
		String[] names = switch (normalizedProvider(provider)) {
			case "gemini" -> new String[]{"Azure", "Crimson", "Solar", "Verdant"};
			case "kimi" -> new String[]{"Moon", "Ice", "Orchid", "Sunrise"};
			default -> new String[]{"Cyan", "Violet", "Emerald", "Amber"};
		};
		return names[Math.floorMod(variant, names.length)];
	}

	private static String compactModel(String provider, String model) {
		String lower = model.toLowerCase(Locale.ROOT);
		if (lower.contains("sol")) return "Sol";
		if (lower.contains("terra")) return "Ter";
		if (lower.contains("luna")) return "Lun";
		if (lower.contains("k3")) return "K3";
		if (normalizedProvider(provider).equals("kimi")) return "Kimi";
		if (normalizedProvider(provider).equals("gemini")) return "Gem";
		return "GPT";
	}

	private static String compactSkin(String provider, int variant) {
		String name = skinName(provider, variant);
		return name.substring(0, Math.min(4, name.length()));
	}

	private static String modelName(String provider, String model) {
		String lower = model.toLowerCase(Locale.ROOT);
		if (lower.equals("gpt-5.6-sol")) return "Sol";
		if (lower.equals("gpt-5.6-sol-wm")) return "Sol WM";
		if (lower.equals("gpt-5.6-terra")) return "Terra";
		if (lower.equals("gpt-5.6-luna")) return "Luna";
		if (lower.endsWith("/k3")) return "Kimi K3";
		if (lower.endsWith("/k3-256k")) return "Kimi K3 256K";
		if (lower.contains("kimi-for-coding-highspeed")) return "Kimi Coding Fast";
		if (lower.contains("kimi-for-coding")) return "Kimi Coding";
		if (lower.startsWith("gemini-")) return readable(model);
		String readable = readable(model);
		return normalizedProvider(provider).equals("codex") ? readable : title(provider) + " " + readable;
	}

	private static String readable(String model) {
		String value = model.startsWith("kimi-code/") ? model.substring("kimi-code/".length()) : model;
		StringBuilder result = new StringBuilder();
		for (String part : value.replace('_', '-').split("-")) {
			if (part.isBlank()) continue;
			if (!result.isEmpty()) result.append(' ');
			result.append(part.equalsIgnoreCase("gpt") ? "GPT" : title(part));
		}
		return result.isEmpty() ? model : result.toString();
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
