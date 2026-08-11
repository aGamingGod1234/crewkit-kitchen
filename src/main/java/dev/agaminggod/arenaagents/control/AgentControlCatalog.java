package dev.agaminggod.arenaagents.control;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentControlCatalog {
	private static final String CODEX = "codex";
	private static final String GEMINI = "gemini";
	private static final String KIMI = "kimi";
	private static final List<String> PROVIDERS = List.of(CODEX, GEMINI, KIMI);
	private static final Map<String, List<String>> MODELS = Map.of(
			CODEX, List.of("gpt-5.6-sol"),
			GEMINI, List.of("gemini-3.1-pro", "gemini-3.6-flash", "gemini-3.5-flash"),
			KIMI, List.of(
					"kimi-code/k3",
					"kimi-code/k3-256k",
					"kimi-code/kimi-for-coding",
					"kimi-code/kimi-for-coding-highspeed"
			)
	);
	private static final List<String> CODEX_REASONING = List.of("low", "medium", "high", "xhigh", "max");
	private static final List<String> GEMINI_PRO_REASONING = List.of("high", "low");
	private static final List<String> GEMINI_FLASH_REASONING = List.of("high", "medium", "low");
	private static final List<String> KIMI_K3_REASONING = List.of("low", "high", "max");
	private static final List<String> KIMI_FIXED_REASONING = List.of("high");

	private AgentControlCatalog() {
	}

	public static List<String> providers() {
		return PROVIDERS;
	}

	public static List<String> models(String provider) {
		List<String> models = MODELS.get(requireProvider(provider));
		return Objects.requireNonNull(models);
	}

	public static String defaultModel(String provider) {
		return models(provider).getFirst();
	}

	public static String defaultReasoning(String provider, String model) {
		List<String> efforts = reasoningEfforts(provider, model);
		return efforts.contains("high") ? "high" : efforts.getFirst();
	}

	public static List<String> reasoningEfforts(String provider, String model) {
		String checkedProvider = requireProvider(provider);
		Objects.requireNonNull(model, "model must not be null");
		return switch (checkedProvider) {
			case CODEX -> CODEX_REASONING;
			case GEMINI -> model.equals("gemini-3.1-pro") ? GEMINI_PRO_REASONING : GEMINI_FLASH_REASONING;
			case KIMI -> model.equals("kimi-code/k3") || model.equals("kimi-code/k3-256k")
					? KIMI_K3_REASONING : KIMI_FIXED_REASONING;
			default -> throw new IllegalStateException("Unexpected provider: " + checkedProvider);
		};
	}

	public static String requireProvider(String provider) {
		String checked = Objects.requireNonNull(provider, "provider must not be null");
		if (!PROVIDERS.contains(checked)) {
			throw new IllegalArgumentException("Unsupported provider: " + checked);
		}
		return checked;
	}
}
