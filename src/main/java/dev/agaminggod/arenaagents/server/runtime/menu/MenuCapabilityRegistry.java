package dev.agaminggod.arenaagents.server.runtime.menu;

import dev.agaminggod.arenaagents.agent.AgentDomainException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Whitelists vanilla menus whose semantics and slot ownership are understood by the agent runtime. */
public final class MenuCapabilityRegistry {
	private static final Map<String, List<String>> CAPABILITIES = Map.of(
			"minecraft:merchant", List.of("select_trade", "transfer_exact"),
			"minecraft:enchantment", List.of("select_enchantment", "transfer_exact"),
			"minecraft:anvil", List.of("set_anvil_name", "transfer_exact", "take_result"),
			"minecraft:smithing", List.of("transfer_exact", "take_result"),
			"minecraft:brewing_stand", List.of("transfer_exact", "take_result"),
			"minecraft:loom", List.of("select_pattern", "transfer_exact", "take_result"),
			"minecraft:stonecutter", List.of("select_recipe", "transfer_exact", "take_result")
	);

	private MenuCapabilityRegistry() {
	}

	public static Optional<List<String>> capabilities(String menuId) {
		return Optional.ofNullable(CAPABILITIES.get(Objects.requireNonNull(menuId, "menuId must not be null")));
	}

	public static List<String> requireSupported(String menuId) {
		return capabilities(menuId).orElseThrow(() -> new AgentDomainException(
				"UNSUPPORTED_MENU",
				"Unsupported or modded menu: " + menuId
		));
	}
}
