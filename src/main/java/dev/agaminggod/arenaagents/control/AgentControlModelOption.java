package dev.agaminggod.arenaagents.control;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Public, bounded provider model metadata safe to send to an in-game client. */
public record AgentControlModelOption(
		String provider,
		String model,
		String displayName,
		List<String> reasoningEfforts,
		List<String> serviceTiers
) {
	public static final int MAX_OPTIONS = 128;
	private static final Set<String> PROVIDERS = Set.of("codex", "gemini", "claude");
	/** Ascending thinking depth; unknown efforts follow these in their input order. */
	public static final List<String> REASONING_ORDER =
			List.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");
	/** Normal speed first, then fast; unknown tiers follow in their input order. */
	public static final List<String> SERVICE_TIER_ORDER = List.of("priority", "fast");

	public AgentControlModelOption {
		provider = identifier(provider, "provider", 24).toLowerCase(Locale.ROOT);
		if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("Unsupported provider: " + provider);
		model = identifier(model, "model", 128);
		displayName = text(displayName, "displayName", 96);
		// Sorting here keeps the fallback catalog and the coordinator's runtime catalog on one scale,
		// so every selector steps from the lowest value upward regardless of how the list arrived.
		reasoningEfforts = ordered(identifiers(reasoningEfforts, "reasoningEfforts", 12, 32, false), REASONING_ORDER);
		serviceTiers = ordered(identifiers(serviceTiers, "serviceTiers", 8, 24, true), SERVICE_TIER_ORDER);
	}

	/** Stable sort by canonical rank; values missing from the canonical list keep their relative order last. */
	static List<String> ordered(List<String> values, List<String> canonical) {
		return values.stream()
				.sorted(Comparator.comparingInt(value -> {
					int rank = canonical.indexOf(value);
					return rank < 0 ? canonical.size() : rank;
				}))
				.toList();
	}

	private static List<String> identifiers(
			List<String> values, String field, int maximumItems, int maximumLength, boolean mayBeEmpty
	) {
		List<String> checked = List.copyOf(Objects.requireNonNull(values, field + " must not be null"));
		if ((!mayBeEmpty && checked.isEmpty()) || checked.size() > maximumItems) {
			throw new IllegalArgumentException(field + " has an invalid item count");
		}
		List<String> normalized = checked.stream()
				.map(value -> identifier(value, field + " entry", maximumLength).toLowerCase(Locale.ROOT))
				.distinct()
				.toList();
		if (normalized.size() != checked.size()) throw new IllegalArgumentException(field + " contains duplicates");
		return normalized;
	}

	private static String identifier(String value, String field, int maximumLength) {
		String checked = text(value, field, maximumLength);
		if (checked.chars().anyMatch(Character::isWhitespace)) {
			throw new IllegalArgumentException(field + " must not contain whitespace");
		}
		return checked;
	}

	private static String text(String value, String field, int maximumLength) {
		String checked = Objects.requireNonNull(value, field + " must not be null").strip();
		if (checked.isEmpty() || checked.length() > maximumLength
				|| checked.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(field + " is invalid");
		}
		return checked;
	}
}
