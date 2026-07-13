package dev.agaminggod.arenaagents.client.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class WeaponSelector {
	private static final Map<String, Integer> WEAPON_PRIORITY = Map.ofEntries(
			Map.entry("minecraft:netherite_sword", 100),
			Map.entry("minecraft:diamond_sword", 95),
			Map.entry("minecraft:netherite_axe", 90),
			Map.entry("minecraft:iron_sword", 85),
			Map.entry("minecraft:diamond_axe", 80),
			Map.entry("minecraft:stone_sword", 75),
			Map.entry("minecraft:iron_axe", 70),
			Map.entry("minecraft:golden_sword", 65),
			Map.entry("minecraft:wooden_sword", 60),
			Map.entry("minecraft:stone_axe", 55),
			Map.entry("minecraft:golden_axe", 50),
			Map.entry("minecraft:wooden_axe", 45)
	);

	public Optional<WeaponCandidate> selectBest(List<WeaponCandidate> candidates) {
		Objects.requireNonNull(candidates, "candidates must not be null");
		return candidates.stream()
				.filter(Objects::nonNull)
				.filter(candidate -> WEAPON_PRIORITY.containsKey(candidate.itemId()))
				.max(Comparator
						.comparingInt((WeaponCandidate candidate) -> WEAPON_PRIORITY.get(candidate.itemId()))
						.thenComparing(Comparator.comparingInt(WeaponCandidate::slot).reversed()));
	}
}
