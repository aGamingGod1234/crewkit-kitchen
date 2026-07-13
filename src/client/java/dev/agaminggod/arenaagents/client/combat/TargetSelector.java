package dev.agaminggod.arenaagents.client.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public final class TargetSelector {
	private static final String PLAYER_PREFIX = "player:";
	private static final String UUID_PREFIX = "uuid:";
	private static final String TYPE_PREFIX = "type:";
	private static final String NEAREST_HOSTILE = "nearest_hostile";
	private static final String NEAREST_PLAYER = "nearest_player";
	private static final Comparator<CombatTarget> ORDER = Comparator
			.comparingDouble(CombatTarget::distanceSquared)
			.thenComparing(CombatTarget::uuid);

	public Optional<CombatTarget> select(List<CombatTarget> targets, String selector) {
		Objects.requireNonNull(targets, "targets must not be null");
		Objects.requireNonNull(selector, "selector must not be null");
		Predicate<CombatTarget> predicate = selectorPredicate(selector);
		return targets.stream()
				.filter(Objects::nonNull)
				.filter(CombatTarget::alive)
				.filter(predicate)
				.min(ORDER);
	}

	private static Predicate<CombatTarget> selectorPredicate(String selector) {
		if (selector.startsWith(PLAYER_PREFIX)) {
			String name = requireSuffix(selector, PLAYER_PREFIX);
			return target -> target.player() && target.name().equals(name);
		}
		if (selector.startsWith(UUID_PREFIX)) {
			UUID uuid;
			try {
				uuid = UUID.fromString(requireSuffix(selector, UUID_PREFIX));
			} catch (IllegalArgumentException exception) {
				return target -> false;
			}
			return target -> target.uuid().equals(uuid);
		}
		if (selector.startsWith(TYPE_PREFIX)) {
			String typeId = requireSuffix(selector, TYPE_PREFIX);
			return target -> target.typeId().equals(typeId);
		}
		return switch (selector) {
			case NEAREST_HOSTILE -> CombatTarget::hostile;
			case NEAREST_PLAYER -> CombatTarget::player;
			default -> target -> false;
		};
	}

	private static String requireSuffix(String selector, String prefix) {
		String suffix = selector.substring(prefix.length());
		return suffix.isBlank() ? "\u0000" : suffix;
	}
}
